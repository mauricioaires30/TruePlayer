package dev.truetagger.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniusParserTest {
    private val sample = """
        {"meta":{"status":200},"response":{"sections":[
          {"type":"top_hit","hits":[{"type":"song","result":{"id":1,"title":"Sky","primary_artist":{"name":"Playboi Carti"},
            "release_date_for_display":"May 25, 2018","song_art_image_url":"https://img/sky.jpg"}}]},
          {"type":"song","hits":[
            {"type":"song","result":{"id":1,"title":"Sky","primary_artist":{"name":"Playboi Carti"},"release_date_for_display":"May 25, 2018"}},
            {"type":"song","result":{"id":2,"title":"Sky High","primary_artist":{"name":"Outro"},"release_date_for_display":null}},
            {"type":"song","result":{"id":3,"title":"","primary_artist":{"name":"Sem titulo"}}}
          ]},
          {"type":"artist","hits":[{"type":"artist","result":{"id":9,"name":"Playboi Carti"}}]}
        ]}}
    """.trimIndent()

    @Test fun readsSongsFromAllSectionsWithoutDuplicates() {
        val results = GeniusParser.parse(sample)
        assertEquals(listOf("Sky", "Sky High"), results.map { it.title })
        assertEquals("Playboi Carti", results.first().artist)
        assertEquals("2018", results.first().year)
        assertEquals("https://img/sky.jpg", results.first().artworkUrl)
    }

    @Test fun entriesWithoutDateKeepAnEmptyYear() {
        assertEquals("", GeniusParser.parse(sample).last().year)
    }

    @Test fun unexpectedContentIsAnEmptyList() {
        assertTrue(GeniusParser.parse("<html>blocked</html>").isEmpty())
        assertTrue(GeniusParser.parse("""{"response":{}}""").isEmpty())
        assertTrue(GeniusParser.parse("").isEmpty())
    }
}
