package dev.truetagger.data

/**
 * Título de vídeo/faixa já separado em partes. [version] é algo como "v2" ou "Leak"; [unreleased]
 * vira true quando o título tem marca de música não lançada (leak, snippet, demo, v1...).
 */
data class ParsedTitle(
    val artist: String?,
    val title: String,
    val version: String?,
    val unreleased: Boolean,
    /** Marcas como remix, live, acoustic encontradas no título (ajudam a comparar com resultados). */
    val variants: Set<String> = emptySet(),
)

object TitleParser {
    private val NOISE_GROUP = Regex(
        "(?i)[(\\[{]\\s*(?:official(?:\\s+(?:music|lyric|lyrics))?(?:\\s+(?:video|audio|visuali[sz]er))?|" +
            "(?:music|lyric|lyrics|audio|video|visuali[sz]er|hd|4k|hq|lq|cdq|explicit|clean|full\\s+song|" +
            "out\\s+now|new|nova\\s+m[uú]sica|letra|legendado|tradu[cç][aã]o)(?:\\s+video)?|" +
            "prod\\.?[^)\\]}]*|produced\\s+by[^)\\]}]*|dir\\.?[^)\\]}]*)\\s*[)\\]}]",
    )
    private val FEAT_GROUP = Regex("(?i)\\s*[(\\[]\\s*(?:ft|feat|featuring)\\.?\\s+[^)\\]]*[)\\]]")
    private val VERSION_NUMBER = Regex("(?i)(?<![a-z0-9])v\\s?(\\d+(?:\\.\\d+)?)(?![a-z0-9])")
    private val UNRELEASED_WORDS = Regex("(?i)(?<![a-z0-9])(leak(?:ed)?|unreleased|snippet|demo|rough|vault|og|cdq|lq)(?![a-z0-9])")
    private val MARKER_GROUP = Regex(
        "(?i)[(\\[{]\\s*(?:v\\s?\\d+(?:\\.\\d+)?|leak(?:ed)?|unreleased|snippet|demo|rough|vault|og|cdq|lq)(?:\\s*[,/&+]?\\s*(?:v\\s?\\d+(?:\\.\\d+)?|leak(?:ed)?|unreleased|snippet|demo|rough|vault|og|cdq|lq))*\\s*[)\\]}]",
    )
    private val TRAILING_MARKER = Regex(
        "(?i)\\s+(?:v\\s?\\d+(?:\\.\\d+)?|leak(?:ed)?|unreleased|snippet|demo|rough|vault|og|cdq|lq)\\s*$",
    )
    private val VARIANT_WORDS = listOf(
        "remix", "live", "acoustic", "instrumental", "karaoke", "cover", "sped up", "slowed", "reverb", "remaster", "edit",
    )
    private val DASH = Regex("\\s[-–—]\\s")

    fun parse(rawTitle: String): ParsedTitle {
        val raw = rawTitle.trim()
        val version = detectVersion(raw)
        val unreleased = VERSION_NUMBER.containsMatchIn(raw) || UNRELEASED_WORDS.containsMatchIn(raw)
        val lower = raw.lowercase()
        val variants = VARIANT_WORDS.filter { Regex("(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(lower) }.toSet()

        var s = raw
        s = s.replace(NOISE_GROUP, " ")
        s = s.replace(FEAT_GROUP, " ")
        // "Música | Official Video": só vale o que vem antes da barra.
        s = s.substringBefore(" | ").substringBefore(" // ")
        s = s.replace(MARKER_GROUP, " ")
        repeat(2) { s = s.replace(TRAILING_MARKER, "") }
        s = collapse(s)

        val match = DASH.find(s)
        val artist: String?
        val title: String
        if (match != null) {
            artist = collapse(s.substring(0, match.range.first)).ifBlank { null }
            title = collapse(s.substring(match.range.last + 1))
        } else {
            artist = null
            title = s
        }
        return ParsedTitle(artist, title.trim('-', '–', '—', '|', '·', ' '), version, unreleased, variants)
    }

    private fun detectVersion(raw: String): String? {
        VERSION_NUMBER.find(raw)?.let { return "v" + it.groupValues[1] }
        val word = UNRELEASED_WORDS.find(raw)?.groupValues?.get(1)?.lowercase() ?: return null
        return when (word) {
            "leak", "leaked" -> "Leak"
            "og" -> "OG"
            "cdq" -> "CDQ"
            "lq" -> "LQ"
            else -> word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun collapse(value: String): String = value.replace(Regex("\\s+"), " ").trim()
}
