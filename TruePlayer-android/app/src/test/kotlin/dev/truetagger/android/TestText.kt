package dev.truetagger.android

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Faz `t()` e `tp()` funcionarem em testes de JVM, lendo os textos de `res/values-<idioma>/strings.xml`
 * (português por padrão). Os ids vêm da classe R, pelo nome do recurso.
 */
object TestText {
    fun install(language: String = "pt") {
        val dir = File("src/main/res/values-$language")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when (node.nodeName) {
                "string" -> strings[node.attributes.getNamedItem("name").nodeValue] = unescape(node.textContent)
                "plurals" -> {
                    val items = HashMap<String, String>()
                    val children = node.childNodes
                    for (j in 0 until children.length) {
                        val item = children.item(j)
                        if (item.nodeName == "item") items[item.attributes.getNamedItem("quantity").nodeValue] = unescape(item.textContent)
                    }
                    plurals[node.attributes.getNamedItem("name").nodeValue] = items
                }
            }
        }
        val stringIds = ids("dev.truetagger.android.R\$string")
        val pluralIds = ids("dev.truetagger.android.R\$plurals")
        AppText.testResolver = object : AppText.TestResolver {
            override fun string(id: Int, args: Array<out Any?>): String {
                val name = stringIds[id] ?: error("recurso de texto desconhecido: $id")
                val text = strings[name] ?: error("sem texto em values-$language: $name")
                return String.format(Locale.ROOT, text, *args)
            }

            override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
                val name = pluralIds[id] ?: error("recurso de plural desconhecido: $id")
                val forms = plurals[name] ?: error("sem plural em values-$language: $name")
                val text = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
                return String.format(Locale.ROOT, text, count, *args)
            }
        }
    }

    private fun ids(className: String): Map<Int, String> =
        Class.forName(className).fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }

    /** Do formato do strings.xml para o texto real: `\n`, `\'`, `\"`, `\\` e aspas em volta. */
    private fun unescape(raw: String): String {
        var text = raw
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(when (val n = text[i + 1]) { 'n' -> '\n'; else -> n })
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
