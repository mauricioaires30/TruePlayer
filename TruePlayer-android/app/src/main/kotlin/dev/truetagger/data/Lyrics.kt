package dev.truetagger.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs

/** Uma linha da letra e o instante (em ms) em que ela começa a ser cantada. */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Letra de uma música: [synced] tem os tempos de cada linha; sem eles, só o texto corrido em [plain].
 * [source] é de onde veio (mostrado na tela como crédito).
 */
data class Lyrics(val synced: List<LrcLine>, val plain: List<String>, val source: String = "") {
    val isSynced: Boolean get() = synced.isNotEmpty()
    val isEmpty: Boolean get() = synced.isEmpty() && plain.isEmpty()
}

/** Leitura do formato LRC (`[mm:ss.xx] texto`). */
object Lrc {
    private val LEADING_TAGS = Regex("""^(\s*\[[^\]]*\])+""")
    private val TAG = Regex("""\[([^\]]*)\]""")
    private val TIME = Regex("""^(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$""")
    private val OFFSET = Regex("""^offset:\s*([+-]?\d+)$""", RegexOption.IGNORE_CASE)
    /** Marcas de palavra do LRC estendido (`<00:12.30>`): a letra é mostrada por linha, então saem. */
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    /** Instante de uma marca como "01:23.45" (centésimos), "01:23.456" (milésimos) ou "01:23" em ms; null se não for um tempo. */
    private fun timeOf(tag: String): Long? {
        val match = TIME.find(tag.trim()) ?: return null
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return (minutes * 60 + seconds) * 1000 + millis
    }

    /** Linhas em ordem de tempo. Uma linha com várias marcas (refrão repetido) vira uma entrada para cada marca. */
    fun parse(text: String): List<LrcLine> {
        var offsetMs = 0L
        val out = ArrayList<LrcLine>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val tags = LEADING_TAGS.find(line) ?: continue
            val times = ArrayList<Long>()
            for (tag in TAG.findAll(tags.value)) {
                val content = tag.groupValues[1]
                val offset = OFFSET.find(content.trim())
                if (offset != null) { offsetMs = offset.groupValues[1].toLong(); continue }
                timeOf(content)?.let { times += it }
            }
            val body = WORD_TIME.replace(line.substring(tags.value.length), "").trim()
            times.forEach { out += LrcLine(it, body) }
        }
        // No LRC, offset positivo faz a letra aparecer mais cedo.
        return out.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) }.sortedBy { it.timeMs }
    }

    /** Índice da linha que está sendo cantada em [positionMs]; -1 antes da primeira linha. */
    fun activeIndex(lines: List<LrcLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) { found = mid; low = mid + 1 } else high = mid - 1
        }
        return found
    }
}

/** Uma música como o LRCLIB (lrclib.net, banco de letras aberto) a devolve. */
data class LrcLibTrack(
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Int,
    val instrumental: Boolean,
    val syncedLyrics: String,
    val plainLyrics: String,
)

object LrcLibParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    private fun trackOf(element: JsonElement?): LrcLibTrack? {
        val obj = element as? JsonObject ?: return null
        val synced = obj["syncedLyrics"].text()
        val plain = obj["plainLyrics"].text()
        val instrumental = (obj["instrumental"] as? JsonPrimitive)?.booleanOrNull == true
        if (synced.isBlank() && plain.isBlank() && !instrumental) return null
        return LrcLibTrack(
            trackName = obj["trackName"].text(),
            artistName = obj["artistName"].text(),
            albumName = obj["albumName"].text(),
            durationSec = ((obj["duration"] as? JsonPrimitive)?.doubleOrNull ?: 0.0).toInt(),
            instrumental = instrumental,
            syncedLyrics = synced,
            plainLyrics = plain,
        )
    }

    /** Resposta de `/api/get`: uma música. */
    fun parseTrack(text: String): LrcLibTrack? = trackOf(runCatching { json.parseToJsonElement(text) }.getOrNull())

    /** Resposta de `/api/search`: uma lista de músicas. Qualquer outro formato devolve lista vazia. */
    fun parseSearch(text: String): List<LrcLibTrack> =
        (runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray).orEmpty().mapNotNull(::trackOf)

    /** Diferença de duração aceita entre o arquivo e a letra, em segundos: acima disso é outra versão da música. */
    const val DURATION_TOLERANCE_SEC = 3

    /**
     * A melhor entre as músicas da busca: duração parecida com a do arquivo (se ela for conhecida), preferindo a que
     * tem a letra sincronizada e, depois, a de duração mais próxima. Null se nenhuma serve.
     */
    fun best(candidates: List<LrcLibTrack>, durationSec: Int): LrcLibTrack? =
        candidates
            .filter { !it.instrumental && (it.syncedLyrics.isNotBlank() || it.plainLyrics.isNotBlank()) }
            .filter { durationSec <= 0 || it.durationSec <= 0 || abs(it.durationSec - durationSec) <= DURATION_TOLERANCE_SEC }
            .sortedWith(
                compareByDescending<LrcLibTrack> { it.syncedLyrics.isNotBlank() }
                    .thenBy { if (durationSec > 0 && it.durationSec > 0) abs(it.durationSec - durationSec) else 0 },
            )
            .firstOrNull()

    /** Letra pronta para mostrar. Null se a música é instrumental ou não tem texto. */
    fun lyricsOf(track: LrcLibTrack): Lyrics? {
        if (track.instrumental) return null
        val synced = Lrc.parse(track.syncedLyrics)
        val plain = track.plainLyrics.lines().map { it.trim() }
        val lyrics = Lyrics(synced, if (synced.isEmpty()) plain.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() } else emptyList(), "LRCLIB")
        return if (lyrics.isEmpty) null else lyrics
    }
}
