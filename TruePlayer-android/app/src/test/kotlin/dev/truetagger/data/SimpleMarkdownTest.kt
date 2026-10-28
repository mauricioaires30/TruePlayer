package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleMarkdownTest {
    @Test fun boldBecomesBoldSpanWithoutAsterisks() {
        val spans = SimpleMarkdown.spans("**Pastas organizadas:** suas músicas")
        assertEquals(listOf(MdSpan("Pastas organizadas:", bold = true), MdSpan(" suas músicas")), spans)
    }

    @Test fun italicAndCode() {
        val spans = SimpleMarkdown.spans("use *assim* e `songs/`")
        assertEquals(
            listOf(MdSpan("use "), MdSpan("assim", italic = true), MdSpan(" e "), MdSpan("songs/", code = true)),
            spans,
        )
    }

    @Test fun linkKeepsOnlyItsText() {
        assertEquals(listOf(MdSpan("veja Releases agora")), SimpleMarkdown.spans("veja [Releases](https://x.y/z) agora"))
    }

    @Test fun unpairedMarksStayAsText() {
        assertEquals(listOf(MdSpan("2 * 3 e **sem fim")), SimpleMarkdown.spans("2 * 3 e **sem fim"))
    }

    @Test fun bulletsAndHeadings() {
        val lines = SimpleMarkdown.parse("## Novidades\n\n- um\n* dois\n1. tres")
        assertEquals(listOf(MdKind.HEADING, MdKind.BLANK, MdKind.BULLET, MdKind.BULLET, MdKind.BULLET), lines.map { it.kind })
        assertTrue(lines[0].spans.all { it.bold })
        assertEquals("1. tres", lines[4].spans.joinToString("") { it.text })
    }

    @Test fun leadingHeadingCanBeDropped() {
        val lines = SimpleMarkdown.parse("## Novidades do TruePlayer 1.6.1\n\n**Primeira versão.**\n\n- item", dropLeadingHeading = true)
        assertEquals(MdKind.TEXT, lines.first().kind)
        assertEquals(MdKind.BULLET, lines.last().kind)
    }

    @Test fun blankLinesAreCollapsedAndTrimmed() {
        val lines = SimpleMarkdown.parse("\n\na\n\n\n\nb\n\n")
        assertEquals(listOf(MdKind.TEXT, MdKind.BLANK, MdKind.TEXT), lines.map { it.kind })
    }

    @Test fun windowsLineEndings() {
        assertEquals(2, SimpleMarkdown.parse("- a\r\n- b\r\n").size)
    }
}
