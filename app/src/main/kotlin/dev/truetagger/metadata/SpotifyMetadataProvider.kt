package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

class SpotifyMetadataProvider(
    private val client: HttpClient,
    private val clientId: String,
    private val clientSecret: String,
) : MetadataProvider {
    override val name: String = "Spotify"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var cachedToken: String = ""
    private var tokenExpiresAtMs: Long = 0L

    override suspend fun search(query: String): List<MetadataResult> {
        if (query.isBlank() || clientId.isBlank() || clientSecret.isBlank()) return emptyList()
        val token = accessToken()
        if (token.isBlank()) return emptyList()

        val text = client.get("https://api.spotify.com/v1/search") {
            header(HttpHeaders.Authorization, "Bearer $token")
            parameter("q", query)
            parameter("type", "track")
            parameter("limit", 25)
            parameter("market", "BR")
        }.bodyAsText()
        val response = json.decodeFromString<SpotifySearchResponse>(text)
        return response.tracks?.items.orEmpty().map { item ->
            val artist = item.artists.firstOrNull()?.name.orEmpty()
            MetadataResult(
                source = name,
                sourceId = item.id,
                title = item.name,
                artist = artist,
                album = item.album.name,
                albumArtist = item.album.artists.firstOrNull()?.name ?: artist,
                trackNumber = item.trackNumber,
                trackTotal = item.album.totalTracks,
                discNumber = item.discNumber,
                discTotal = null,
                releaseDate = item.album.releaseDate,
                isrc = item.externalIds?.isrc.orEmpty(),
                artworkUrl = item.album.images.maxByOrNull { it.width ?: 0 }?.url.orEmpty(),
            )
        }
    }

    private suspend fun accessToken(): String {
        val now = System.currentTimeMillis()
        if (cachedToken.isNotBlank() && now < tokenExpiresAtMs - 30_000L) return cachedToken
        val basic = Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())
        val text = client.post("https://accounts.spotify.com/api/token") {
            header(HttpHeaders.Authorization, "Basic $basic")
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("grant_type=client_credentials")
        }.bodyAsText()
        val response = json.decodeFromString<SpotifyTokenResponse>(text)
        cachedToken = response.accessToken
        tokenExpiresAtMs = now + response.expiresIn * 1000L
        return cachedToken
    }
}

@Serializable
private data class SpotifyTokenResponse(
    val access_token: String = "",
    val expires_in: Long = 3600L,
) {
    val accessToken: String get() = access_token
    val expiresIn: Long get() = expires_in
}

@Serializable
private data class SpotifySearchResponse(val tracks: SpotifyTracks? = null)

@Serializable
private data class SpotifyTracks(val items: List<SpotifyTrack> = emptyList())

@Serializable
private data class SpotifyTrack(
    val id: String,
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbum,
    val track_number: Int? = null,
    val disc_number: Int? = null,
    val external_ids: SpotifyExternalIds? = null,
) {
    val trackNumber: Int? get() = track_number
    val discNumber: Int? get() = disc_number
    val externalIds: SpotifyExternalIds? get() = external_ids
}

@Serializable
private data class SpotifyArtist(val name: String)

@Serializable
private data class SpotifyAlbum(
    val name: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val total_tracks: Int? = null,
    val release_date: String = "",
    val images: List<SpotifyImage> = emptyList(),
) {
    val totalTracks: Int? get() = total_tracks
    val releaseDate: String get() = release_date
}

@Serializable
private data class SpotifyImage(val url: String, val width: Int? = null)

@Serializable
private data class SpotifyExternalIds(val isrc: String? = null)
