package dev.truetagger.android

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.EditableTags
import dev.truetagger.model.MetadataResult
import dev.truetagger.model.TagWriteResult
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URL

class AndroidAudioService(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun scanLibrary(): List<AudioTrack> {
        val tracks = mutableListOf<AudioTrack>()
        val collections = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).map { MediaStore.Audio.Media.getContentUri(it) }
        } else {
            listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        collections.forEach { collection ->
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.SIZE,
            )
            resolver.query(
                collection,
                projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val name = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val track = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val year = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
                val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val modified = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
                val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val itemUri = ContentUris.withAppendedId(collection, cursor.getLong(id))
                    val trackValue = cursor.getInt(track).takeIf { it > 0 }?.let { raw ->
                        if (raw >= 1000) (raw % 1000).toString() else raw.toString()
                    }.orEmpty()
                    tracks += AudioTrack(
                        id = itemUri.toString(),
                        displayName = cursor.getString(name).orEmpty().ifBlank { "audio" },
                        title = cursor.getString(title).orEmpty(),
                        artist = cursor.getString(artist).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(),
                        album = cursor.getString(album).orEmpty().takeUnless { it == "<unknown>" }.orEmpty(),
                        trackNumber = trackValue,
                        year = cursor.getInt(year).takeIf { it > 0 }?.toString().orEmpty(),
                        durationMs = cursor.getLong(duration),
                        modifiedAtSeconds = cursor.getLong(modified),
                        sizeBytes = cursor.getLong(size),
                        location = collection.authority.orEmpty(),
                    )
                }
            }
        }
        return tracks.distinctBy { it.id }.sortedBy { it.title.ifBlank { it.displayName }.lowercase() }
    }

    fun read(uri: Uri): AudioTrack {
        val displayName = queryDisplayName(uri) ?: "audio.mp3"
        val cacheFile = copyUriToCache(uri, displayName)
        return try {
            val audio = AudioFileIO.read(cacheFile)
            val tag = audio.tag
            fun field(key: FieldKey) = runCatching { tag?.getFirst(key).orEmpty() }.getOrDefault("")

            AudioTrack(
                id = uri.toString(),
                displayName = displayName,
                title = field(FieldKey.TITLE),
                artist = field(FieldKey.ARTIST),
                album = field(FieldKey.ALBUM),
                albumArtist = field(FieldKey.ALBUM_ARTIST),
                trackNumber = field(FieldKey.TRACK),
                trackTotal = field(FieldKey.TRACK_TOTAL),
                discNumber = field(FieldKey.DISC_NO),
                discTotal = field(FieldKey.DISC_TOTAL),
                year = normalizeYear(field(FieldKey.YEAR)),
                genre = field(FieldKey.GENRE),
                isrc = field(FieldKey.ISRC),
                durationMs = runCatching { audio.audioHeader.trackLength * 1000L }.getOrDefault(0L),
                artworkBytes = runCatching { tag?.firstArtwork?.binaryData }.getOrNull(),
            )
        } finally {
            cacheFile.delete()
        }
    }

    @Throws(SecurityException::class)
    fun applyOrThrow(track: AudioTrack, metadata: MetadataResult): TagWriteResult = applyEditsOrThrow(
        track,
        EditableTags(
            title = metadata.title,
            artist = metadata.artist,
            album = metadata.album,
            albumArtist = metadata.albumArtist,
            trackNumber = metadata.trackNumber?.toString().orEmpty(),
            trackTotal = metadata.trackTotal?.toString().orEmpty(),
            discNumber = metadata.discNumber?.toString().orEmpty(),
            discTotal = metadata.discTotal?.toString().orEmpty(),
            year = metadata.year,
            genre = metadata.genre,
            isrc = metadata.isrc,
            artworkUrl = metadata.artworkUrl,
        )
    )

    @Throws(SecurityException::class)
    fun applyEditsOrThrow(track: AudioTrack, edits: EditableTags): TagWriteResult {
        val uri = Uri.parse(track.id)
        val cacheFile = copyUriToCache(uri, track.displayName)
        return try {
            val audio = AudioFileIO.read(cacheFile)
            val tag = audio.tagOrCreateAndSetDefault

            fun setOrClear(key: FieldKey, value: String) {
                runCatching { tag.deleteField(key) }
                if (value.isNotBlank()) tag.setField(key, value.trim())
            }

            setOrClear(FieldKey.TITLE, edits.title)
            setOrClear(FieldKey.ARTIST, edits.artist)
            setOrClear(FieldKey.ALBUM, edits.album)
            setOrClear(FieldKey.ALBUM_ARTIST, edits.albumArtist)
            setOrClear(FieldKey.TRACK, edits.trackNumber)
            setOrClear(FieldKey.TRACK_TOTAL, edits.trackTotal)
            setOrClear(FieldKey.DISC_NO, edits.discNumber)
            setOrClear(FieldKey.DISC_TOTAL, edits.discTotal)
            setOrClear(FieldKey.YEAR, normalizeYear(edits.year))
            setOrClear(FieldKey.GENRE, edits.genre)
            setOrClear(FieldKey.ISRC, edits.isrc)

            when {
                edits.artworkBytes != null -> {
                    setArtwork(tag, edits.artworkBytes)
                }
                edits.artworkUrl.isNotBlank() -> {
                    runCatching {
                        val bytes = URL(edits.artworkUrl).openStream().use { it.readBytes() }
                        setArtwork(tag, bytes)
                    }
                }
            }

            audio.commit()
            resolver.openOutputStream(uri, "wt")!!.use { output ->
                cacheFile.inputStream().use { input -> input.copyTo(output) }
            }
            TagWriteResult(true, "Tags salvas.", read(uri))
        } finally {
            cacheFile.delete()
        }
    }

    private fun setArtwork(tag: org.jaudiotagger.tag.Tag, bytes: ByteArray) {
        val cover = File.createTempFile("truetagger-cover-", ".jpg", context.cacheDir)
        try {
            cover.writeBytes(bytes)
            tag.deleteArtworkField()
            tag.setField(ArtworkFactory.createArtworkFromFile(cover))
        } finally {
            cover.delete()
        }
    }

    private fun normalizeYear(value: String): String {
        val digits = value.filter(Char::isDigit)
        return when {
            digits.length >= 4 -> digits.take(4)
            else -> value.trim()
        }
    }

    private fun copyUriToCache(uri: Uri, displayName: String): File {
        val extension = displayName.substringAfterLast('.', "mp3")
        val file = File.createTempFile("truetagger-", ".${extension}", context.cacheDir)
        resolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return file
    }

    private fun queryDisplayName(uri: Uri): String? {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }
}
