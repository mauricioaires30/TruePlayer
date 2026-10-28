package dev.truetagger.data

import java.net.URI

/** Quais links o app sabe baixar e quais são playlists. O download em si é feito pelo yt-dlp. */
object UrlSupport {
    enum class Source(val label: String) { YOUTUBE("YouTube"), SOUNDCLOUD("SoundCloud") }

    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
    private val SOUNDCLOUD_HOSTS = setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "on.soundcloud.com")

    private fun uri(url: String): URI? = runCatching { URI(url.trim()) }.getOrNull()

    private fun host(url: String): String = uri(url)?.host.orEmpty().lowercase()

    fun source(url: String): Source? = when (host(url)) {
        in YOUTUBE_HOSTS -> Source.YOUTUBE
        in SOUNDCLOUD_HOSTS -> Source.SOUNDCLOUD
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
        null -> false
    }

    /** Vale a pena listar o conteúdo antes de baixar: playlist certa ou link curto que esconde o tipo. */
    fun needsListing(url: String): Boolean = isPlaylist(url) || host(url) == "on.soundcloud.com"
}
