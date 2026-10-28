package dev.truetagger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Preferências do app que fazem sentido em qualquer celular e vão para `.trueplayer/settings.json`
 * (e daí para o Git). Segredos, caminhos do aparelho e posição na tela nunca entram aqui.
 */
@Serializable
data class AppConfigFile(
    val schemaVersion: Int = AppConfigCodec.SCHEMA,
    val autoRefreshLibrary: Boolean = true,
    val embedYoutubeThumbnail: Boolean = true,
    val autoUpdateDownloader: Boolean = true,
    val autoTag: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val volumeNormalization: Boolean = true,
    val volumeOutputLevel: Float = 1f,
    val librarySort: String = "",
    val libraryAscending: Boolean = true,
    val albumSort: String = "",
    val albumAscending: Boolean = true,
)

object AppConfigCodec {
    const val SCHEMA = 2

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun encode(file: AppConfigFile): String = json.encodeToString(file)

    /** Devolve null se o texto é inválido ou de uma versão mais nova do app (para não reescrever por cima). */
    fun decode(text: String): AppConfigFile? {
        val file = runCatching { json.decodeFromString<AppConfigFile>(text) }.getOrNull() ?: return null
        return file.takeIf { it.schemaVersion <= SCHEMA }
    }
}
