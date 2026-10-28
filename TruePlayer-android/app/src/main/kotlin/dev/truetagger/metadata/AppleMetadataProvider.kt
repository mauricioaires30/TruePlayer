package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppleMetadataProvider(
    private val client: HttpClient,
) : MetadataProvider {
    override val name: String = "Apple"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun search(query: String): List<MetadataResult> {
        if (query.isBlank()) return emptyList()

        val text = client.get("https://itunes.apple.com/search") {
            parameter("term", query)
            parameter("media", "music")
            parameter("entity", "song")
            parameter("country", "BR")
            parameter("limit", 30)
        }.bodyAsText()
        val response = json.decodeFromString<AppleSearchResponse>(text)

        return response.results.mapNotNull { item ->
            val title = item.trackName ?: return@mapNotNull null
            val artist = item.artistName ?: return@mapNotNull null
            MetadataResult(
                source = name,
                sourceId = item.trackId?.toString().orEmpty(),
                title = title,
                artist = artist,
                album = item.collectionName.orEmpty(),
                albumArtist = item.collectionArtistName ?: artist,
                trackNumber = item.trackNumber,
                trackTotal = item.trackCount,
                discNumber = item.discNumber,
                discTotal = item.discCount,
                releaseDate = item.releaseDate.orEmpty(),
                genre = item.primaryGenreName.orEmpty(),
                artworkUrl = item.artworkUrl100
                    ?.replace("100x100bb", "600x600bb")
                    .orEmpty(),
                durationSec = ((item.trackTimeMillis ?: 0L) / 1000L).toInt(),
            )
        }
    }
}

@Serializable
private data class AppleSearchResponse(
    val resultCount: Int = 0,
    val results: List<AppleTrack> = emptyList(),
)

@Serializable
private data class AppleTrack(
    val trackId: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val collectionName: String? = null,
    val collectionArtistName: String? = null,
    val trackNumber: Int? = null,
    val trackCount: Int? = null,
    val discNumber: Int? = null,
    val discCount: Int? = null,
    val releaseDate: String? = null,
    val primaryGenreName: String? = null,
    val artworkUrl100: String? = null,
    val trackTimeMillis: Long? = null,
)
