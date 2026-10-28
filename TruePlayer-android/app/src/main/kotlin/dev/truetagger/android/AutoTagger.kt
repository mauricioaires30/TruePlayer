package dev.truetagger.android

import dev.truetagger.data.SourceInfo
import dev.truetagger.data.TitleParser
import dev.truetagger.metadata.MatchScorer
import dev.truetagger.metadata.MetadataRepository
import dev.truetagger.metadata.createPlatformHttpClient
import dev.truetagger.model.EditableTags
import dev.truetagger.model.MetadataResult

/**
 * Procura a música recém-baixada no Apple Music, no Deezer e (se esses não acharem) no Genius. Só devolve tags quando tem certeza de que
 * achou a faixa certa; caso contrário devolve null e o arquivo fica exatamente como foi baixado.
 */
class AutoTagger {
    data class Outcome(
        val tags: EditableTags,
        /** Usar a imagem do vídeo como capa (quando a faixa achada não trouxe capa de álbum). */
        val useThumbnail: Boolean,
    )

    suspend fun resolve(info: SourceInfo?, fallbackTitle: String): Outcome? {
        val rawTitle = info?.title?.ifBlank { fallbackTitle } ?: fallbackTitle
        val parsed = TitleParser.parse(rawTitle)
        val realMetadata = info?.hasRealMetadata == true
        val artistHint = info?.mainArtist.orEmpty().ifBlank { parsed.artist.orEmpty() }
        val titleHint = info?.track.orEmpty().ifBlank { parsed.title }.ifBlank { fallbackTitle }
        if (titleHint.isBlank()) return null

        // Metadados reais do YouTube Music já são confiáveis; sem eles, exige uma correspondência forte.
        val threshold = if (realMetadata) 0.70 else MatchScorer.DEFAULT_THRESHOLD
        val match = searchAndPick(artistHint, titleHint, info?.durationSec ?: 0, threshold) ?: return null
        return Outcome(tagsFrom(match), useThumbnail = match.artworkUrl.isBlank())
    }

    private suspend fun searchAndPick(artist: String, title: String, durationSec: Int, threshold: Double): MetadataResult? {
        val client = createPlatformHttpClient()
        try {
            val repository = MetadataRepository(client)
            var candidates = runCatching { repository.search(listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")) }
                .getOrDefault(emptyList())
            if (candidates.isEmpty() && artist.isNotBlank()) {
                candidates = runCatching { repository.search(title) }.getOrDefault(emptyList())
            }
            val pick = MatchScorer.pick(candidates, title, artist.ifBlank { null }, durationSec)
            return pick?.takeIf { MatchScorer.isConfident(it, threshold) }?.result
        } finally {
            runCatching { client.close() }
        }
    }

    private fun tagsFrom(m: MetadataResult) = EditableTags(
        title = m.title,
        artist = m.artist,
        album = m.album,
        albumArtist = m.albumArtist,
        trackNumber = m.trackNumber?.toString().orEmpty(),
        trackTotal = m.trackTotal?.toString().orEmpty(),
        discNumber = m.discNumber?.toString().orEmpty(),
        discTotal = m.discTotal?.toString().orEmpty(),
        year = m.year,
        genre = m.genre,
        isrc = m.isrc,
        artworkUrl = m.artworkUrl,
    )
}
