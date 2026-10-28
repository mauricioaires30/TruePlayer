package dev.truetagger.android

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.truetagger.data.CoverNames
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.model.AudioTrack
import java.io.File

/**
 * Salva a capa de cada álbum como imagem em `.trueplayer/covers/`, para ir junto no Git. É a capa
 * original que está dentro do arquivo de música (sem recomprimir). As capas continuam também dentro
 * das músicas; isto só deixa uma cópia solta e visível no GitHub.
 */
internal object CoverExport {
    const val DIR = "covers"

    /** [written] capas novas gravadas agora; [missing] capas que ainda faltam (limite por vez); [total] capas distintas. */
    data class Result(val written: Int, val missing: Int, val total: Int)

    /**
     * Grava só o que ainda não existe, no máximo [limit] por vez, para o commit não demorar demais.
     * Nunca apaga nem substitui uma capa que já está na pasta.
     */
    fun export(context: Context, dir: File, tracks: List<AudioTrack>, limit: Int = 300, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val groups = CoverNames.firstTrackPerCover(tracks)
        if (groups.isEmpty()) return Result(0, 0, 0)
        val coversDir = File(dir, "${MigrationPlanner.META_DIR}/$DIR")
        val existing = coversDir.list()?.map { it.substringBeforeLast('.') }?.toSet().orEmpty()
        // Álbuns que já foram tentados e não têm capa dentro dos arquivos: não adianta tentar de novo a cada commit.
        val prefs = context.applicationContext.getSharedPreferences("truetagger_covers", Context.MODE_PRIVATE)
        val withoutCover = prefs.getStringSet("none", emptySet()).orEmpty().toMutableSet()
        val pending = groups.filterKeys { it !in existing && it !in withoutCover }.entries.toList()
        val batch = pending.take(limit)
        var written = 0
        batch.forEachIndexed { index, (key, group) ->
            onProgress(index, batch.size)
            val bytes = group.firstNotNullOfOrNull { track -> embeddedPicture(context, track) }
            if (bytes == null) {
                withoutCover += key
                return@forEachIndexed
            }
            coversDir.mkdirs()
            val target = File(coversDir, "$key.${CoverNames.extensionFor(bytes)}")
            if (!target.exists()) {
                target.writeBytes(bytes)
                written++
            }
        }
        onProgress(batch.size, batch.size)
        prefs.edit().putStringSet("none", withoutCover).apply()
        // "Faltando" são só as que o limite deixou para o próximo commit.
        return Result(written, (pending.size - batch.size).coerceAtLeast(0), groups.size)
    }

    private fun embeddedPicture(context: Context, track: AudioTrack): ByteArray? =
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, Uri.parse(track.id))
                retriever.embeddedPicture
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
}
