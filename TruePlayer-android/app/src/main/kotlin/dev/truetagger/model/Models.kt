package dev.truetagger.model

data class AudioTrack(
    val id: String,
    /**
     * Caminho do arquivo relativo à pasta de músicas escolhida (ex.: "songs/Faixa.mp3").
     * Diferente de [id] (content URI do MediaStore, que muda de celular para celular),
     * é a chave portátil usada por playlists e estatísticas.
     */
    val relPath: String = "",
    val displayName: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val trackNumber: String = "",
    val trackTotal: String = "",
    val discNumber: String = "",
    val discTotal: String = "",
    val year: String = "",
    val genre: String = "",
    val isrc: String = "",
    val durationMs: Long = 0L,
    val modifiedAtSeconds: Long = 0L,
    val sizeBytes: Long = 0L,
    val location: String = "",
    val artworkBytes: ByteArray? = null,
)

data class EditableTags(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val trackNumber: String,
    val trackTotal: String,
    val discNumber: String,
    val discTotal: String,
    val year: String,
    val genre: String,
    val isrc: String,
    val artworkBytes: ByteArray? = null,
    val artworkUrl: String = "",
)

data class MetadataResult(
    val source: String,
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = artist,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val releaseDate: String = "",
    val genre: String = "",
    val isrc: String = "",
    val artworkUrl: String = "",
    val durationSec: Int = 0,
) {
    val year: String get() = releaseDate.take(4)
}

data class TagWriteResult(
    val success: Boolean,
    val message: String,
    val updatedTrack: AudioTrack? = null,
)

data class ImportStatus(
    val url: String,
    val message: String,
    val progress: Int? = null,
    val completed: Boolean = false,
    val failed: Boolean = false,
    val mediaTitle: String = "",
)

data class AppSettings(
    val autoRefreshLibrary: Boolean = true,
    val embedYoutubeThumbnail: Boolean = true,
    val autoUpdateDownloader: Boolean = true,
    val geniusAccessToken: String = "",
    val autoTag: Boolean = true,
)

/** Uma permissão que o app precisa, para o checklist de Configurações. */
data class PermissionItem(val label: String, val granted: Boolean, val onGrant: () -> Unit)
