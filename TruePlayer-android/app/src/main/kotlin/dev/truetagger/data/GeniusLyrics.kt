package dev.truetagger.data

import dev.truetagger.metadata.MatchScorer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Uma música da pesquisa do Genius e o endereço da página com a letra. */
data class GeniusSong(val title: String, val artist: String, val url: String)

/**
 * Letra pelo Genius: a pesquisa do site devolve o endereço da página da música e a letra está dentro dela. O Genius
 * não tem os tempos de cada linha, então esta letra não é sincronizada.
 */
object GeniusLyrics {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    /** Músicas da resposta de `genius.com/api/search/multi` cuja letra já foi publicada. Formato estranho dá lista vazia. */
    fun songs(searchJson: String): List<GeniusSong> {
        val root = runCatching { json.parseToJsonElement(searchJson) }.getOrNull() as? JsonObject ?: return emptyList()
        val sections = ((root["response"] as? JsonObject)?.get("sections") as? JsonArray).orEmpty()
        return sections
            .mapNotNull { it as? JsonObject }
            .flatMap { (it["hits"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .filter { it["type"].text() == "song" }
            .mapNotNull { hit ->
                val result = hit["result"] as? JsonObject ?: return@mapNotNull null
                val url = result["url"].text()
                val state = result["lyrics_state"].text()
                if (url.isBlank() || (state.isNotBlank() && state != "complete")) return@mapNotNull null
                GeniusSong(
                    title = result["title"].text(),
                    artist = ((result["primary_artist"] as? JsonObject)?.get("name")).text(),
                    url = url,
                )
            }
            .distinctBy { it.url }
    }

    private val VARIANTS = listOf("remix", "live", "acoustic", "instrumental", "karaoke", "cover", "sped up", "slowed", "reverb")
    private val BRACKETS = Regex("""\s*[(\[][^)\]]*[)\]]""")

    private fun base(title: String) = MatchScorer.normalize(title.replace(BRACKETS, " "))

    private fun variantsIn(text: String): Set<String> {
        val normalized = " ${MatchScorer.normalize(text)} "
        return VARIANTS.filter { normalized.contains(" $it ") }.toSet()
    }

    /** A página do Genius que é a música pedida; null se nenhuma tem título e artista parecidos. */
    fun pick(songs: List<GeniusSong>, title: String, artist: String): GeniusSong? {
        val wantedTitle = base(title)
        val wantedArtist = MatchScorer.normalize(artist)
        if (wantedTitle.isEmpty()) return null
        return songs
            .filterNot { MatchScorer.normalize(it.artist).startsWith("genius") }
            .filter { (variantsIn(it.title) - variantsIn(title)).isEmpty() }
            .map { it to MatchScorer.similarity(wantedTitle, base(it.title)) }
            .filter { (song, titleScore) ->
                val found = MatchScorer.normalize(song.artist)
                // Igual ou começando igual ("Fulano & Grupo" para "Fulano"); "Outro Fulano" não vale.
                val artistOk = wantedArtist.isEmpty() || found == wantedArtist || found.startsWith("$wantedArtist ") ||
                    wantedArtist.startsWith("$found ") || MatchScorer.similarity(wantedArtist, found) >= 0.85
                titleScore >= 0.85 && artistOk
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private val DIV = Regex("""<(/?)div\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<[^>]+>""")
    private val ENTITY = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
    private val SECTION_HEADER = Regex("""^\[[^\]]*]$""")
    private const val MARK = "data-lyrics-container=\"true\""

    private fun decode(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        when {
            name.startsWith("#x") -> name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
            else -> when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> match.value
            }
        }
    }

    /** Conteúdo de cada bloco de letra da página, achando o `</div>` que fecha o bloco mesmo com `div`s dentro dele. */
    private fun containers(html: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val mark = html.indexOf(MARK, from)
            if (mark < 0) break
            val start = html.indexOf('>', mark) + 1
            if (start <= 0) break
            var depth = 1
            var end = -1
            for (tag in DIV.findAll(html.substring(start))) {
                depth += if (tag.groupValues[1].isEmpty()) 1 else -1
                if (depth == 0) { end = start + tag.range.first; break }
            }
            if (end < 0) break
            out += html.substring(start, end)
            from = end
        }
        return out
    }

    /** As linhas da letra da página; títulos de seção como "[Refrão]" saem. Null se a página não tem letra. */
    fun extract(html: String): List<String>? {
        val lines = containers(html)
            .flatMap { decode(TAG.replace(BREAK.replace(it, "\n"), "")).lines() }
            .map { it.trim() }
            .filterNot { SECTION_HEADER.matches(it) }
        val compact = ArrayList<String>()
        for (line in lines) if (line.isNotEmpty() || (compact.isNotEmpty() && compact.last().isNotEmpty())) compact += line
        while (compact.isNotEmpty() && compact.last().isEmpty()) compact.removeAt(compact.lastIndex)
        return compact.takeIf { it.isNotEmpty() }
    }
}
