package dev.truetagger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

enum class SpotifyKind { TRACK, ALBUM, PLAYLIST }

/** Tipo e id de um link do Spotify. */
data class SpotifyRef(val kind: SpotifyKind, val id: String) {
    /** Página de "player incorporado" do Spotify: pública, sem chave, e traz o nome e as músicas em JSON. */
    val embedUrl: String get() = "https://open.spotify.com/embed/${kind.name.lowercase()}/$id"
    val pageUrl: String get() = "https://open.spotify.com/${kind.name.lowercase()}/$id"
}

/**
 * O que o app precisa saber de uma música do Spotify para achá-la em outro lugar e marcar o arquivo.
 * [album], [albumArtist] e [trackNumber] só vêm quando a música foi pedida pelo link do álbum.
 */
@Serializable
data class TrackHint(
    val title: String,
    /** Todos os artistas, como o Spotify mostra ("A, B"). */
    val artistLine: String,
    val artists: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val album: String = "",
    val albumArtist: String = "",
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val year: String = "",
) {
    val durationSec: Int get() = (durationMs / 1000L).toInt()
    val mainArtist: String get() = artists.firstOrNull().orEmpty().ifBlank { artistLine.substringBefore(',').trim() }
}

/** Uma música, um álbum ou uma playlist do Spotify já lidos. [tracks] vem na ordem original. */
data class SpotifyCollection(val kind: SpotifyKind, val name: String, val tracks: List<TrackHint>, val ids: List<String>, val coverUrl: String = "")

object SpotifyLinks {
    private val HOSTS = setOf("open.spotify.com", "play.spotify.com")
    private val SHORT_HOSTS = setOf("spotify.link", "spotify.app.link")
    private val KINDS = mapOf("track" to SpotifyKind.TRACK, "album" to SpotifyKind.ALBUM, "playlist" to SpotifyKind.PLAYLIST)
    private val IN_TEXT = Regex("""open\.spotify\.com/(?:intl-[A-Za-z-]+/)?(track|album|playlist)/([A-Za-z0-9]{22})""")

    private fun host(url: String): String = runCatching { java.net.URI(url.trim()).host.orEmpty().lowercase() }.getOrDefault("")

    fun isSpotifyHost(url: String): Boolean = host(url) in HOSTS || host(url) in SHORT_HOSTS

    /** Links curtos (spotify.link) escondem o tipo: é preciso abrir para saber. */
    fun isShort(url: String): Boolean = host(url) in SHORT_HOSTS

    /** Link de música, álbum ou playlist (aceita /intl-xx/ e parâmetros como ?si=...). Null para o resto. */
    fun parse(url: String): SpotifyRef? {
        if (host(url) !in HOSTS) return null
        return IN_TEXT.find(url.trim())?.let { SpotifyRef(KINDS.getValue(it.groupValues[1]), it.groupValues[2]) }
    }

    /** Acha o primeiro link de música, álbum ou playlist dentro de um texto (página de um link curto). */
    fun findInText(text: String): SpotifyRef? =
        IN_TEXT.find(text)?.let { SpotifyRef(KINDS.getValue(it.groupValues[1]), it.groupValues[2]) }
}

/** Leitura da página `open.spotify.com/embed/...` (JSON em `__NEXT_DATA__`). */
object SpotifyEmbed {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** O embed devolve no máximo 100 músicas por playlist: com 100 lidas, a playlist pode ser maior. */
    const val PLAYLIST_LIMIT = 100

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    private fun JsonElement?.number(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L

    private fun clean(text: String) = text.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun splitArtists(line: String): List<String> = clean(line).split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** A maior imagem do álbum ou da playlist: a capa nas fontes, ou a maior da identidade visual. */
    private fun coverOf(entity: JsonObject): String {
        val sources = (entity["coverArt"].obj()?.get("sources") as? JsonArray).orEmpty().mapNotNull { it.obj() }
        val visual = (entity["visualIdentity"].obj()?.get("image") as? JsonArray).orEmpty().mapNotNull { it.obj() }
        return (sources + visual).filter { it["url"].text().startsWith("http") }
            .maxByOrNull { maxOf(it["width"].number(), it["maxWidth"].number()) }?.get("url").text()
    }

    fun parse(html: String): SpotifyCollection? {
        val raw = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull().obj() ?: return null
        val entity = root["props"].obj()?.get("pageProps").obj()?.get("state").obj()?.get("data").obj()?.get("entity").obj() ?: return null
        val kind = when (entity["type"].text().lowercase()) {
            "track" -> SpotifyKind.TRACK
            "album" -> SpotifyKind.ALBUM
            "playlist" -> SpotifyKind.PLAYLIST
            else -> return null
        }
        val name = clean(entity["name"].text().ifBlank { entity["title"].text() })

        if (kind == SpotifyKind.TRACK) {
            val artists = (entity["artists"] as? JsonArray).orEmpty().mapNotNull { clean(it.obj()?.get("name").text()).ifBlank { null } }
            val track = TrackHint(
                title = name,
                artistLine = artists.joinToString(", "),
                artists = artists,
                durationMs = entity["duration"].number(),
                year = entity["releaseDate"].obj()?.get("isoString").text().take(4),
            )
            return if (track.title.isBlank()) null else SpotifyCollection(kind, name, listOf(track), listOf(trackId(entity["uri"].text(), entity["id"].text())))
        }

        val list = entity["trackList"] as? JsonArray ?: return null
        val albumArtist = if (kind == SpotifyKind.ALBUM) clean(entity["subtitle"].text()) else ""
        val items = list.mapNotNull { it.obj() }.filter { it["entityType"].text().ifBlank { "track" } == "track" && it["title"].text().isNotBlank() }
        val tracks = items.mapIndexed { index, item ->
            val line = clean(item["subtitle"].text())
            TrackHint(
                title = clean(item["title"].text()),
                artistLine = line,
                artists = splitArtists(line),
                durationMs = item["duration"].number(),
                album = if (kind == SpotifyKind.ALBUM) name else "",
                albumArtist = albumArtist,
                trackNumber = if (kind == SpotifyKind.ALBUM) index + 1 else null,
                trackTotal = if (kind == SpotifyKind.ALBUM) items.size else null,
            )
        }
        if (tracks.isEmpty()) return null
        val ids = items.map { trackId(it["uri"].text(), "") }
        return SpotifyCollection(kind, name.ifBlank { if (kind == SpotifyKind.ALBUM) "Album" else "Playlist" }, tracks, ids, coverOf(entity))
    }

    /** "spotify:track:ID" -> "ID". */
    private fun trackId(uri: String, fallback: String) = uri.substringAfterLast(':', "").ifBlank { fallback }
}
