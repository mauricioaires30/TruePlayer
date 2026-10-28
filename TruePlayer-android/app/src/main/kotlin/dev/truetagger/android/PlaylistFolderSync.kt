package dev.truetagger.android

import android.content.Context
import android.net.Uri
import dev.truetagger.data.M3u
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.PlaylistEntry
import dev.truetagger.model.TrackKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** Ponteiro para a pasta de músicas escolhida. */
object MusicFolder {
    /** Pasta escolhida, ou null se não há pasta ou se o Android não concede mais acesso a ela. */
    fun treeUri(context: Context): Uri? = savedUri(context)?.takeIf { hasPermission(context, it) }

    /** Pasta lembrada nas preferências, mesmo que a permissão tenha se perdido. */
    fun savedUri(context: Context): Uri? = context.applicationContext
        .getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
        .getString(ShareContract.PREF_DOWNLOAD_TREE, null)
        ?.let(Uri::parse)

    /**
     * Preferências restauradas por backup (ex.: troca de celular) trazem o endereço da pasta, mas o
     * Android não restaura a permissão: sem ela toda leitura/escrita na pasta dá "Permission Denial".
     */
    fun hasPermission(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }.getOrDefault(false)

    fun tree(context: Context): SafTree? = treeUri(context)?.let { runCatching { SafTree(context, it) }.getOrNull() }

    fun rootRelativePath(context: Context): String = tree(context)?.rootRelativePath().orEmpty()

    /** URI da pasta `playlists/`, para abrir o seletor de arquivos direto nela. */
    fun playlistsFolderUri(context: Context): Uri? {
        val tree = tree(context) ?: return null
        val docId = runCatching { tree.child(tree.rootDocId, MigrationPlanner.PLAYLISTS_DIR)?.docId }.getOrNull() ?: return null
        return tree.docUri(docId)
    }
}

/** Seletor de playlists (.m3u) que já abre em `playlists/` e aceita vários arquivos. */
class OpenPlaylistFiles : androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments() {
    var initialUri: Uri? = null

