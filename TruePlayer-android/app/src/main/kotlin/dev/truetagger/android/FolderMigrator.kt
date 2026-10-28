package dev.truetagger.android

import android.content.Context
import android.media.MediaScannerConnection
import dev.truetagger.data.FolderFile
import dev.truetagger.data.M3u
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.PlannedMove
import dev.truetagger.model.TrackKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cria `songs/`, `playlists/` e `.trueplayer/` na pasta escolhida e move para dentro delas
 * as músicas e playlists que já estavam soltas.
 *
 * Garantias: só move (renomeação no mesmo provedor, sem copiar bytes), nunca apaga e nunca
 * sobrescreve; qualquer item que não puder ser movido fica onde estava e é listado no
 * relatório. É idempotente: se for interrompida, rodar de novo retoma de onde parou.
 */
class FolderMigrator(context: Context, private val tree: SafTree) {
    private val appContext = context.applicationContext

    data class Layout(val songsDocId: String, val playlistsDocId: String, val metaDocId: String)

    data class Preview(val songs: Int, val playlists: Int, val plan: List<PlannedMove>)

    data class Report(
        val songsMoved: Int,
        val playlistsMoved: Int,
        val failed: List<Failure>,
        val logName: String,
    ) {
        val moved: Int get() = songsMoved + playlistsMoved
    }

    data class Failure(val path: String, val reason: String)

    /** O que está sendo movido agora; [index]/[total] contam só itens do mesmo tipo (músicas ou playlists). */
    data class Progress(
        val kind: PlannedMove.Kind,
        val name: String,
        val index: Int,
        val total: Int,
        val overallDone: Int,
        val overallTotal: Int,
    )

    @Serializable
    private data class LogItem(val from: String, val to: String, val kind: String, val status: String = "pendente")

    @Serializable
    private data class MigrationLog(val startedAt: String, val finishedAt: String = "", val items: List<LogItem>)

    private val json = Json { prettyPrint = true }

    /** Cria as pastas padrão (não move nada). */
    fun ensureLayout(): Layout? {
        val songs = tree.ensureDir(tree.rootDocId, MigrationPlanner.SONGS_DIR) ?: return null
        val playlists = tree.ensureDir(tree.rootDocId, MigrationPlanner.PLAYLISTS_DIR) ?: return null
        val meta = tree.ensureDir(tree.rootDocId, MigrationPlanner.META_DIR) ?: return null
        return Layout(songs, playlists, meta)
    }

    data class Diagnosis(
        val layoutOk: Boolean,
        val totalFiles: Int,
        val audioFiles: Int,
        val audioInSongs: Int,
        val audioOutside: Int,
        val playlistsOutside: Int,
        val plan: List<PlannedMove>,
        val error: String? = null,
    )

    /** Olha a pasta e explica o que encontrou (sem mover nada). Nunca lança exceção. */
    fun diagnose(): Diagnosis {
        return try {
            val layoutOk = ensureLayout() != null
            val files = inventory()
            val plan = MigrationPlanner.plan(files.map { FolderFile(it.relPath, it.node.sizeBytes) })
            val audio = files.filter { MigrationPlanner.isAudio(it.node.name) }
            val inSongs = audio.count { it.relPath.substringBefore('/').equals(MigrationPlanner.SONGS_DIR, ignoreCase = true) }
            Diagnosis(
                layoutOk = layoutOk,
                totalFiles = files.size,
                audioFiles = audio.size,
                audioInSongs = inSongs,
                audioOutside = plan.count { it.kind == PlannedMove.Kind.SONG },
                playlistsOutside = plan.count { it.kind == PlannedMove.Kind.PLAYLIST },
                plan = plan,
            )
        } catch (error: Throwable) {
            Diagnosis(false, 0, 0, 0, 0, 0, emptyList(), error.message ?: error::class.java.simpleName)
        }
    }

    /** Calcula o que seria movido, sem alterar nada. */
    fun preview(): Preview {
        val plan = MigrationPlanner.plan(inventory().map { FolderFile(it.relPath, it.node.sizeBytes) })
        return Preview(
            songs = plan.count { it.kind == PlannedMove.Kind.SONG },
            playlists = plan.count { it.kind == PlannedMove.Kind.PLAYLIST },
            plan = plan,
        )
    }

