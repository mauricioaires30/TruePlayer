package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesLocalizerTest {
    private val body = """
        ## TruePlayer 1.9.0

        ### English [en]
        - New language setting.

        ### Português [pt]
        - Novo idioma nas configurações.

        ### 简体中文 [zh-CN]
        - 新增语言设置。
    """.trimIndent()

    @Test fun picksTheBlockOfTheLanguageAndKeepsTheTitle() {
        assertEquals("## TruePlayer 1.9.0\n\n- Novo idioma nas configurações.", ReleaseNotesLocalizer.pick(body, "pt"))
    }

    @Test fun regionalTagsFallBackToTheLanguage() {
        assertEquals(ReleaseNotesLocalizer.pick(body, "pt"), ReleaseNotesLocalizer.pick(body, "pt-BR"))
        assertEquals(ReleaseNotesLocalizer.pick(body, "pt"), ReleaseNotesLocalizer.pick(body, "pt_PT"))
    }

    @Test fun anExactTagWinsOverTheLanguage() {
        assertEquals("## TruePlayer 1.9.0\n\n- 新增语言设置。", ReleaseNotesLocalizer.pick(body, "zh-CN"))
    }

    @Test fun unknownLanguageFallsBackToEnglish() {
        assertEquals("## TruePlayer 1.9.0\n\n- New language setting.", ReleaseNotesLocalizer.pick(body, "fr"))
    }

    @Test fun withoutEnglishTheFirstBlockIsUsed() {
        val only = "## v\n\n### Português [pt]\n- a\n\n### Español [es]\n- b"
        assertEquals("## v\n\n- a", ReleaseNotesLocalizer.pick(only, "fr"))
    }

    @Test fun oldNotesWithoutMarkersAreShownWhole() {
        val old = "## TruePlayer 1.8.2\n\n- Uma coisa.\n- Outra coisa."
        assertEquals(old, ReleaseNotesLocalizer.pick(old, "en"))
    }

    @Test fun theResultNeverContainsAMarker() {
        listOf("en", "pt", "zh-CN", "fr").forEach { assertFalse(ReleaseNotesLocalizer.pick(body, it).contains("[")) }
    }

    @Test fun windowsLineEndingsWork() {
        assertEquals("## TruePlayer 1.9.0\n\n- New language setting.", ReleaseNotesLocalizer.pick(body.replace("\n", "\r\n"), "en"))
    }

    @Test fun theRealReleaseNotesHaveAnEnglishBlockAndNoMarkersLeak() {
        val file = java.io.File("../../RELEASE_NOTES.md")
        if (!file.exists()) return // fora do repositório completo (ex.: pacote de código-fonte)
        val body = file.readText()
        val english = ReleaseNotesLocalizer.pick(body, "en")
        assertTrue(english.startsWith("## TruePlayer"))
        AppLanguages.all.forEach { language ->
            val text = ReleaseNotesLocalizer.pick(body, language.tag)
            assertFalse("${language.tag} com marcador", Regex("""\[[A-Za-z-]{2,5}]\s*$""", RegexOption.MULTILINE).containsMatchIn(text))
        }
    }

    @Test fun aHeadingWithBracketsThatIsNotALanguageIsJustText() {
        val notes = "## v\n\n### Fixes [new]\n- a"
        assertEquals(notes, ReleaseNotesLocalizer.pick(notes, "en"))
    }
}
