package dev.truetagger.android

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
 * Procura a letra da música na internet (LRCLIB, banco de letras aberto e sem chave). Nada é baixado para a pasta de
 * músicas: a letra só vive na memória enquanto o app está aberto. Sem internet ou sem letra, o resultado é null.
 */
object LyricsRepository {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "TruePlayer (https://github.com/mauricioaires30/TruePlayer)"

    /** Letras já procuradas nesta sessão (inclusive "não tem"); falha de rede não entra, para tentar de novo depois. */
    private val cache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics?>?) = size > 40
    }

    private sealed interface Reply {
        data class Ok(val body: String) : Reply
        data object NotFound : Reply
        data object Failed : Reply
    }

    private fun get(path: String): Reply {
        val connection = try {
            (URL("$BASE/$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", USER_AGENT)
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

    /** Devolve `null` se falhou a rede (vale tentar de novo) e `Optional`-like [Lyrics]? dentro de [Found] se a busca terminou. */
    private class Found(val lyrics: Lyrics?)

    private fun search(track: AudioTrack): Found? {
        val artist = primaryArtist(track.artist)
        val title = track.title.ifBlank { track.displayName.substringBeforeLast('.') }
        if (title.isBlank()) return Found(null)
        val durationSec = (track.durationMs / 1000).toInt()
        var failed = false

        fun lyricsFrom(candidate: LrcLibTrack?) = candidate?.let { LrcLibParser.lyricsOf(it) }

        // 1) Busca exata (artista, título, álbum e duração), a mais certeira.
        if (artist.isNotBlank() && track.album.isNotBlank() && durationSec > 0) {
            val path = "get?artist_name=${q(artist)}&track_name=${q(title)}&album_name=${q(track.album)}&duration=$durationSec"
            when (val reply = get(path)) {
                is Reply.Ok -> LrcLibParser.parseTrack(reply.body)?.let { lyricsFrom(it) }?.let { return Found(it) }
                Reply.NotFound -> Unit
                Reply.Failed -> return null
            }
        }
        // 2) Busca por nome, escolhendo pela duração; depois com o título limpo, se for diferente.
        for (name in listOf(title, cleanTitle(title)).distinct().filter { it.isNotBlank() }) {
            val path = "search?track_name=${q(name)}" + if (artist.isNotBlank()) "&artist_name=${q(artist)}" else ""
            when (val reply = get(path)) {
                is Reply.Ok -> lyricsFrom(LrcLibParser.best(LrcLibParser.parseSearch(reply.body), durationSec))?.let { return Found(it) }
                Reply.NotFound -> Unit
                Reply.Failed -> failed = true
            }
        }
        return if (failed) null else Found(null)
    }

    /** A letra de [track], ou null se não há (ou se está sem internet). Bloqueia a rede: roda fora da thread principal. */
    suspend fun load(track: AudioTrack): Lyrics? = withContext(Dispatchers.IO) {
        synchronized(cache) { if (cache.containsKey(track.id)) return@withContext cache[track.id] }
        val found = runCatching { search(track) }.getOrNull() ?: return@withContext null
        synchronized(cache) { cache[track.id] = found.lyrics }
        found.lyrics
    }
}
