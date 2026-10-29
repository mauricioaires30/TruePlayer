package dev.truetagger.android

import dev.truetagger.data.SpotifyCollection
import dev.truetagger.data.SpotifyEmbed
import dev.truetagger.data.SpotifyLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lê músicas, álbuns e playlists do Spotify pela página pública de "player incorporado" (sem chave nem conta).
 * Só o nome, os artistas e a duração: o áudio do Spotify tem DRM e não é baixado daqui.
 */
object SpotifyClient {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private class Page(val body: String, val finalUrl: String)

    private fun fetch(url: String): Page? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "en-US,en;q=0.8")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            Page(String(bytes, Charsets.UTF_8), connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Lê o link (inclusive links curtos spotify.link). Null se não for um link do Spotify que dê para ler. */
    suspend fun load(url: String): SpotifyCollection? = withContext(Dispatchers.IO) {
        var ref = SpotifyLinks.parse(url)
        if (ref == null && SpotifyLinks.isShort(url)) {
            val page = fetch(url) ?: return@withContext null
            ref = SpotifyLinks.parse(page.finalUrl) ?: SpotifyLinks.findInText(page.body)
        }
        val target = ref ?: return@withContext null
        // Uma segunda tentativa cobre falha de rede passageira.
        val html = (fetch(target.embedUrl) ?: fetch(target.embedUrl))?.body ?: return@withContext null
        SpotifyEmbed.parse(html)
    }
}
