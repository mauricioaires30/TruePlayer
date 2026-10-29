package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Confere os textos traduzidos: todo idioma precisa ter as mesmas chaves do inglês (o padrão), com os mesmos
 * marcadores de formato (%1$s...), e cada plural precisa da forma "other". Também confere que todo idioma
 * listado em [AppLanguages] tem sua pasta de textos.
 */
class StringResourcesTest {
    private val res = File("src/main/res")

    private class Parsed(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun parse(dir: File): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(dir, "strings.xml"))
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            when (node.nodeName) {
                "string" -> strings[node.attributes.getNamedItem("name").nodeValue] = node.textContent
                "plurals" -> {
                    val forms = LinkedHashMap<String, String>()
                    for (j in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(j)
                        if (item.nodeName == "item") forms[item.attributes.getNamedItem("quantity").nodeValue] = item.textContent
                    }
                    plurals[node.attributes.getNamedItem("name").nodeValue] = forms
                }
            }
        }
        return Parsed(strings, plurals)
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""").findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    private fun languageDir(tag: String) = File(
        res,
        when (tag) {
            "en" -> "values"
            "id" -> "values-in" // o Android usa o código antigo "in" para o indonésio
            else -> "values-" + tag.replace("-", "-r")
        },
    )

    @Test fun everyListedLanguageHasItsTextsAndNothingIsMissing() {
        val base = parse(File(res, "values"))
        for (language in AppLanguages.all) {
            val dir = languageDir(language.tag)
            assertTrue("falta a pasta de ${language.tag}", File(dir, "strings.xml").exists())
            val other = parse(dir)
            val missing = (base.strings.keys - "app_name") - other.strings.keys
            assertTrue("${language.tag}: faltam textos $missing", missing.isEmpty())
            val extra = other.strings.keys - base.strings.keys
            assertTrue("${language.tag}: textos que não existem no inglês $extra", extra.isEmpty())
            val missingPlurals = base.plurals.keys - other.plurals.keys
            assertTrue("${language.tag}: faltam plurais $missingPlurals", missingPlurals.isEmpty())
        }
    }

    @Test fun placeholdersMatchTheEnglishOnes() {
        val base = parse(File(res, "values"))
        for (language in AppLanguages.all) {
            val other = parse(languageDir(language.tag))
            base.strings.forEach { (name, text) ->
                val translated = other.strings[name] ?: return@forEach
                assertEquals("${language.tag}/$name", placeholders(text), placeholders(translated))
            }
            base.plurals.forEach { (name, forms) ->
                val translated = other.plurals[name] ?: return@forEach
                val expected = placeholders(forms.getValue("other"))
                translated.forEach { (quantity, text) ->
                    // Nas formas "zero", "one" e "two" o número pode estar escrito por extenso ("um arquivo").
                    val withoutCount = expected - "%1\$s"
                    val ok = placeholders(text) == expected || (quantity in setOf("zero", "one", "two") && placeholders(text) == withoutCount)
                    assertTrue("${language.tag}/$name/$quantity: ${placeholders(text)} esperado $expected", ok)
                }
            }
        }
    }

    @Test fun everyPluralHasTheOtherForm() {
        for (language in AppLanguages.all) {
            parse(languageDir(language.tag)).plurals.forEach { (name, forms) ->
                assertTrue("${language.tag}/$name sem 'other'", "other" in forms)
            }
        }
    }

    @Test fun localesConfigListsExactlyTheAppLanguages() {
        val text = File(res, "xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(AppLanguages.all.map { it.tag }.toSet(), listed)
    }
}