    override fun createIntent(context: Context, input: Array<String>): android.content.Intent =
        super.createIntent(context, input).also { intent ->
            initialUri?.let { intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

/**
 * Mantém `playlists/<nome>.m3u` na pasta de músicas em sincronia com as playlists do app.
 * A pasta é o que sobrevive a trocas de celular; as preferências locais são só cache.
 * Nada é apagado: playlists removidas vão para `_lixeira/playlists/`.
 */
internal object PlaylistFolderSync {
    private const val PREFS = "truetagger_playlist_sync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var exportJob: Job? = null

    fun scheduleExport(context: Context) {
        val app = context.applicationContext
        exportJob?.cancel()
        exportJob = scope.launch {
            delay(1_500)
            mutex.withLock { runCatching { exportNow(app) } }
        }
    }

    fun scheduleTrash(context: Context, name: String) {
        val app = context.applicationContext
        scope.launch {
            delay(2_000)
            mutex.withLock { runCatching { trashIfGone(app, name) } }
        }
    }

    /** Lê os .m3u da pasta e junta com as playlists locais. Chame depois de uma varredura confiável. */
    suspend fun importFromFolder(context: Context) {
        val app = context.applicationContext
        mutex.withLock { runCatching { importNow(app) } }
    }

    /** Para uso fora de corrotinas (ex.: dentro da migração, que já roda em thread de fundo). */
    fun exportBlocking(context: Context) {
        val app = context.applicationContext
        kotlinx.coroutines.runBlocking { mutex.withLock { runCatching { exportNow(app) } } }
    }

    suspend fun exportAllNow(context: Context) {
        val app = context.applicationContext
        mutex.withLock { runCatching { exportNow(app) } }
    }

    private fun fileName(playlistName: String): String =
        playlistName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "playlist" } + ".m3u"

    private fun hashOf(entries: List<PlaylistEntry>): Int =
        entries.joinToString("\n") { TrackKeys.compareKey(it.key).ifEmpty { it.key } }.hashCode()

    private fun lastHashes(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun exportNow(context: Context) {
        val tree = MusicFolder.tree(context) ?: return
        val playlistsDir = tree.ensureDir(tree.rootDocId, MigrationPlanner.PLAYLISTS_DIR) ?: return
        val prefs = lastHashes(context)
        val existing = tree.children(playlistsDir).associateBy { it.name.lowercase(Locale.ROOT) }
        val edit = prefs.edit()
        PlaylistStore(context).loadStored().forEach { playlist ->
            val name = fileName(playlist.name)
            val hash = hashOf(playlist.entries)
            val hashKey = "h:" + playlist.name.lowercase(Locale.ROOT)
            val upToDate = prefs.contains(hashKey) && prefs.getInt(hashKey, 0) == hash && existing.containsKey(name.lowercase(Locale.ROOT))
            if (upToDate) return@forEach
            val text = M3u.write(playlist.entries)
            if (tree.writeTextFile(playlistsDir, name, text, "audio/x-mpegurl") != null) edit.putInt(hashKey, hash)
        }
        edit.apply()
    }

    private fun importNow(context: Context) {
        val tree = MusicFolder.tree(context) ?: return
        val playlistsDir = tree.child(tree.rootDocId, MigrationPlanner.PLAYLISTS_DIR)?.docId ?: return
        val rootRel = tree.rootRelativePath()
        val store = PlaylistStore(context)
        val local = store.loadStored().associateBy { it.name.lowercase(Locale.ROOT) }
        val prefs = lastHashes(context)
        val edit = prefs.edit()

        val incoming = ArrayList<StoredPlaylist>()
        val replace = HashSet<String>()
        tree.children(playlistsDir)
            .filter { !it.isDir && (it.name.endsWith(".m3u", true) || it.name.endsWith(".m3u8", true)) }
            .forEach { file ->
                val text = tree.readText(file.docId) ?: return@forEach
                val entries = M3u.parse(text, rootRelativePath = rootRel)
                if (entries.isEmpty()) return@forEach
                val name = file.name.substringBeforeLast('.').trim().ifBlank { return@forEach }
                val fileHash = hashOf(entries)
                val hashKey = "h:" + name.lowercase(Locale.ROOT)
                val last = if (prefs.contains(hashKey)) prefs.getInt(hashKey, 0) else null
                val mine = local[name.lowercase(Locale.ROOT)]
                when {
                    mine == null -> {
                        incoming += StoredPlaylist(name, entries)
                        edit.putInt(hashKey, fileHash)
                    }
                    last == null || hashOf(mine.entries) != last -> {
                        // Os dois lados mudaram (ou não há histórico): une sem perder nada.
                        if (fileHash != hashOf(mine.entries)) {
                            val known = mine.entries.map { TrackKeys.compareKey(it.key) }.toHashSet()
                            val extras = entries.filter { TrackKeys.compareKey(it.key) !in known }
                            if (extras.isNotEmpty()) {
                                incoming += StoredPlaylist(mine.name, mine.entries + extras)
                                replace += mine.name
                            }
                        }
                    }
                    fileHash != last -> {
                        // Só o arquivo mudou (git pull, edição em outro aparelho): o arquivo vence.
                        incoming += StoredPlaylist(mine.name, entries)
                        replace += mine.name
                        edit.putInt(hashKey, fileHash)
                    }
                }
            }
        edit.apply()
        store.mergeFromFolder(incoming) { it in replace }
    }

    private fun trashIfGone(context: Context, name: String) {
        if (PlaylistStore(context).loadStored().any { it.name.equals(name, ignoreCase = true) }) return
        val tree = MusicFolder.tree(context) ?: return
        val playlistsDir = tree.child(tree.rootDocId, MigrationPlanner.PLAYLISTS_DIR)?.docId ?: return
        val file = tree.child(playlistsDir, fileName(name)) ?: return
        val trashDir = tree.ensureDirPath("${MigrationPlanner.TRASH_DIR}/playlists") ?: return
        var docId = file.docId
        if (tree.child(trashDir, file.name) != null) {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
            docId = tree.rename(docId, file.name.substringBeforeLast('.') + " ($stamp).m3u") ?: return
        }
        tree.move(docId, playlistsDir, trashDir)
        lastHashes(context).edit().remove("h:" + name.lowercase(Locale.ROOT)).apply()
    }
}
