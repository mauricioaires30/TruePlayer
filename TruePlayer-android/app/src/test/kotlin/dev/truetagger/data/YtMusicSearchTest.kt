package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtMusicSearchTest {
    private fun artistRun(name: String) =
        """{"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}"""

    private fun albumRun(name: String) =
        """{"text":"$name","navigationEndpoint":{"browseEndpoint":{"browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}"""

    private fun plain(text: String) = """{"text":"$text"}"""

    private fun item(id: String?, title: String, details: List<String>) = buildString {
        append("""{"musicResponsiveListItemRenderer":{""")
        if (id != null) append(""""playlistItemData":{"videoId":"$id"},""")
        append(""""flexColumns":[""")
        append("""{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${plain(title)}]}}},""")
        append("""{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${details.joinToString(",")}]}}}""")
        append("]}}")
    }

    private fun response(vararg items: String) =
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{"contents":[${items.joinToString(",")}]}}]}}}}]}}}"""

    @Test fun readsTitleArtistsAlbumAndDuration() {
        val hits = YtMusicSearch.parse(
            response(
                item("J7p4bzqLvCw", "Blinding Lights", listOf(artistRun("The Weeknd"), plain(" • "), albumRun("Blinding Lights"), plain(" • "), plain("3:22"))),
                item("tEeK40cjmkw", "Save Your Tears", listOf(artistRun("The Weeknd"), plain(" • "), albumRun("After Hours"), plain(" • "), plain("3:36"))),
            ),
        )
        assertEquals(2, hits.size)
        assertEquals("https://music.youtube.com/watch?v=J7p4bzqLvCw", hits[0].url)
        assertEquals("Blinding Lights", hits[0].title)
        assertEquals("The Weeknd", hits[0].artist)
        assertEquals(202, hits[0].durationSec)
        assertEquals("Save Your Tears", hits[1].title)
        assertEquals(216, hits[1].durationSec)
    }

    @Test fun joinsSeveralArtistsAndSkipsAlbumName() {
        val hit = YtMusicSearch.parse(
            response(item("abc", "Song", listOf(artistRun("A"), plain(" & "), artistRun("B"), plain(" • "), albumRun("Album X"), plain(" • "), plain("2:05")))),
        ).single()
        assertEquals("A, B", hit.artist)
        assertEquals(125, hit.durationSec)
    }

    @Test fun fallsBackToFirstPlainTextWhenThereIsNoArtistLink() {
        val hit = YtMusicSearch.parse(response(item("abc", "Song", listOf(plain("Song"), plain(" • "), plain("Some Uploader"), plain(" • "), plain("4:01"))))).single()
        assertEquals("Some Uploader", hit.artist)
        assertEquals(241, hit.durationSec)
    }

    @Test fun readsVideoIdFromWatchEndpointWhenPlaylistItemDataIsMissing() {
        val json = """{"a":{"musicResponsiveListItemRenderer":{"flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Song","navigationEndpoint":{"watchEndpoint":{"videoId":"xyz123"}}}]}}}]}}}"""
        assertEquals("https://music.youtube.com/watch?v=xyz123", YtMusicSearch.parse(json).single().url)
    }

    @Test fun skipsItemsWithoutVideoIdOrTitle() {
        val hits = YtMusicSearch.parse(response(item(null, "Album result", listOf(plain("Album"))), item("id1", "", listOf(plain("3:00"))), item("id2", "Ok", emptyList())))
        assertEquals(listOf("Ok"), hits.map { it.title })
        assertEquals(0, hits.single().durationSec)
    }

    @Test fun unexpectedOrBrokenResponsesGiveAnEmptyList() {
        assertTrue(YtMusicSearch.parse("").isEmpty())
        assertTrue(YtMusicSearch.parse("not json").isEmpty())
        assertTrue(YtMusicSearch.parse("""{"contents":{}}""").isEmpty())
        assertTrue(YtMusicSearch.parse("[1,2,3]").isEmpty())
    }

    @Test fun parsesDurations() {
        assertEquals(202, YtMusicSearch.parseDuration("3:22"))
        assertEquals(3723, YtMusicSearch.parseDuration("1:02:03"))
        assertEquals(0, YtMusicSearch.parseDuration("3.6B plays"))
        assertEquals(0, YtMusicSearch.parseDuration(""))
    }

    @Test fun requestBodyCarriesTheQueryAndTheSongsFilter() {
        val body = YtMusicSearch.requestBody("The Weeknd \"Blinding\" Lights")
        assertTrue(body.contains("WEB_REMIX"))
        assertTrue(body.contains("EgWKAQIIAWoMEA4QChADEAQQCRAF"))
        // aspas na busca precisam sair escapadas, senão o JSON quebra
        assertTrue(body.contains("""The Weeknd \"Blinding\" Lights"""))
    }

    @Test fun theBestHitFeedsTheMatcherLikeTheOtherSources() {
        val hint = TrackHint(title = "Blinding Lights", artistLine = "The Weeknd", artists = listOf("The Weeknd"), durationMs = 200_000L)
        val hits = YtMusicSearch.parse(
            response(
                item("cover1", "Blinding Lights", listOf(artistRun("Bongo Cat"), plain(" • "), albumRun("Blinding Lights"), plain(" • "), plain("2:12"))),
                item("J7p4bzqLvCw", "Blinding Lights", listOf(artistRun("The Weeknd"), plain(" • "), albumRun("Blinding Lights"), plain(" • "), plain("3:22"))),
            ),
        )
        val best = TrackMatcher.best(hint, hits, MatchSource.YT_MUSIC)
        assertNotNull(best)
        assertEquals("https://music.youtube.com/watch?v=J7p4bzqLvCw", best!!.url)
        assertNull(TrackMatcher.best(hint, hits.take(1), MatchSource.YT_MUSIC))
    }
}
