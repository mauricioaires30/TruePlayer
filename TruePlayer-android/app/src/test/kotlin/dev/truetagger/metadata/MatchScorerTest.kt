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
