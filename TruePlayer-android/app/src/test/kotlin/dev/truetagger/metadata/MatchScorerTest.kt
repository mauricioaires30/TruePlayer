package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchScorerTest {
    private fun result(title: String, artist: String, durationSec: Int = 0, album: String = "Album") =
        MetadataResult(source = "Teste", sourceId = "$title-$artist-$album", title = title, artist = artist, album = album, durationSec = durationSec)

    @Test fun normalizeIgnoresCaseAccentsAndPunctuation() {
        assertEquals("sao paulo", MatchScorer.normalize("São  Paulo!"))
        assertEquals(1.0, MatchScorer.similarity("Faça Isso", "faca isso"), 0.0001)
    }

    @Test fun exactMatchIsConfident() {
        val candidates = listOf(
            result("Sky", "Playboi Carti", 180),
            result("Sky High", "Outro Artista", 200),
        )
        val pick = MatchScorer.pick(candidates, "Sky", "Playboi Carti", 181)
        assertTrue(MatchScorer.isConfident(pick))
        assertEquals("Playboi Carti", pick!!.result.artist)
    }

    @Test fun wrongArtistIsNotConfident() {
        val pick = MatchScorer.pick(listOf(result("Sky", "Fulano", 180)), "Sky", "Playboi Carti", 180)
        assertFalse(MatchScorer.isConfident(pick))
    }

    @Test fun veryDifferentDurationDragsTheScoreDown() {
        val close = MatchScorer.score("Sky", "Carti", 180, result("Sky", "Carti", 182))
        val far = MatchScorer.score("Sky", "Carti", 180, result("Sky", "Carti", 260))
        assertTrue(close > far)
        assertTrue(far < MatchScorer.DEFAULT_THRESHOLD)
    }

    @Test fun remixDoesNotMatchTheOriginal() {
        val original = MatchScorer.score("Faixa", "Artista", 0, result("Faixa", "Artista"))
        val remix = MatchScorer.score("Faixa", "Artista", 0, result("Faixa (Remix)", "Artista"))
        assertTrue(original > remix)
        assertTrue(remix < MatchScorer.DEFAULT_THRESHOLD)
    }

    @Test fun sameSongOnTwoAlbumsStillHasMargin() {
        val candidates = listOf(
            result("Faixa", "Artista", 200, "Single"),
            result("Faixa", "Artista", 200, "Disco"),
            result("Outra coisa", "Outro", 200),
        )
        val pick = MatchScorer.pick(candidates, "Faixa", "Artista", 200)!!
        assertTrue(pick.margin > 0.5)
        assertTrue(MatchScorer.isConfident(pick))
    }

    @Test fun twoDifferentSongsWithSimilarScoresAreNotConfident() {
        val candidates = listOf(result("Love You", "Artista", 200), result("Love Yo", "Artista", 200))
        val pick = MatchScorer.pick(candidates, "Love You", "Artista", 200)!!
        assertFalse(MatchScorer.isConfident(pick, minMargin = 0.3))
    }

    @Test fun longerMusicVideoStillMatchesTheTrack() {
        // Clipe de 4:23 de uma faixa de 3:20; um cover com a duração do clipe não pode ganhar.
        val candidates = listOf(
            result("Blinding Lights", "The Weeknd", 200),
            result("Blinding Lights", "Roma Symphony Orchestra", 262),
            result("Blinding Lights (Major Lazer Remix) [feat. Major Lazer]", "The Weeknd", 197),
        )
        val pick = MatchScorer.pick(candidates, "Blinding Lights", "The Weeknd", 263)!!
        assertEquals("The Weeknd", pick.result.artist)
        assertEquals("Blinding Lights", pick.result.title)
        assertTrue(MatchScorer.isConfident(pick))
    }

    @Test fun sameSongWrittenDifferentlyIsNotACompetitor() {
        val candidates = listOf(
            result("death bed (feat. beabadoobee) [coffee for your head]", "Powfu", 173),
            result("death bed (coffee for your head) [feat. beabadoobee]", "Powfu & beabadoobee", 173),
            result("Coffee", "beabadoobee", 126),
        )
        val pick = MatchScorer.pick(candidates, "death bed (coffee for your head) ft. beabadoobee", "Powfu", 173)!!
        assertEquals("Powfu", MatchScorer.primaryArtist(pick.result.artist).let { if (it == "powfu") "Powfu" else it })
        assertTrue(MatchScorer.isConfident(pick))
    }

    @Test fun differentArtistWithSameTitleIsNotConfident() {
        val pick = MatchScorer.pick(listOf(result("Blinding Lights", "Roma Symphony Orchestra", 263)), "Blinding Lights", "The Weeknd", 263)
        assertFalse(MatchScorer.isConfident(pick))
    }

    @Test fun shorterVideoIsStillAnotherEdit() {
        // Vídeo bem mais curto que a faixa (versão editada/acelerada): não é a mesma gravação.
        val score = MatchScorer.score("Faixa", "Artista", 120, result("Faixa", "Artista", 200))
        assertTrue(score < MatchScorer.DEFAULT_THRESHOLD)
    }

    @Test fun liveInOneCityIsNotLiveInAnother() {
        val pick = MatchScorer.pick(
            listOf(result("Viva la Vida (Live In Buenos Aires)", "Coldplay", 280)),
            "Viva La Vida (Live in São Paulo)", "Coldplay", 280,
        )
        assertFalse(MatchScorer.isConfident(pick))
    }

    @Test fun studioSongDoesNotMatchAoVivo() {
        val pick = MatchScorer.pick(listOf(result("Oceano (Ao Vivo)", "Djavan", 290)), "Oceano", "Djavan", 290)
        assertFalse(MatchScorer.isConfident(pick))
        val live = MatchScorer.pick(listOf(result("Oceano (Ao Vivo)", "Djavan", 290)), "Oceano (Live)", "Djavan", 290)
        assertTrue(live!!.score > pick!!.score)
    }

    @Test fun coreTitleAndPrimaryArtist() {
        assertEquals("death bed", MatchScorer.coreTitle("death bed (feat. beabadoobee) [coffee for your head]"))
        assertEquals("song", MatchScorer.coreTitle("Song ft. Someone"))
        assertEquals("powfu", MatchScorer.primaryArtist("Powfu & beabadoobee"))
        assertEquals("the weeknd", MatchScorer.primaryArtist("The Weeknd, Daft Punk"))
    }

    @Test fun noCandidatesMeansNoPick() {
        assertNull(MatchScorer.pick(emptyList(), "x", null, 0))
        assertFalse(MatchScorer.isConfident(null))
    }

    @Test fun missingArtistUsesOnlyTitleAndDuration() {
        val pick = MatchScorer.pick(listOf(result("Faixa Unica", "Alguem", 100)), "Faixa Unica", null, 100)
        assertNotNull(pick)
        assertTrue(pick!!.score > 0.95)
    }
}
