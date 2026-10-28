package dev.truetagger.android

import android.content.Context
import dev.truetagger.model.EditableTags
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URL

/** Grava tags direto em um arquivo local (usado nos downloads, antes de ele entrar na pasta de músicas). */
object FileTagWriter {
    fun write(context: Context, file: File, tags: EditableTags, comment: String = "") {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault

        fun set(key: FieldKey, value: String) {
            runCatching { tag.deleteField(key) }
            if (value.isNotBlank()) tag.setField(key, value.trim())
        }

        set(FieldKey.TITLE, tags.title)
        set(FieldKey.ARTIST, tags.artist)
        set(FieldKey.ALBUM, tags.album)
        set(FieldKey.ALBUM_ARTIST, tags.albumArtist)
        set(FieldKey.TRACK, tags.trackNumber)
        set(FieldKey.TRACK_TOTAL, tags.trackTotal)
        set(FieldKey.DISC_NO, tags.discNumber)
        set(FieldKey.DISC_TOTAL, tags.discTotal)
        set(FieldKey.YEAR, tags.year.filter(Char::isDigit).take(4))
        set(FieldKey.GENRE, tags.genre)
        set(FieldKey.ISRC, tags.isrc)
        if (comment.isNotBlank()) set(FieldKey.COMMENT, comment)

        val artwork = tags.artworkBytes ?: tags.artworkUrl.takeIf { it.isNotBlank() }?.let { url ->
            runCatching { URL(url).openStream().use { it.readBytes() } }.getOrNull()
        }
        if (artwork != null && artwork.isNotEmpty()) {
            runCatching {
                val cover = File.createTempFile("truetagger-cover-", ".jpg", context.cacheDir)
                try {
                    cover.writeBytes(artwork)
                    tag.deleteArtworkField()
                    tag.setField(ArtworkFactory.createArtworkFromFile(cover))
                } finally {
                    cover.delete()
                }
            }
        }
        audio.commit()
    }
}
