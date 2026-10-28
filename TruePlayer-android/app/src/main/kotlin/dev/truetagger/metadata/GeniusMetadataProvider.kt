package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Busca no Genius do mesmo jeito que o site faz quando alguém digita na caixa de pesquisa. Não usa
 * chave de API (a chave exige cadastro e e-mail para os donos do serviço).
 */
class GeniusMetadataProvider(private val client: HttpClient) : MetadataProvider {
    override val name: String = "Genius"

    override suspend fun search(query: String): List<MetadataResult> {
        if (query.isBlank()) return emptyList()
        val text = client.get("https://genius.com/api/search/multi") {
            parameter("q", query)
            header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            header(HttpHeaders.Accept, "application/json")
        }.bodyAsText()
        return GeniusParser.parse(text, name)
    }
}

/** Leitura da resposta da pesquisa do site do Genius. Qualquer formato inesperado vira lista vazia. */
object GeniusParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    fun parse(text: String, source: String = "Genius"): List<MetadataResult> {
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val sections = ((root["response"] as? JsonObject)?.get("sections") as? JsonArray) ?: return emptyList()
        return sections
            .mapNotNull { it as? JsonObject }
            .flatMap { section -> (section["hits"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it["type"].text() == "song" }
            .mapNotNull { hit ->
                val result = hit["result"] as? JsonObject ?: return@mapNotNull null
                val title = result["title"].text().ifBlank { return@mapNotNull null }
                val artist = ((result["primary_artist"] as? JsonObject)?.get("name")).text().ifBlank { return@mapNotNull null }
                MetadataResult(
                    source = source,
                    sourceId = result["id"].text(),
                    title = title,
                    artist = artist,
                    album = "",
                    albumArtist = artist,
                    releaseDate = yearOf(result["release_date_for_display"].text()),
                    artworkUrl = result["song_art_image_url"].text(),
                )
            }
            .distinctBy { it.sourceId.ifBlank { it.title + "|" + it.artist } }
    }

    private fun yearOf(display: String): String = Regex("\\b(?:19|20)\\d{2}\\b").find(display)?.value.orEmpty()
}
