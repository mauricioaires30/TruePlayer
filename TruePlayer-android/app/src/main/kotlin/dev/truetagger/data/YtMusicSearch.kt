package dev.truetagger.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Busca de músicas no YouTube Music pela mesma API que o site usa: uma requisição HTTP que já traz título, artistas,
 * álbum e duração. Substitui abrir o yt-dlp (um processo Python) para ler três vídeos só para conferir a duração.
 */
object YtMusicSearch {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val ENDPOINT = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"

    /** Filtro "Músicas" da busca do YouTube Music: só faixas, sem vídeos, álbuns ou artistas. */
    private const val SONGS_FILTER = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    private const val CLIENT_VERSION = "1.20250101.01.00"

    private val DURATION = Regex("""^\d{1,2}(?::\d{2}){1,2}$""")

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.array() = this as? JsonArray
    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    /** Corpo do POST para buscar [query]. */
    fun requestBody(query: String): String = JsonObject(
        mapOf(
            "context" to JsonObject(
                mapOf(
                    "client" to JsonObject(
                        mapOf(
                            "clientName" to JsonPrimitive("WEB_REMIX"),
                            "clientVersion" to JsonPrimitive(CLIENT_VERSION),
                            "hl" to JsonPrimitive("en"),
                            "gl" to JsonPrimitive("US"),
                        ),
                    ),
                ),
            ),
            "query" to JsonPrimitive(query),
            "params" to JsonPrimitive(SONGS_FILTER),
        ),
    ).toString()

    /** "3:22" -> 202; "1:02:03" -> 3723; qualquer outra coisa -> 0. */
    fun parseDuration(text: String): Int {
        val value = text.trim()
        if (!DURATION.matches(value)) return 0
        return value.split(':').fold(0) { total, part -> total * 60 + part.toInt() }
    }

    private fun items(element: JsonElement?, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                element["musicResponsiveListItemRenderer"].obj()?.let { out += it }
                element.values.forEach { items(it, out) }
            }
            is JsonArray -> element.forEach { items(it, out) }
            else -> Unit
        }
    }

    private fun runsOf(item: JsonObject, column: Int): List<JsonObject> =
        item["flexColumns"].array()?.getOrNull(column).obj()
            ?.get("musicResponsiveListItemFlexColumnRenderer").obj()
            ?.get("text").obj()?.get("runs").array().orEmpty().mapNotNull { it.obj() }

    private fun pageType(run: JsonObject): String =
        run["navigationEndpoint"].obj()?.get("browseEndpoint").obj()
            ?.get("browseEndpointContextSupportedConfigs").obj()
            ?.get("browseEndpointContextMusicConfig").obj()
            ?.get("pageType").text()

    private fun videoIdOf(item: JsonObject): String =
        item["playlistItemData"].obj()?.get("videoId").text().ifBlank {
            runsOf(item, 0).firstNotNullOfOrNull { it["navigationEndpoint"].obj()?.get("watchEndpoint").obj()?.get("videoId").text().ifBlank { null } }.orEmpty()
        }

    private fun isSeparator(text: String) = text.isBlank() || text.trim() == "•" || text.trim() == "·" || text.trim() == "•"

    /** Resultados da busca, na ordem do site. Resposta vazia ou em outro formato devolve lista vazia, nunca erro. */
    fun parse(text: String): List<SearchHit> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val found = ArrayList<JsonObject>()
        items(root, found)
        return found.mapNotNull { item ->
            val id = videoIdOf(item)
            val title = runsOf(item, 0).joinToString("") { it["text"].text() }.trim()
            if (id.isBlank() || title.isBlank()) return@mapNotNull null

            val details = runsOf(item, 1)
            val artists = details.filter { pageType(it) in ARTIST_PAGES }.map { it["text"].text().trim() }.filter { it.isNotEmpty() }
            val duration = details.map { it["text"].text() }.lastOrNull { parseDuration(it) > 0 }.orEmpty()
            // Sem links de artista (resultados de upload de usuário): o primeiro trecho que não é separador, duração nem tipo.
            val artist = artists.joinToString(", ").ifBlank {
                details.map { it["text"].text().trim() }
                    .firstOrNull { !isSeparator(it) && parseDuration(it) == 0 && it !in KIND_LABELS }.orEmpty()
            }
            SearchHit(
                url = "https://music.youtube.com/watch?v=$id",
                title = title,
                durationSec = parseDuration(duration),
                uploader = artist,
                artist = artist,
            )
        }
    }

    private val ARTIST_PAGES = setOf("MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL")
    private val KIND_LABELS = setOf("Song", "Video", "Single", "EP", "Album")
}
