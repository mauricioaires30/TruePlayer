package dev.truetagger.data

import java.net.URI

/** Quais links o app sabe baixar e quais são playlists. O download em si é feito pelo yt-dlp. */
object UrlSupport {
    enum class Source(val label: String) { YOUTUBE("YouTube"), SOUNDCLOUD("SoundCloud"), SPOTIFY("Spotify") }

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    private val SOUNDCLOUD_HOSTS = setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "on.soundcloud.com")

    private fun uri(url: String): URI? = runCatching { URI(url.trim()) }.getOrNull()

    private fun host(url: String): String = uri(url)?.host.orEmpty().lowercase()

    fun source(url: String): Source? = when {
        host(url) in YOUTUBE_HOSTS -> Source.YOUTUBE
        host(url) in SOUNDCLOUD_HOSTS -> Source.SOUNDCLOUD
        // Do Spotify só valem links de música, álbum e playlist (e os links curtos, que escondem o tipo).
        SpotifyLinks.parse(url) != null || SpotifyLinks.isShort(url) -> Source.SPOTIFY
        else -> null
    }

    fun isSupported(url: String): Boolean = source(url) != null

    private fun queryParams(url: String): Map<String, String> =
        uri(url)?.rawQuery.orEmpty().split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun pathSegments(url: String): List<String> =
        uri(url)?.path.orEmpty().split('/').filter { it.isNotEmpty() }

    /**
     * Link que com certeza é uma playlist. Um vídeo do YouTube aberto dentro de uma playlist
     * (watch?v=...&list=...) conta como música única, e as "mixes" automáticas (list=RD...) também.
     */
    fun isPlaylist(url: String): Boolean = when (source(url)) {
        Source.YOUTUBE -> {
            val list = queryParams(url)["list"].orEmpty()
            pathSegments(url).firstOrNull() == "playlist" && list.isNotBlank() && !list.startsWith("RD")
        }
        Source.SOUNDCLOUD -> {
            // soundcloud.com/<artista>/sets/<nome>
            val segments = pathSegments(url)
            val setsAt = segments.indexOf("sets")
            setsAt >= 1 && segments.size > setsAt + 1
        }
        Source.SPOTIFY -> SpotifyLinks.parse(url)?.kind.let { it == SpotifyKind.ALBUM || it == SpotifyKind.PLAYLIST }
        null -> false
    }

    /**
     * Vale a pena listar o conteúdo antes de baixar: playlist certa ou link curto que esconde o tipo. Todo link do
     * Spotify passa por aqui: o áudio não vem de lá, então a música precisa ser lida e procurada em outro lugar.
     */
    fun needsListing(url: String): Boolean = isPlaylist(url) || host(url) == "on.soundcloud.com" || source(url) == Source.SPOTIFY
}
