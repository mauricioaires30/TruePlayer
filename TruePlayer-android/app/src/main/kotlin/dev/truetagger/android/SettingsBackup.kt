package dev.truetagger.android

import android.content.Context
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Cópia das configurações em `.trueplayer/settings.json`, para voltarem sozinhas depois de
 * reinstalar ou trocar de celular. Segredos (como o token do GitHub) nunca vão para a pasta.
 */
object SettingsBackup {
    private const val FILE = "settings.json"
    private const val SCHEMA = 1

    @Serializable
    private data class Dto(
        val schemaVersion: Int = SCHEMA,
        val autoRefreshLibrary: Boolean = true,
        val embedYoutubeThumbnail: Boolean = true,
        val autoUpdateDownloader: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun scheduleWrite(context: Context, settings: AppSettings) {
        val app = context.applicationContext
        scope.launch { runCatching { write(app, settings) } }
    }

    fun write(context: Context, settings: AppSettings) {
        val tree = MusicFolder.tree(context) ?: return
        val metaDir = tree.ensureDir(tree.rootDocId, MigrationPlanner.META_DIR) ?: return
        val dto = Dto(
            autoRefreshLibrary = settings.autoRefreshLibrary,
            embedYoutubeThumbnail = settings.embedYoutubeThumbnail,
            autoUpdateDownloader = settings.autoUpdateDownloader,
        )
        tree.writeTextFile(metaDir, FILE, json.encodeToString(dto), "application/json")
    }

    /** Lê as configurações da pasta. Devolve null se não há arquivo, se ele é inválido ou de uma versão mais nova. */
    fun read(context: Context): AppSettings? {
        val tree = MusicFolder.tree(context) ?: return null
        val metaDir = tree.child(tree.rootDocId, MigrationPlanner.META_DIR)?.docId ?: return null
        val text = tree.child(metaDir, FILE)?.let { tree.readText(it.docId) } ?: return null
        val dto = runCatching { json.decodeFromString<Dto>(text) }.getOrNull() ?: return null
        if (dto.schemaVersion > SCHEMA) return null
        return AppSettings(
            autoRefreshLibrary = dto.autoRefreshLibrary,
            embedYoutubeThumbnail = dto.embedYoutubeThumbnail,
            autoUpdateDownloader = dto.autoUpdateDownloader,
        )
    }

    fun exists(context: Context): Boolean {
        val tree = MusicFolder.tree(context) ?: return false
        val metaDir = tree.child(tree.rootDocId, MigrationPlanner.META_DIR)?.docId ?: return false
        return tree.child(metaDir, FILE) != null
    }
}