    /**
     * @param beforeMove chamado com o plano completo antes de mover qualquer arquivo; é onde o app
     *        grava as playlists internas já com os caminhos finais. Se lançar exceção, nada é movido.
     */
    fun run(beforeMove: (List<PlannedMove>) -> Unit, onProgress: (Progress) -> Unit): Report {
        val layout = ensureLayout() ?: error("Não consegui criar as pastas songs e playlists.")
        val files = inventory()
        val sizes = files.associate { it.relPath.lowercase() to it.node.sizeBytes }
        val byPath = files.associateBy { it.relPath.lowercase() }
        val plan = MigrationPlanner.plan(files.map { FolderFile(it.relPath, it.node.sizeBytes) })

        val startedAt = timestamp()
        val logName = "migracao-" + startedAt.replace(':', '-').replace(' ', '_') + ".json"
        val items = plan.map { LogItem(it.from, it.to, it.kind.name) }.toMutableList()
        writeLog(layout, logName, MigrationLog(startedAt, items = items))

        beforeMove(plan)

        val songTargets = plan.filter { it.kind == PlannedMove.Kind.SONG }
            .associate { TrackKeys.compareKey(it.from) to it.to }
        val rootRel = tree.rootRelativePath()
        val failures = ArrayList<Failure>()
        val movedTargets = ArrayList<String>()
        var songs = 0
        var playlists = 0
        val totalSongs = plan.count { it.kind == PlannedMove.Kind.SONG }
        val totalPlaylists = plan.size - totalSongs
        var seenSongs = 0
        var seenPlaylists = 0
        plan.forEachIndexed { index, move ->
            val isSong = move.kind == PlannedMove.Kind.SONG
            if (isSong) seenSongs++ else seenPlaylists++
            onProgress(
                Progress(
                    kind = move.kind,
                    name = move.from.substringAfterLast('/').substringBeforeLast('.'),
                    index = if (isSong) seenSongs else seenPlaylists,
                    total = if (isSong) totalSongs else totalPlaylists,
                    overallDone = index,
                    overallTotal = plan.size,
                ),
            )
            val source = byPath[move.from.lowercase()]
            val (movedDocId, error) =
                if (source == null) null to "não achei o arquivo" else moveOne(source, move, index)
            if (error == null) {
                if (move.kind == PlannedMove.Kind.PLAYLIST && movedDocId != null && source != null) {
                    rewritePlaylist(movedDocId, move, source, songTargets, rootRel, layout)
                }
                items[index] = items[index].copy(status = "movido")
                movedTargets += move.to
                if (move.kind == PlannedMove.Kind.SONG) songs++ else playlists++
            } else {
                items[index] = items[index].copy(status = "falhou: $error")
                failures += Failure(move.from, error)
            }
        }

        // Confere o resultado: cada destino precisa existir com o mesmo tamanho do original.
        val after = tree.walk(setOf(".git")).associateBy { it.relPath.lowercase() }
        plan.forEachIndexed { index, move ->
            if (items[index].status != "movido") return@forEachIndexed
            val moved = after[move.to.lowercase()]
            val expected = sizes[move.from.lowercase()]
            if (moved == null || (expected != null && expected > 0 && moved.node.sizeBytes != expected)) {
                items[index] = items[index].copy(status = "conferir: arquivo ausente ou com tamanho diferente")
                failures += Failure(move.to, "o arquivo movido não bate com o original")
            }
        }

        writeLog(layout, logName, MigrationLog(startedAt, timestamp(), items))
        scanMoved(movedTargets)
        return Report(songs, playlists, failures, logName)
    }

    private fun inventory(): List<SafTree.Entry> = tree.walk(setOf(".git"))

    /** Devolve (novo docId, null) em caso de sucesso, ou (null, motivo) em caso de falha. */
    private fun moveOne(source: SafTree.Entry, move: PlannedMove, index: Int): Pair<String?, String?> {
        val targetDir = move.to.substringBeforeLast('/', "")
        val destParent = tree.ensureDirPath(targetDir) ?: return null to "não consegui criar a pasta $targetDir"
        // Algo novo pode ter aparecido no destino depois do plano (ex.: playlist exportada pelo app).
        val targetName = freeName(destParent, move.to.substringAfterLast('/'))

        if (source.node.name == targetName) {
            val moved = tree.move(source.node.docId, source.parentDocId, destParent)
            return if (moved != null) moved to null else null to "o Android não deixou mover"
        }

        // Nome mudou por colisão: passa por uma pasta temporária para não conflitar em nenhum dos lados.
        val staging = tree.ensureDirPath("${MigrationPlanner.META_DIR}/tmp/$index") ?: return null to "não consegui criar a pasta temporária"
        val stagedId = tree.move(source.node.docId, source.parentDocId, staging)
            ?: return null to "o Android não deixou mover"
        val finalId = tree.rename(stagedId, targetName) ?: stagedId
        val moved = tree.move(finalId, staging, destParent)
            ?: return null to "não terminou de mover, o arquivo está em ${MigrationPlanner.META_DIR}/tmp/$index"
        tree.deleteEmptyDir(staging)
        return moved to null
    }

    private fun freeName(parentDocId: String, name: String): String {
        if (tree.child(parentDocId, name) == null) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (tree.child(parentDocId, "$base ($n)$ext") != null) n++
        return "$base ($n)$ext"
    }

    /**
     * O .m3u mudou de pasta, então seus caminhos relativos deixaram de valer. Guarda o original em
     * `.trueplayer/backup/` e reescreve o arquivo apontando para os novos caminhos das músicas.
     */
    private fun rewritePlaylist(
        docId: String,
        move: PlannedMove,
        source: SafTree.Entry,
        songTargets: Map<String, String>,
        rootRel: String,
        layout: Layout,
    ) {
        val original = tree.readText(docId) ?: return
        val backupDir = tree.ensureDir(layout.metaDocId, "backup") ?: return
        tree.writeTextFile(backupDir, "original-" + move.from.replace('/', '_'), original, "audio/x-mpegurl")
        val oldDir = move.from.substringBeforeLast('/', "")
        val rewritten = M3u.remapKeys(M3u.parse(original, playlistDir = oldDir, rootRelativePath = rootRel), songTargets)
        if (rewritten.isNotEmpty()) tree.writeText(docId, M3u.write(rewritten))
    }

    private fun writeLog(layout: Layout, name: String, log: MigrationLog) {
        tree.writeTextFile(layout.metaDocId, name, json.encodeToString(log), "application/json")
    }

    private fun scanMoved(relPaths: List<String>) {
        val paths = relPaths.mapNotNull { tree.absolutePath(it) }.toTypedArray()
        if (paths.isEmpty()) return
        runCatching { MediaScannerConnection.scanFile(appContext, paths, null, null) }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
}
