package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyTest {
    private fun page(entity: String) =
        """<html><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":$entity}}}}}</script></body></html>"""

    private val album = page(
        """{"type":"album","name":"Global Warming","title":"Global Warming","subtitle":"Pitbull","trackList":[
            {"uri":"spotify:track:AAAAAAAAAAAAAAAAAAAAAA","title":"Global Warming (feat. Sensato)","subtitle":"Pitbull,${' '}Sensato","duration":85400,"entityType":"track"},
            {"uri":"spotify:track:BBBBBBBBBBBBBBBBBBBBBB","title":"Don't Stop the Party","subtitle":"Pitbull","duration":206120,"entityType":"track"}]}"""
    )

    @Test fun linksOfAllThreeKindsAreRead() {
        assertEquals(SpotifyRef(SpotifyKind.TRACK, "2sFepQNyl1WjpROruP8bJ0"), SpotifyLinks.parse("https://open.spotify.com/track/2sFepQNyl1WjpROruP8bJ0?si=abc"))
        assertEquals(SpotifyKind.ALBUM, SpotifyLinks.parse("https://open.spotify.com/intl-pt/album/4aawyAB9vmqN3uQ7FjRGTy")?.kind)
        assertEquals(SpotifyKind.PLAYLIST, SpotifyLinks.parse("https://open.spotify.com/playlist/37i9dQZF1DX5Ejj0EkURtP")?.kind)
    }

    @Test fun artistPagesAndOtherSitesAreNotReadable() {
        assertNull(SpotifyLinks.parse("https://open.spotify.com/artist/45dkTj5sMRSjrmBSBeiHym"))
        assertNull(SpotifyLinks.parse("https://example.com/track/2sFepQNyl1WjpROruP8bJ0"))
        assertNull(SpotifyLinks.parse("isso nao e link"))
    }

    @Test fun shortLinksAreRecognizedButNotParsedUntilOpened() {
        assertTrue(SpotifyLinks.isShort("https://spotify.link/abc123"))
        assertNull(SpotifyLinks.parse("https://spotify.link/abc123"))
        assertEquals(SpotifyKind.TRACK, SpotifyLinks.findInText("""<a href="https://open.spotify.com/track/2sFepQNyl1WjpROruP8bJ0?si=1">""")?.kind)
    }

    @Test fun embedUrlPointsToThePublicEmbedPage() {
        assertEquals("https://open.spotify.com/embed/playlist/37i9dQZF1DX5Ejj0EkURtP", SpotifyRef(SpotifyKind.PLAYLIST, "37i9dQZF1DX5Ejj0EkURtP").embedUrl)
    }

    @Test fun urlSupportAcceptsSpotifyMediaLinksOnly() {
        assertEquals(UrlSupport.Source.SPOTIFY, UrlSupport.source("https://open.spotify.com/track/2sFepQNyl1WjpROruP8bJ0"))
        assertEquals(UrlSupport.Source.SPOTIFY, UrlSupport.source("https://spotify.link/abc123"))
        assertFalse(UrlSupport.isSupported("https://open.spotify.com/artist/45dkTj5sMRSjrmBSBeiHym"))
        assertTrue(UrlSupport.isPlaylist("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertTrue(UrlSupport.isPlaylist("https://open.spotify.com/playlist/37i9dQZF1DX5Ejj0EkURtP"))
        assertFalse(UrlSupport.isPlaylist("https://open.spotify.com/track/2sFepQNyl1WjpROruP8bJ0"))
        // Toda música do Spotify precisa ser lida antes: o áudio vem de outro lugar.
        assertTrue(UrlSupport.needsListing("https://open.spotify.com/track/2sFepQNyl1WjpROruP8bJ0"))
    }

    @Test fun albumKeepsOrderAndTrackNumbers() {
        val collection = SpotifyEmbed.parse(album)!!
        assertEquals(SpotifyKind.ALBUM, collection.kind)
        assertEquals("Global Warming", collection.name)
        assertEquals(listOf("AAAAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBBBB"), collection.ids)
        val first = collection.tracks[0]
        assertEquals("Global Warming (feat. Sensato)", first.title)
        assertEquals(listOf("Pitbull", "Sensato"), first.artists)
        assertEquals("Pitbull, Sensato", first.artistLine)
        assertEquals("Global Warming", first.album)
        assertEquals("Pitbull", first.albumArtist)
        assertEquals(1, first.trackNumber)
        assertEquals(2, first.trackTotal)
        assertEquals(85, first.durationSec)
        assertEquals(2, collection.tracks[1].trackNumber)
    }

    @Test fun playlistTracksHaveNoAlbumInfo() {
        val collection = SpotifyEmbed.parse(
            page("""{"type":"playlist","name":"Treino","trackList":[{"uri":"spotify:track:AAAAAAAAAAAAAAAAAAAAAA","title":"Housewife","subtitle":"Tate McRae","duration":208810,"entityType":"track"}]}""")
        )!!
        assertEquals(SpotifyKind.PLAYLIST, collection.kind)
        assertEquals("", collection.tracks.single().album)
        assertNull(collection.tracks.single().trackNumber)
    }

    @Test fun singleTrackUsesTheArtistsList() {
        val collection = SpotifyEmbed.parse(
            page("""{"type":"track","name":"Housewife","title":"Housewife","uri":"spotify:track:2sFepQNyl1WjpROruP8bJ0","artists":[{"name":"Tate McRae"}],"duration":208810,"releaseDate":{"isoString":"2026-10-15T00:00:00Z"}}""")
        )!!
        val track = collection.tracks.single()
        assertEquals("Tate McRae", track.artistLine)
        assertEquals("2026", track.year)
        assertEquals(listOf("2sFepQNyl1WjpROruP8bJ0"), collection.ids)
    }

    @Test fun brokenPagesGiveNull() {
        assertNull(SpotifyEmbed.parse("<html>nada</html>"))
        assertNull(SpotifyEmbed.parse(page("""{"type":"artist","name":"x"}""")))
        assertNull(SpotifyEmbed.parse(page("""{"type":"playlist","name":"vazia","trackList":[]}""")))
    }

    // ---- escolha do resultado da busca ----

    private val housewife = TrackHint("Housewife", "Tate McRae", listOf("Tate McRae"), durationMs = 208_810)

    @Test fun officialVideoWithTheRightLengthIsAccepted() {
        val hit = SearchHit("u", "Tate McRae - Housewife (Official Video)", 210, "Tate McRae")
        assertEquals(hit, TrackMatcher.best(housewife, listOf(hit), MatchSource.YOUTUBE))
    }

    @Test fun otherVersionsAreRejected() {
        listOf("Housewife (Acoustic Version)", "Tate McRae - Housewife (Gravagerz REMIX)", "housewife (slowed + reverb)", "Housewife (Piano)", "Tate McRae - Housewife (Live from The Show)")
            .forEach { title -> assertEquals(title, 0.0, TrackMatcher.score(housewife, SearchHit("u", title, 209, "Tate McRae")), 0.0) }
    }

    @Test fun aVersionTheOriginalAlreadyHasIsNotRejected() {
        val live = TrackHint("Live Forever", "Oasis", listOf("Oasis"), durationMs = 276_000)
        assertTrue(TrackMatcher.score(live, SearchHit("u", "Oasis - Live Forever", 277, "Oasis")) > 0.9)
    }

    @Test fun veryDifferentLengthIsRejected() {
        assertEquals(0.0, TrackMatcher.score(housewife, SearchHit("u", "Tate McRae - Housewife", 262, "Tate McRae")), 0.0)
    }

    @Test fun wrongArtistDoesNotPass() {
        val hit = SearchHit("u", "Housewife", 209, "Some Random Channel")
        assertNull(TrackMatcher.best(housewife, listOf(hit), MatchSource.SOUNDCLOUD))
    }

    @Test fun topicChannelFromYoutubeMusicWins() {
        val topic = SearchHit("topic", "Housewife", 209, "Tate McRae - Topic", artist = "Tate McRae")
        val video = SearchHit("video", "Tate McRae - Housewife (Lyrics)", 209, "Vibe Music")
        assertEquals(topic, TrackMatcher.best(housewife, listOf(video, topic), MatchSource.YOUTUBE))
    }

    @Test fun featuringInTheTitleDoesNotBreakTheMatch() {
        val hint = TrackHint("Don't Stop the Party (feat. TJR)", "Pitbull, TJR", listOf("Pitbull", "TJR"), durationMs = 206_120)
        val hit = SearchHit("u", "Pitbull - Don't Stop The Party ft. TJR (Official Video)", 206, "PitbullVEVO")
        assertNotNull(TrackMatcher.best(hint, listOf(hit), MatchSource.YOUTUBE))
        assertEquals("Pitbull, TJR Don't Stop the Party", TrackMatcher.query(hint))
    }

    @Test fun searchResultsAreParsedInOrder() {
        val json = """{"_type":"playlist","entries":[
            {"id":"a","title":"Tate McRae - Housewife","url":"https://www.youtube.com/watch?v=a","duration":210,"uploader":"Tate McRae"},
            {"id":"b","title":"Sem url"},
            {"id":"c","title":"x","url":"https://api.soundcloud.com/tracks/soundcloud%3Atracks%3A1","duration":195.047,"uploader":"RAP MUSIC"}]}"""
        val hits = YtDlpJson.parseSearch(json)
        assertEquals(listOf("https://www.youtube.com/watch?v=a", "https://api.soundcloud.com/tracks/soundcloud%3Atracks%3A1"), hits.map { it.url })
        assertEquals(210, hits[0].durationSec)
        assertEquals(195, hits[1].durationSec)
        assertEquals("Tate McRae", hits[0].uploader)
    }

    @Test fun detailedEntriesUseThePageUrlAndTheRealTrackName() {
        val json = """{"entries":[{"id":"FXk4oLgkKL0","title":"Housewife (Official)","track":"Housewife","artist":"Tate McRae","duration":209,
            "uploader":"Tate McRae","url":"https://rr1.googlevideo.com/audio","webpage_url":"https://www.youtube.com/watch?v=FXk4oLgkKL0"}]}"""
        val hit = YtDlpJson.parseSearch(json).single()
        assertEquals("https://www.youtube.com/watch?v=FXk4oLgkKL0", hit.url)
        assertEquals("Housewife", hit.title)
        assertEquals("Tate McRae", hit.artist)
        assertNotNull(TrackMatcher.best(housewife, listOf(hit), MatchSource.YT_MUSIC))
    }

    @Test fun aCoverWithTheSameNameAndLengthFromAnotherArtistIsRejected() {
        val cover = SearchHit("u", "Housewife", 208, "Release - Topic", artist = "Xtina Louise, Fran Garro")
        assertNull(TrackMatcher.best(housewife, listOf(cover), MatchSource.YT_MUSIC))
    }

    @Test fun playlistCoverIsTheLargestImageOfTheEmbed() {
        val collection = SpotifyEmbed.parse(
            page("""{"type":"playlist","name":"Treino","coverArt":{"sources":[{"url":"https://i.scdn.co/image/small","width":64},{"url":"https://i.scdn.co/image/big","width":640}]},
                "trackList":[{"uri":"spotify:track:AAAAAAAAAAAAAAAAAAAAAA","title":"Housewife","subtitle":"Tate McRae","duration":1,"entityType":"track"}]}""")
        )!!
        assertEquals("https://i.scdn.co/image/big", collection.coverUrl)
    }

    @Test fun albumCoverFallsBackToTheVisualIdentityImages() {
        val collection = SpotifyEmbed.parse(
            page("""{"type":"album","name":"A","subtitle":"B","visualIdentity":{"image":[{"url":"https://x/300","maxWidth":300},{"url":"https://x/640","maxWidth":640}]},
                "trackList":[{"uri":"spotify:track:AAAAAAAAAAAAAAAAAAAAAA","title":"T","subtitle":"B","duration":1,"entityType":"track"}]}""")
        )!!
        assertEquals("https://x/640", collection.coverUrl)
    }

    @Test fun listedPlaylistsKeepTheirPicture() {
        val json = """{"title":"Popular","thumbnails":[{"url":"https://i/small.jpg","width":168},{"url":"https://i/large.jpg","width":246}],
            "entries":[{"id":"a","title":"x","url":"https://www.youtube.com/watch?v=a"}]}"""
        assertEquals("https://i/large.jpg", YtDlpJson.parseListing(json)!!.thumbnail)
        assertEquals("https://i/only.jpg", YtDlpJson.parseListing("""{"title":"P","thumbnail":"https://i/only.jpg","entries":[{"id":"a","url":"https://youtu.be/a"}]}""")!!.thumbnail)
    }

    @Test fun detailedSearchResultsKeepTheirFullInfoForADirectDownload() {
        val json = """{"entries":[{"id":"a","title":"Housewife","duration":209,"uploader":"Tate McRae","webpage_url":"https://www.youtube.com/watch?v=a",
            "formats":[{"format_id":"140","ext":"m4a","url":"https://rr/audio"}]},{"id":"b","title":"x","url":"https://www.youtube.com/watch?v=b"}]}"""
        val hits = YtDlpJson.parseSearchWithInfo(json)
        assertEquals(2, hits.size)
        assertTrue(YtDlpJson.hasFormats(hits[0].second))
        assertFalse(YtDlpJson.hasFormats(hits[1].second))
        assertEquals("https://www.youtube.com/watch?v=a", hits[0].first.url)
    }
}
