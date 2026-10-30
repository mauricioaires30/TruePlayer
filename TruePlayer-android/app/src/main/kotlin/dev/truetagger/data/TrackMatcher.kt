package dev.truetagger.data

import dev.truetagger.metadata.MatchScorer
import kotlin.math.abs

/** Um resultado de busca (YouTube Music, YouTube ou SoundCloud) que pode ser a música procurada. */
data class SearchHit(
    val url: String,
    val title: String,
    val durationSec: Int = 0,
    /** Canal (YouTube) ou usuário (SoundCloud). */
    val uploader: String = "",
    /** Só o YouTube Music informa o artista de verdade. */
    val artist: String = "",
)

enum class MatchSource(val minScore: Double) {
    /** Catálogo oficial: confiamos mais, já que costuma ser o mesmo áudio do Spotify. */
    YT_MUSIC(0.80),
    YOUTUBE(0.80),
    /** Qualquer um posta no SoundCloud: pede correspondência forte. */
    SOUNDCLOUD(0.80),
}

/**
 * Decide qual resultado de busca é a música do Spotify. A duração e o nome precisam bater, e versões diferentes
 * (remix, ao vivo, acústica, slowed...) são descartadas quando a original não é uma delas.
 */
object TrackMatcher {
    private val VARIANTS = listOf(
        "remix", "live", "acoustic", "instrumental", "karaoke", "cover", "sped up", "speed up", "slowed", "reverb", "nightcore",
        "8d", "piano", "lofi", "lo fi", "chopped", "screwed", "mashup", "tribute", "bass boosted", "reprise",
    )
    private val FEAT = Regex("""\s*[(\[]\s*(?:feat|ft|with|featuring)\b[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val TOPIC = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)

    /** O que digitar na busca: "artistas título" (com os artistas do Spotify, sem "feat." no título). */
    fun query(hint: TrackHint): String = listOf(hint.artistLine, stripFeat(hint.title)).filter { it.isNotBlank() }.joinToString(" ")

    private fun stripFeat(title: String) = title.replace(FEAT, "").trim()

    private fun variantsIn(text: String): Set<String> {
        val normalized = " ${MatchScorer.normalize(text)} "
        return VARIANTS.filter { normalized.contains(" ${MatchScorer.normalize(it)} ") }.toSet()
    }

    private fun containsWords(haystack: String, needle: String): Boolean {
        val h = " ${MatchScorer.normalize(haystack)} "
        val n = MatchScorer.normalize(needle)
        return n.isNotEmpty() && h.contains(" $n ")
    }

    private fun titleScore(hint: TrackHint, hit: SearchHit): Double {
        val wanted = stripFeat(hint.title)
        val candidate = stripFeat(hit.title)
        // "Artista - Música (Official Video)" contém o nome inteiro da música: conta como quase certo.
        val contained = if (containsWords(hit.title, wanted)) 0.95 else 0.0
        return maxOf(MatchScorer.similarity(wanted, candidate), MatchScorer.similarity(hint.title, hit.title), contained)
    }

    private fun artistScore(hint: TrackHint, hit: SearchHit): Double {
        val names = hint.artists.ifEmpty { listOf(hint.artistLine) }.filter { it.isNotBlank() }
        if (names.isEmpty()) return 0.5
        val where = listOf(hit.title, hit.uploader.replace(TOPIC, ""), hit.artist).joinToString(" ")
        if (names.any { containsWords(where, it) } || containsWords(where, hint.artistLine)) return 1.0
        return names.maxOf { MatchScorer.similarity(it, hit.artist.ifBlank { hit.uploader.replace(TOPIC, "") }) }.let { if (it >= 0.85) it else 0.0 }
    }

    /** Nota de 0 a 1; 0 quer dizer descartado (outra versão ou duração muito diferente). */
    fun score(hint: TrackHint, hit: SearchHit): Double {
        if (hit.title.isBlank()) return 0.0
        if ((variantsIn(hit.title) - variantsIn(hint.title)).isNotEmpty()) return 0.0

        val duration = if (hint.durationSec > 0 && hit.durationSec > 0) {
            val diff = abs(hint.durationSec - hit.durationSec)
            when {
                diff <= 3 -> 1.0
                diff <= 6 -> 0.85
                diff <= 10 -> 0.6
                else -> return 0.0
            }
        } else null

        val title = titleScore(hint, hit)
        val artist = artistScore(hint, hit)
        var total = if (duration != null) 0.5 * title + 0.25 * artist + 0.25 * duration else 0.65 * title + 0.35 * artist
        val channel = hit.uploader.trim()
        // Canal oficial só ajuda quando o artista também confere (um "Topic" de outro artista não vale).
        if (artist >= 0.85 && (TOPIC.containsMatchIn(channel) || (channel.isNotEmpty() && hint.artists.any { MatchScorer.normalize(it) == MatchScorer.normalize(channel) }))) {
            total += 0.05
        }
        return total.coerceAtMost(1.0)
    }

    /** Melhor resultado que passa da nota mínima da [source], ou null. */
    fun best(hint: TrackHint, hits: List<SearchHit>, source: MatchSource): SearchHit? =
        hits.map { it to score(hint, it) }
            .filter { it.second >= source.minScore }
            .maxByOrNull { it.second }
            ?.first
}
