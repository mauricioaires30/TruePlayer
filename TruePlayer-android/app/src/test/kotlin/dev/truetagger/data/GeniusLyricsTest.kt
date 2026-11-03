package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O texto das páginas e das letras destes testes é inventado, só para conferir o formato. */
class GeniusLyricsTest {
    private fun hit(type: String, title: String, artist: String, url: String, state: String = "complete") =
        """{"type":"$type","result":{"title":"$title","primary_artist":{"name":"$artist"},"url":"$url","lyrics_state":"$state"}}"""

    private fun search(vararg hits: String) =
        """{"response":{"sections":[{"type":"top_hit","hits":[${hits.joinToString(",")}]},{"type":"song","hits":[${hits.joinToString(",")}]}]}}"""

    @Test fun listsSongsOnceAndSkipsUnreleasedAndNonSongs() {
        val songs = GeniusLyrics.songs(
            search(
                hit("song", "Faixa", "Artista", "https://genius.com/a-faixa-lyrics"),
                hit("song", "Outra", "Artista", "https://genius.com/a-outra-lyrics", state = "unreleased"),
                hit("artist", "x", "y", "https://genius.com/artists/y"),
            ),
        )
        assertEquals(listOf("https://genius.com/a-faixa-lyrics"), songs.map { it.url })
    }

    @Test fun brokenSearchResponsesGiveNothing() {
        assertTrue(GeniusLyrics.songs("").isEmpty())
        assertTrue(GeniusLyrics.songs("não é json").isEmpty())
        assertTrue(GeniusLyrics.songs("""{"response":{}}""").isEmpty())
    }

    private fun song(title: String, artist: String) = GeniusSong(title, artist, "https://genius.com/${title.hashCode()}")

    @Test fun pickTakesTheSameSongByTheSameArtist() {
        val picked = GeniusLyrics.pick(listOf(song("Outra Coisa", "Artista"), song("Faixa Exemplo", "Artista")), "Faixa Exemplo", "Artista")
        assertEquals("Faixa Exemplo", picked?.title)
    }

    @Test fun pickIgnoresFeaturingBracketsAndAccents() {
        assertNotNull(GeniusLyrics.pick(listOf(song("Canção (Ft. Alguém)", "Artista")), "Cancao", "Artista"))
    }

    @Test fun pickRejectsOtherArtistsVersionsAndTranslationPages() {
        assertNull(GeniusLyrics.pick(listOf(song("Faixa", "Outro Artista")), "Faixa", "Artista"))
        assertNull(GeniusLyrics.pick(listOf(song("Faixa (Remix)", "Artista")), "Faixa", "Artista"))
        assertNull(GeniusLyrics.pick(listOf(song("Faixa (English Translation)", "Genius English Translations")), "Faixa", "Artista"))
        assertNull(GeniusLyrics.pick(emptyList(), "Faixa", "Artista"))
    }

    @Test fun pickAcceptsCollaborationsThatStartWithTheArtist() {
        assertNotNull(GeniusLyrics.pick(listOf(song("Faixa", "Artista & Grupo Exemplo")), "Faixa", "Artista"))
    }

    @Test fun pickAcceptsARemixWhenTheFileIsOneToo() {
        assertNotNull(GeniusLyrics.pick(listOf(song("Faixa (Remix)", "Artista")), "Faixa (Remix)", "Artista"))
    }

    @Test fun pickWithoutAnArtistFallsBackToTheTitle() {
        assertNotNull(GeniusLyrics.pick(listOf(song("Faixa", "Qualquer")), "Faixa", ""))
    }

    private fun page(vararg blocks: String) = "<html><body><div class=\"header\">fora</div>" + blocks.joinToString("") + "<div>rodapé</div></body></html>"

    private fun block(inner: String) = "<div data-lyrics-container=\"true\" class=\"Lyrics__Container\">$inner</div>"

    @Test fun extractsLinesBreaksAndStripsTagsAndEntities() {
        val html = page(block("[Verso 1]<br/>primeira linha<br>segunda <a href=\"#\"><span>com link</span></a><br/><br/>[Refrão]<br/>E &amp; mais &#39;uma&#39; &quot;linha&quot;"))
        assertEquals(listOf("primeira linha", "segunda com link", "", "E & mais 'uma' \"linha\""), GeniusLyrics.extract(html))
    }

    @Test fun joinsSeveralBlocksAndHandlesDivsInsideTheBlock() {
        val html = page(
            block("um<div data-exclude-from-selection=\"true\">anotação</div><br/>dois"),
            block("três"),
        )
        val lines = GeniusLyrics.extract(html)!!
        assertEquals(listOf("umanotação", "dois", "três"), lines)
        assertTrue("rodapé" !in lines && "fora" !in lines)
    }

    @Test fun pagesWithoutALyricsBlockGiveNull() {
        assertNull(GeniusLyrics.extract("<html><body><div>nada</div></body></html>"))
        assertNull(GeniusLyrics.extract(page(block("[Instrumental]"))))
        assertNull(GeniusLyrics.extract(""))
    }
}
