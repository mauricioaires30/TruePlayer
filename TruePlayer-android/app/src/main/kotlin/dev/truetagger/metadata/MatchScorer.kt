package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import java.text.Normalizer
import kotlin.math.abs

/**
 * Pontua (de 0 a 1) o quanto um resultado de busca é a música que baixamos e decide se dá para
 * aplicar as tags sozinho. Dá peso a título, artista e duração e desconta versões diferentes
 * (remix, ao vivo...).
 */
object MatchScorer {
    data class Pick(val result: MetadataResult, val score: Double, val margin: Double)

    const val DEFAULT_THRESHOLD = 0.85
    const val DEFAULT_MARGIN = 0.05

    private val VARIANTS = listOf(
        "remix", "live", "acoustic", "instrumental", "karaoke", "cover", "sped up", "slowed", "reverb", "remaster", "edit",
    )

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return decomposed.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val tokensA = x.split(' ').toSet()
        val tokensB = y.split(' ').toSet()
        val jaccard = tokensA.intersect(tokensB).size.toDouble() / tokensA.union(tokensB).size
        val levenshtein = 1.0 - editDistance(x, y).toDouble() / maxOf(x.length, y.length)
        return maxOf(jaccard, levenshtein)
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun variantsIn(value: String): Set<String> {
        val text = " ${normalize(value)} "
        return VARIANTS.filter { text.contains(" $it ") }.toSet()
    }

    private fun durationScore(expectedSec: Int, actualSec: Int): Double? {
        if (expectedSec <= 0 || actualSec <= 0) return null
        val diff = abs(expectedSec - actualSec)
        return when {
            diff <= 3 -> 1.0
            diff <= 8 -> 0.7
            diff <= 15 -> 0.3
            else -> 0.0
        }
    }

    fun score(expectedTitle: String, expectedArtist: String?, expectedDurationSec: Int, candidate: MetadataResult): Double {
        val titleScore = similarity(expectedTitle, candidate.title)
        val artistScore = expectedArtist?.takeIf { it.isNotBlank() }?.let { similarity(it, candidate.artist) }
        val duration = durationScore(expectedDurationSec, candidate.durationSec)

        var total = when {
            artistScore != null && duration != null -> 0.55 * titleScore + 0.30 * artistScore + 0.15 * duration
            artistScore != null -> 0.65 * titleScore + 0.35 * artistScore
            duration != null -> 0.75 * titleScore + 0.25 * duration
            else -> titleScore
        }
        // Versão diferente (remix, ao vivo...) não é a mesma faixa.
        if (variantsIn(expectedTitle) != variantsIn(candidate.title)) total *= 0.75
        // Duração muito diferente: provavelmente outra edição.
        if (duration == 0.0 && abs(expectedDurationSec - candidate.durationSec) > 20) total *= 0.6
        return total
    }

    /** Melhor candidato e a folga dele sobre o melhor candidato que seja *outra* música. */
    fun pick(candidates: List<MetadataResult>, expectedTitle: String, expectedArtist: String?, expectedDurationSec: Int): Pick? {
        if (candidates.isEmpty()) return null
        val scored = candidates
            .map { it to score(expectedTitle, expectedArtist, expectedDurationSec, it) }
            .sortedByDescending { it.second }
        val (best, bestScore) = scored.first()
        val bestKey = songKey(best)
        val runnerUp = scored.firstOrNull { songKey(it.first) != bestKey }?.second ?: 0.0
        return Pick(best, bestScore, bestScore - runnerUp)
    }

    fun isConfident(pick: Pick?, threshold: Double = DEFAULT_THRESHOLD, minMargin: Double = DEFAULT_MARGIN): Boolean =
        pick != null && pick.score >= threshold && pick.margin >= minMargin

    private fun songKey(r: MetadataResult) = normalize(r.title) + "|" + normalize(r.artist)
}
