package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DeezerMetadataProvider(
    private val client: HttpClient,
) : MetadataProvider {
    override val name: String = "Deezer"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String): List<MetadataResult> {
        if (query.isBlank()) return emptyList()

        val text = client.get("https://api.deezer.com/search") {
            parameter("q", query)
            parameter("limit", 30)
        }.bodyAsText()
        val response = json.decodeFromString<DeezerSearchResponse>(text)

        return response.data.map { item ->
            MetadataResult(
                source = name,
                sourceId = item.id.toString(),
                title = item.title,
                artist = item.artist.name,
                album = item.album.title,
                albumArtist = item.artist.name,
                trackNumber = item.trackPosition,
                discNumber = item.diskNumber,
                artworkUrl = item.album.coverXl ?: item.album.coverBig.orEmpty(),
                durationSec = item.duration ?: 0,
            )
        }
    }
}

@Serializable
private data class DeezerSearchResponse(
    val data: List<DeezerTrack> = emptyList(),
)

@Serializable
private data class DeezerTrack(
    val id: Long,
    val title: String,
    val artist: DeezerArtist,
    val album: DeezerAlbum,
    val track_position: Int? = null,
    val disk_number: Int? = null,
    val duration: Int? = null,
) {
    val trackPosition: Int? get() = track_position
    val diskNumber: Int? get() = disk_number
}

@Serializable
private data class DeezerArtist(
    val name: String,
)

@Serializable
private data class DeezerAlbum(
    val title: String,
    val cover_big: String? = null,
    val cover_xl: String? = null,
) {
    val coverBig: String? get() = cover_big
    val coverXl: String? get() = cover_xl
}
