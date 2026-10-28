package dev.truetagger.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

data class FlatEntry(val url: String, val title: String)

/** Conteúdo de uma playlist, na ordem original. */
data class FlatPlaylist(val title: String, val entries: List<FlatEntry>)

/** O que o yt-dlp sabe sobre uma faixa/vídeo. Todos os campos podem vir vazios. */
data class SourceInfo(
    val title: String = "",
    val uploader: String = "",
    val uploadDate: String = "",
    val thumbnail: String = "",
    /** Preenchidos pelo YouTube Music / SoundCloud quando a faixa tem metadados de verdade. */
    val artist: String = "",
    val track: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val extractor: String = "",
    val releaseYear: String = "",
) {
    /** Primeiro artista da lista ("A, B" -> "A"). */
    val mainArtist: String get() = artist.substringBefore(',').trim()
    val hasRealMetadata: Boolean get() = artist.isNotBlank() && track.isNotBlank()
}

/** Leitura (tolerante) da saída JSON do yt-dlp. */
object YtDlpJson {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun parseObject(text: String): JsonObject? {
        val candidates = listOf(text.trim()) + text.lines().map { it.trim() }.filter { it.startsWith("{") }.reversed()
        for (candidate in candidates) {
            val parsed = runCatching { json.parseToJsonElement(candidate) }.getOrNull()
            if (parsed is JsonObject) return parsed
        }
        return null
    }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    /** Devolve null se o link não é uma playlist (ou ela está vazia). */
    fun parseListing(text: String): FlatPlaylist? {
        val obj = parseObject(text) ?: return null
        val entries = (obj["entries"] as? JsonArray) ?: return null
        val items = entries.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry["id"].text()
            val url = entry["url"].text().ifBlank { entry["webpage_url"].text() }
                .ifBlank { if (id.isNotBlank() && entry["ie_key"].text().equals("Youtube", true)) "https://www.youtube.com/watch?v=$id" else "" }
            if (url.isBlank()) null else FlatEntry(url, entry["title"].text())
        }
        if (items.isEmpty()) return null
        val title = obj["title"].text().ifBlank { obj["playlist_title"].text() }.ifBlank { "Playlist" }
        return FlatPlaylist(title, items)
    }

    fun parseInfo(text: String): SourceInfo? {
        val obj = parseObject(text) ?: return null
        val duration = (obj["duration"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        return SourceInfo(
            title = obj["title"].text().ifBlank { obj["fulltitle"].text() },
            uploader = obj["uploader"].text().ifBlank { obj["channel"].text() }.ifBlank { obj["creator"].text() },
            uploadDate = obj["upload_date"].text(),
            thumbnail = obj["thumbnail"].text(),
            artist = obj["artist"].text().ifBlank {
                (obj["artists"] as? JsonArray)?.mapNotNull { it.text().ifBlank { null } }?.joinToString(", ").orEmpty()
            },
            track = obj["track"].text(),
            album = obj["album"].text(),
            durationSec = duration,
            extractor = obj["extractor_key"].text().ifBlank { obj["extractor"].text() },
            releaseYear = obj["release_year"].text().ifBlank { obj["release_date"].text().take(4) },
        )
    }
}
