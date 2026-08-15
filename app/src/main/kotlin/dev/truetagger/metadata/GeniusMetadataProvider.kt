package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GeniusMetadataProvider(
    private val client: HttpClient,
    private val accessToken: String,
) : MetadataProvider {
    override val name: String = "Genius"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String): List<MetadataResult> {
        if (query.isBlank() || accessToken.isBlank()) return emptyList()
        val text = client.get("https://api.genius.com/search") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("q", query)
        }.bodyAsText()
        val response = json.decodeFromString<GeniusSearchResponse>(text)
        return response.response.hits.map { hit ->
            val song = hit.result
            MetadataResult(
                source = name,
                sourceId = song.id.toString(),
                title = song.title,
                artist = song.primaryArtist.name,
                album = song.album?.name.orEmpty(),
                albumArtist = song.primaryArtist.name,
                releaseDate = song.releaseDate.orEmpty(),
                artworkUrl = song.songArtImageUrl.orEmpty(),
            )
        }
    }
}

@Serializable
private data class GeniusSearchResponse(val response: GeniusResponse = GeniusResponse())

@Serializable
private data class GeniusResponse(val hits: List<GeniusHit> = emptyList())

@Serializable
private data class GeniusHit(val result: GeniusSong)

@Serializable
private data class GeniusSong(
    val id: Long,
    val title: String,
    val primary_artist: GeniusArtist,
    val song_art_image_url: String? = null,
    val release_date_for_display: String? = null,
    val album: GeniusAlbum? = null,
) {
    val primaryArtist: GeniusArtist get() = primary_artist
    val songArtImageUrl: String? get() = song_art_image_url
    val releaseDate: String? get() = release_date_for_display?.let { value ->
        Regex("\\b(?:19|20)\\d{2}\\b").find(value)?.value ?: value.takeIf { it.take(4).all(Char::isDigit) }?.take(4)
    }
}

@Serializable
private data class GeniusArtist(val name: String)

@Serializable
private data class GeniusAlbum(val name: String = "")
