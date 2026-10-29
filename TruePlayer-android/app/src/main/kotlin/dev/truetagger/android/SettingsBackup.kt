package dev.truetagger.android

import android.content.Context
import dev.truetagger.data.AppConfigCodec
import dev.truetagger.data.AppConfigFile
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.ThemeMode
import dev.truetagger.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cópia das configurações em `.trueplayer/settings.json`, para voltarem sozinhas depois de
 * reinstalar ou trocar de celular (e para irem junto no Git). Segredos (como o token do GitHub)
 * nunca vão para a pasta.
 */
object SettingsBackup {
    private const val FILE = "settings.json"
    private const val FILTER_PREFS = "truetagger_filters"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleWrite(context: Context, settings: AppSettings) {
        val app = context.applicationContext
        scope.launch { runCatching { write(app, settings) } }
    }

    /** As opções como estão guardadas no aparelho agora. */
    fun settingsFromPrefs(context: Context): AppSettings {
        val prefs = context.applicationContext.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
        return AppSettings(
            autoRefreshLibrary = prefs.getBoolean(ShareContract.PREF_AUTO_REFRESH_LIBRARY, true),
            embedYoutubeThumbnail = prefs.getBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, true),
            autoUpdateDownloader = prefs.getBoolean(ShareContract.PREF_AUTO_UPDATE_DOWNLOADER, true),
            autoTag = prefs.getBoolean(ShareContract.PREF_AUTO_TAG, true),
            autoCheckUpdates = prefs.getBoolean(ShareContract.PREF_AUTO_CHECK_UPDATES, true),
            themeMode = ThemeMode.fromKey(prefs.getString(ShareContract.PREF_THEME_MODE, null)),
        )
    }

    private fun snapshot(context: Context, settings: AppSettings): AppConfigFile {
        val filters = context.getSharedPreferences(FILTER_PREFS, Context.MODE_PRIVATE)
        return AppConfigFile(
            autoRefreshLibrary = settings.autoRefreshLibrary,
            embedYoutubeThumbnail = settings.embedYoutubeThumbnail,
            autoUpdateDownloader = settings.autoUpdateDownloader,
            autoTag = settings.autoTag,
            autoCheckUpdates = settings.autoCheckUpdates,
            volumeNormalization = VolumeNormalizer.isEnabled(context),
            volumeOutputLevel = VolumeNormalizer.getOutputLevel(context),
            librarySort = filters.getString("library_sort", "").orEmpty(),
            libraryAscending = filters.getBoolean("library_ascending", true),
            albumSort = filters.getString("album_sort", "").orEmpty(),
            albumAscending = filters.getBoolean("album_ascending", true),
            themeMode = settings.themeMode.key,
        )
    }

    /** Grava o arquivo. Se o conteúdo é igual ao que já está lá, não mexe (evita mudança à toa no Git). */
    fun write(context: Context, settings: AppSettings) {
        val app = context.applicationContext
        val tree = MusicFolder.tree(app) ?: return
        val metaDir = tree.ensureDir(tree.rootDocId, MigrationPlanner.META_DIR) ?: return
        val text = AppConfigCodec.encode(snapshot(app, settings))
        val existing = tree.child(metaDir, FILE)?.let { tree.readText(it.docId) }
        if (existing == text) return
        // Arquivo de uma versão mais nova do app: não reescreve por cima.
        if (existing != null && AppConfigCodec.decode(existing) == null && existing.contains("schemaVersion")) return
        tree.writeTextFile(metaDir, FILE, text, "application/json")
    }

    /** Grava agora com o que está guardado no aparelho (usado antes de um commit do Git). */
    fun writeCurrent(context: Context) = write(context, settingsFromPrefs(context))

    /**
     * Lê as configurações da pasta e devolve as opções do app. Volume e ordenação são aplicados
     * direto nas preferências. Devolve null se não há arquivo, se ele é inválido ou de versão mais nova.
     */
    fun read(context: Context): AppSettings? {
        val app = context.applicationContext
        val tree = MusicFolder.tree(app) ?: return null
        val metaDir = tree.child(tree.rootDocId, MigrationPlanner.META_DIR)?.docId ?: return null
        val text = tree.child(metaDir, FILE)?.let { tree.readText(it.docId) } ?: return null
        val file = AppConfigCodec.decode(text) ?: return null
        VolumeNormalizer.setEnabled(app, file.volumeNormalization)
        VolumeNormalizer.setOutputLevel(app, file.volumeOutputLevel)
        val filters = app.getSharedPreferences(FILTER_PREFS, Context.MODE_PRIVATE).edit()
        if (file.librarySort.isNotBlank()) filters.putString("library_sort", file.librarySort).putBoolean("library_ascending", file.libraryAscending)
        if (file.albumSort.isNotBlank()) filters.putString("album_sort", file.albumSort).putBoolean("album_ascending", file.albumAscending)
        filters.apply()
        return AppSettings(
            autoRefreshLibrary = file.autoRefreshLibrary,
            embedYoutubeThumbnail = file.embedYoutubeThumbnail,
            autoUpdateDownloader = file.autoUpdateDownloader,
            autoTag = file.autoTag,
            autoCheckUpdates = file.autoCheckUpdates,
            themeMode = ThemeMode.fromKey(file.themeMode),
        )
    }

    fun exists(context: Context): Boolean {
        val tree = MusicFolder.tree(context) ?: return false
        val metaDir = tree.child(tree.rootDocId, MigrationPlanner.META_DIR)?.docId ?: return false
        return tree.child(metaDir, FILE) != null
    }
}
