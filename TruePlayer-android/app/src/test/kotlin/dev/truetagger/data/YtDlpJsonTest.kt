package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpJsonTest {
    @Test fun listingKeepsOriginalOrderAndSkipsBrokenEntries() {
        val text = """
            {"_type":"playlist","title":"Meu Set","entries":[
              {"_type":"url","id":"a","url":"https://www.youtube.com/watch?v=a","title":"Primeira"},
              null,
              {"_type":"url","id":"b","ie_key":"Youtube","title":"Segunda"},
              {"_type":"url","title":"Sem link"},
              {"_type":"url","id":"c","url":"https://soundcloud.com/x/y","title":"Terceira"}
            ]}
        """.trimIndent()
        val playlist = YtDlpJson.parseListing(text)
        assertNotNull(playlist)
        assertEquals("Meu Set", playlist!!.title)
        assertEquals(
            listOf("https://www.youtube.com/watch?v=a", "https://www.youtube.com/watch?v=b", "https://soundcloud.com/x/y"),
            playlist.entries.map { it.url },
        )
        assertEquals(listOf("Primeira", "Segunda", "Terceira"), playlist.entries.map { it.title })
    }

    @Test fun singleTrackIsNotAPlaylist() {
        assertNull(YtDlpJson.parseListing("""{"id":"x","title":"Uma faixa","url":"https://soundcloud.com/a/b"}"""))
    }

    @Test fun emptyPlaylistIsNotAPlaylist() {
        assertNull(YtDlpJson.parseListing("""{"_type":"playlist","title":"Vazia","entries":[]}"""))
    }

    @Test fun listingIgnoresLogLinesBeforeTheJson() {
        val text = "[info] algo\n{\"title\":\"P\",\"entries\":[{\"url\":\"https://youtu.be/a\",\"title\":\"A\"}]}"
        assertEquals(1, YtDlpJson.parseListing(text)?.entries?.size)
    }

    @Test fun infoReadsMusicMetadataWhenPresent() {
        val info = YtDlpJson.parseInfo(
            """{"title":"Faixa (Official Video)","uploader":"Canal","upload_date":"20240131","duration":201.4,
               "artist":"Artista A, Artista B","track":"Faixa","album":"Disco","release_year":2023,
               "extractor_key":"Youtube","thumbnail":"https://i/x.jpg"}""",
        )!!
        assertEquals("Artista A", info.mainArtist)
        assertEquals("Faixa", info.track)
        assertEquals(201, info.durationSec)
        assertEquals("2023", info.releaseYear)
        assertTrue(info.hasRealMetadata)
    }

    @Test fun infoWithoutMusicMetadataFallsBackToUploader() {
        val info = YtDlpJson.parseInfo("""{"title":"Artista - Faixa","channel":"Canal","duration":null}""")!!
        assertEquals("Canal", info.uploader)
        assertEquals(0, info.durationSec)
        assertFalse(info.hasRealMetadata)
    }

    @Test fun invalidInfoIsNull() {
        assertNull(YtDlpJson.parseInfo("não é json"))
    }
}
