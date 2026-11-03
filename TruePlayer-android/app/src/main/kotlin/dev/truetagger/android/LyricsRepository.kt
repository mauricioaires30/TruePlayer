package dev.truetagger.android

import dev.truetagger.data.GeniusLyrics
import dev.truetagger.data.LrcLibParser
import dev.truetagger.data.LrcLibTrack
import dev.truetagger.data.Lyrics
import dev.truetagger.model.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Procura a letra da música na internet: primeiro no LRCLIB (banco de letras aberto, tem os tempos de cada linha) e,
 * se ele não tem a música, no Genius (só o texto). Nada é baixado para a pasta de músicas: a letra só vive na memória
 * enquanto o app está aberto. Sem internet ou sem letra, o resultado é null.
 */
object LyricsRepository {
    private const val LRCLIB = "https://lrclib.net/api"
    private const val GENIUS_SEARCH = "https://genius.com/api/search/multi"
    private const val APP_AGENT = "TruePlayer (https://github.com/mauricioaires30/TruePlayer)"
    private const val BROWSER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    /** Letras já procuradas nesta sessão (inclusive "não tem"); falha de rede não entra, para tentar de novo depois. */
    private val cache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 40
    }

    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        data object NotFound : Reply
        data object Failed : Reply
    }

    private fun get(url: String, agent: String): Reply {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", agent)
                setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            }
        } catch (error: IOException) {
            return Reply.Failed
        }
        return try {
            when (connection.responseCode) {
                in 200..299 -> Reply.Ok(String(connection.inputStream.use { it.readBytes() }, Charsets.UTF_8))
                404 -> Reply.NotFound
                else -> Reply.Failed
            }
        } catch (error: IOException) {
            Reply.Failed
        } finally {
            connection.disconnect()
        }
    }

    private fun q(value: String) = URLEncoder.encode(value, "UTF-8")

    /** Cantor principal: "A, B", "A; B", "A & B" e "A feat. B" viram "A". */
    internal fun primaryArtist(artist: String): String =
        artist.split(',', ';', '&', '/').first().replace(Regex("""(?i)\s+(feat|ft|featuring|with)\b.*$"""), "").trim()

    /** Título sem "(feat. ...)", "[...]" e sufixos como " - Remastered 2011". */
    internal fun cleanTitle(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+-\s+.*$"""), "").trim()

    /** Resultado de uma fonte: [lyrics] null com [failed] falso quer dizer "a fonte não tem"; com [failed] verdadeiro, "não deu para perguntar". */
    private class Attempt(val lyrics: Lyrics?, val failed: Boolean)

    private fun fromLrcLib(track: AudioTrack, artist: String, title: String): Attempt {
        val durationSec = (track.durationMs / 1000).toInt()
        var failed = false

        fun lyricsFrom(candidate: LrcLibTrack?) = candidate?.let { LrcLibParser.lyricsOf(it) }

        // 1) Busca exata (artista, título, álbum e duração), a mais certeira.
        if (artist.isNotBlank() && track.album.isNotBlank() && durationSec > 0) {
            val path = "get?artist_name=${q(artist)}&track_name=${q(title)}&album_name=${q(track.album)}&duration=$durationSec"
            when (val reply = get("$LRCLIB/$path", APP_AGENT)) {
                is Reply.Ok -> LrcLibParser.parseTrack(reply.body)?.let { lyricsFrom(it) }?.let { return Attempt(it, false) }
                Reply.NotFound -> Unit
                Reply.Failed -> return Attempt(null, true)
            }
        }
        // 2) Busca por nome, escolhendo pela duração; depois com o título limpo, se for diferente.
        for (name in listOf(title, cleanTitle(title)).distinct().filter { it.isNotBlank() }) {
            val path = "search?track_name=${q(name)}" + if (artist.isNotBlank()) "&artist_name=${q(artist)}" else ""
            when (val reply = get("$LRCLIB/$path", APP_AGENT)) {
                is Reply.Ok -> lyricsFrom(LrcLibParser.best(LrcLibParser.parseSearch(reply.body), durationSec))?.let { return Attempt(it, false) }
                Reply.NotFound -> Unit
                Reply.Failed -> failed = true
            }
        }
        return Attempt(null, failed)
    }

    /** Pesquisa do site do Genius (a mesma da caixa de busca, sem chave) e leitura da página da música escolhida. */
    private fun fromGenius(artist: String, title: String): Attempt {
        val cleaned = cleanTitle(title).ifBlank { title }
        val search = when (val reply = get("$GENIUS_SEARCH?q=${q(listOf(artist, cleaned).filter { it.isNotBlank() }.joinToString(" "))}", BROWSER_AGENT)) {
            is Reply.Ok -> reply.body
            Reply.NotFound -> return Attempt(null, false)
            Reply.Failed -> return Attempt(null, true)
        }
        val song = GeniusLyrics.pick(GeniusLyrics.songs(search), cleaned, artist) ?: return Attempt(null, false)
        return when (val page = get(song.url, BROWSER_AGENT)) {
            is Reply.Ok -> Attempt(GeniusLyrics.extract(page.body)?.let { Lyrics(emptyList(), it, source = "Genius") }, false)
            Reply.NotFound -> Attempt(null, false)
            Reply.Failed -> Attempt(null, true)
        }
    }

    /** A letra de [track], ou null se não há (ou se está sem internet). Bloqueia a rede: roda fora da thread principal. */
    suspend fun load(track: AudioTrack): Lyrics? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(track.id)) return@withContext cache[track.id] }
        val artist = primaryArtist(track.artist)
        val title = track.title.ifBlank { track.displayName.substringBeforeLast('.') }
        if (title.isBlank()) return@withContext null

        val lrclib = runCatching { fromLrcLib(track, artist, title) }.getOrElse { Attempt(null, true) }
        if (lrclib.lyrics != null) return@withContext remember(track, lrclib.lyrics)
        // Se o LRCLIB não tem a música (ou está fora do ar), tenta o Genius.
        val genius = runCatching { fromGenius(artist, title) }.getOrElse { Attempt(null, true) }
        if (genius.lyrics != null) return@withContext remember(track, genius.lyrics)
        // Só guarda o "não tem" quando as duas fontes responderam; falha de rede deixa tentar de novo depois.
        if (!lrclib.failed && !genius.failed) remember(track, null)
        null
    }

    private fun remember(track: AudioTrack, lyrics: Lyrics?): Lyrics? {
        synchronized(cache) { cache[track.id] = lyrics }
        return lyrics
    }
}
