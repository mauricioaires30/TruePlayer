package dev.truetagger.android

import dev.truetagger.data.SourceInfo
import dev.truetagger.data.TitleParser
import dev.truetagger.metadata.MatchScorer
import dev.truetagger.metadata.MetadataRepository
import dev.truetagger.metadata.createPlatformHttpClient
import dev.truetagger.model.EditableTags
import dev.truetagger.model.MetadataResult

/**
 * Decide as tags de uma música recém-baixada. Se achar a faixa com confiança no Apple Music/Deezer,
 * usa os dados dela; senão usa o que o yt-dlp sabe e deixa a música na lista de revisão.
 */
class AutoTagger {
    data class Outcome(
        val tags: EditableTags,
        val comment: String,
        /** Usar a imagem do vídeo como capa (quando não veio capa de álbum). */
        val useThumbnail: Boolean,
        val needsReview: Boolean,
    )

    suspend fun resolve(info: SourceInfo?, fallbackTitle: String, geniusToken: String): Outcome {
        val rawTitle = info?.title?.ifBlank { fallbackTitle } ?: fallbackTitle
        val parsed = TitleParser.parse(rawTitle)
        val realMetadata = info?.hasRealMetadata == true
        val artistHint = info?.mainArtist.orEmpty().ifBlank { parsed.artist.orEmpty() }
        val titleHint = info?.track.orEmpty().ifBlank { parsed.title }.ifBlank { fallbackTitle }
        val duration = info?.durationSec ?: 0
        // Metadados reais do YouTube Music valem mais que marcas no título.
        val unreleased = parsed.unreleased && !realMetadata

        if (!unreleased && titleHint.isNotBlank()) {
            val match = searchAndPick(artistHint, titleHint, duration, if (realMetadata) 0.70 else MatchScorer.DEFAULT_THRESHOLD, geniusToken)
            if (match != null) {
                return Outcome(tagsFrom(match), comment = "", useThumbnail = match.artworkUrl.isBlank(), needsReview = false)
            }
        }
        return fallback(info, parsed.version, titleHint, artistHint, unreleased, realMetadata, rawTitle)
    }

    private suspend fun searchAndPick(artist: String, title: String, durationSec: Int, threshold: Double, geniusToken: String): MetadataResult? {
        val client = createPlatformHttpClient()
        try {
            val repository = MetadataRepository(client, geniusToken)
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

    private fun fallback(
        info: SourceInfo?,
        version: String?,
        titleHint: String,
        artistHint: String,
        unreleased: Boolean,
        realMetadata: Boolean,
        rawTitle: String,
    ): Outcome {
        val artist = artistHint.ifBlank { info?.uploader.orEmpty() }
        val title = if (unreleased && version != null) "$titleHint ($version)" else titleHint
        val year = info?.releaseYear.orEmpty().ifBlank { info?.uploadDate.orEmpty().take(4) }
        val album = if (unreleased) "Inéditas" else info?.album.orEmpty()
        val comment = if (unreleased) {
            "Inédita${version?.let { " ($it)" }.orEmpty()} • fonte: ${info?.extractor.orEmpty().ifBlank { "link" }} • título original: $rawTitle"
        } else ""
        val tags = EditableTags(
            title = title, artist = artist, album = album, albumArtist = artist,
            trackNumber = "", trackTotal = "", discNumber = "", discTotal = "",
            year = year, genre = "", isrc = "",
        )
        return Outcome(tags, comment, useThumbnail = true, needsReview = unreleased || !realMetadata)
    }
}
