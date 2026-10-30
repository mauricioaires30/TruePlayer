package dev.truetagger.android

import dev.truetagger.data.SearchHit
import dev.truetagger.data.YtMusicSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Busca no YouTube Music por HTTP, sem abrir o yt-dlp. Qualquer falha (rede, resposta em outro formato) devolve
 * null: quem chama usa a busca pelo yt-dlp, que é mais lenta mas não depende desta API.
 */
object YtMusicClient {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private fun post(body: String): String? = runCatching {
        val connection = (URL(YtMusicSearch.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Origin", "https://music.youtube.com")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Resultados da busca "Músicas" para [query], ou null se a API não respondeu ou não trouxe nada que se entenda. */
    suspend fun search(query: String): List<SearchHit>? = withContext(Dispatchers.IO) {
        val body = YtMusicSearch.requestBody(query)
        // Uma segunda tentativa cobre falha de rede passageira.
        val text = post(body) ?: post(body) ?: return@withContext null
        YtMusicSearch.parse(text).ifEmpty { null }
    }
}
