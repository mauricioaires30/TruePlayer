package dev.truetagger.data

/**
 * Notas de versão em vários idiomas num texto só. Cada idioma começa numa linha de título que termina com a
 * etiqueta entre colchetes (`### Português [pt]`); o texto antes do primeiro título (ex.: "## TruePlayer 1.9.0")
 * vale para todos. Notas sem etiquetas (versões antigas) são mostradas inteiras.
 *
 * O título com a etiqueta aparece como um título normal em versões do app que ainda não separam idiomas e na
 * página da release no GitHub, então nada fica estranho lá.
 */
object ReleaseNotesLocalizer {
    private val marker = Regex("""^#{2,4}\s+.*\[([A-Za-z]{2,3}(?:-[A-Za-z0-9]+)?)]\s*$""")

    /** Escolhe as notas de [languageTag] (ex.: "pt-BR"): a etiqueta exata, depois o idioma, depois o inglês. */
    fun pick(body: String, languageTag: String): String {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // Só vale como bloco de idioma um título com a etiqueta de um idioma do app (um "[novo]" qualquer não conta).
        val known = AppLanguages.all.map { it.tag.lowercase() }.toSet()
        val starts = lines.indices.filter { i -> marker.matchEntire(lines[i])?.groupValues?.get(1)?.lowercase() in known }
        if (starts.isEmpty()) return body.trim()

        val preamble = lines.subList(0, starts.first()).joinToString("\n").trim()
        val blocks = LinkedHashMap<String, String>()
        starts.forEachIndexed { i, start ->
            val end = starts.getOrElse(i + 1) { lines.size }
            val tag = marker.matchEntire(lines[start])!!.groupValues[1].lowercase()
            blocks.putIfAbsent(tag, lines.subList(start + 1, end).joinToString("\n").trim())
        }

        val wanted = languageTag.replace('_', '-').lowercase()
        val chosen = blocks[wanted]
            ?: blocks.entries.firstOrNull { it.key.substringBefore('-') == wanted.substringBefore('-') }?.value
            ?: blocks["en"]
            ?: blocks.values.first()
        return if (preamble.isEmpty()) chosen else "$preamble\n\n$chosen"
    }
}
