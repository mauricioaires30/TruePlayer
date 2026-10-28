package dev.truetagger.data

/** Trecho de texto com o estilo que o Markdown pediu. */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

enum class MdKind { HEADING, BULLET, TEXT, BLANK }

data class MdLine(val kind: MdKind, val spans: List<MdSpan>)

/**
 * Lê o pedaço de Markdown que as notas de release usam: títulos (#), listas (- ou *), **negrito**,
 * *itálico*, `código` e [links](url). Links viram só o texto; o resto é mostrado como veio.
 */
object SimpleMarkdown {
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^\\s*[-*+]\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d+)[.)]\\s+(.*)$")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")

    /** [dropLeadingHeading] tira o título do começo quando a tela já mostra a versão. */
    fun parse(text: String, dropLeadingHeading: Boolean = false): List<MdLine> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<MdLine>()
        for (raw in lines) {
            val h = heading.matchEntire(raw)
            val b = bullet.matchEntire(raw)
            val n = numbered.matchEntire(raw)
            val line = when {
                raw.isBlank() -> MdLine(MdKind.BLANK, emptyList())
                h != null -> MdLine(MdKind.HEADING, spans(h.groupValues[1]).map { it.copy(bold = true) })
                b != null -> MdLine(MdKind.BULLET, spans(b.groupValues[1]))
                n != null -> MdLine(MdKind.BULLET, listOf(MdSpan("${n.groupValues[1]}. ")) + spans(n.groupValues[2]))
                else -> MdLine(MdKind.TEXT, spans(raw.trim()))
            }
            // Não deixa linhas em branco repetidas nem no começo.
            if (line.kind == MdKind.BLANK && (out.isEmpty() || out.last().kind == MdKind.BLANK)) continue
            out += line
        }
        if (dropLeadingHeading && out.firstOrNull()?.kind == MdKind.HEADING) out.removeAt(0)
        while (out.firstOrNull()?.kind == MdKind.BLANK) out.removeAt(0)
        while (out.lastOrNull()?.kind == MdKind.BLANK) out.removeAt(out.lastIndex)
        return out
    }

    /** Estilos dentro de uma linha. Marcas sem par ficam como texto comum. */
    fun spans(input: String): List<MdSpan> {
        val text = link.replace(input) { it.groupValues[1] }
        val result = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) result += MdSpan(plain.toString())
            plain.clear()
        }
        var i = 0
        while (i < text.length) {
            val bold = if (text.startsWith("**", i)) closing(text, "**", i + 2) else -1
            val code = if (text[i] == '`') closing(text, "`", i + 1) else -1
            val italic = if (text[i] == '*' && !text.startsWith("**", i) && !text.getOrElse(i + 1) { ' ' }.isWhitespace()) {
                closing(text, "*", i + 1).takeIf { it > 0 && !text[it - 1].isWhitespace() } ?: -1
            } else {
                -1
            }
            when {
                bold > 0 -> {
                    flush()
                    result += MdSpan(text.substring(i + 2, bold), bold = true)
                    i = bold + 2
                }
                code > 0 -> {
                    flush()
                    result += MdSpan(text.substring(i + 1, code), code = true)
                    i = code + 1
                }
                italic > 0 -> {
                    flush()
                    result += MdSpan(text.substring(i + 1, italic), italic = true)
                    i = italic + 1
                }
                else -> {
                    plain.append(text[i])
                    i++
                }
            }
        }
        flush()
        return result
    }

    /** Posição da marca que fecha, ou -1 se não há texto entre as marcas. */
    private fun closing(text: String, mark: String, from: Int): Int {
        val at = text.indexOf(mark, from)
        return if (at > from) at else -1
    }
}
