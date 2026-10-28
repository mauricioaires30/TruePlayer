package dev.truetagger.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.model.ImportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URL
import java.util.Locale

class YoutubeImportManager(private val context: Context) {
    suspend fun downloadAudio(
        url: String,
        destinationTree: Uri,
        onStatus: (ImportStatus) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        onStatus(ImportStatus(url, "Preparando download...", 0))
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)

        updateEngineIfNeeded(url, onStatus)
        var videoInfo = fetchVideoInfo(url, onStatus)
        val initialTitle = cleanFeaturingText(videoInfo?.title.orEmpty())
        if (initialTitle.isNotBlank()) {
            onStatus(ImportStatus(url, "Preparando áudio...", 0, mediaTitle = initialTitle))
        }

        val firstAttempt = runCatching {
            executeDownload(url, destinationTree, onStatus, retry = false, videoInfo = videoInfo)
        }
        if (firstAttempt.isSuccess) return@withContext firstAttempt.getOrThrow()

        onStatus(
            ImportStatus(
                url,
                "Atualizando compatibilidade com o YouTube...",
                0,
                mediaTitle = cleanFeaturingText(videoInfo?.title.orEmpty()),
            )
        )
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._NIGHTLY)
            rememberEngineUpdate()
        }
        videoInfo = fetchVideoInfo(url, onStatus) ?: videoInfo
        executeDownload(url, destinationTree, onStatus, retry = true, videoInfo = videoInfo)
    }

    private fun fetchVideoInfo(url: String, onStatus: (ImportStatus) -> Unit): VideoInfo? {
        onStatus(ImportStatus(url, "Lendo informações do vídeo...", 0))
        val info = runCatching { YoutubeDL.getInstance().getInfo(url) }.getOrNull()
        if (info?.title?.isNotBlank() == true) {
            onStatus(
                ImportStatus(
                    url = url,
                    message = "Informações encontradas.",
                    progress = 0,
                    mediaTitle = cleanFeaturingText(info.title.orEmpty()),
                )
            )
        }
        return info
    }

    private fun executeDownload(
        url: String,
        destinationTree: Uri,
        onStatus: (ImportStatus) -> Unit,
        retry: Boolean,
        videoInfo: VideoInfo?,
    ): String {
        val workDir = File(context.cacheDir, if (retry) "youtube-import-retry" else "youtube-import").apply {
            deleteRecursively()
            mkdirs()
        }
        val mediaTitle = cleanFeaturingText(videoInfo?.title.orEmpty())

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("--retries", "2")
            addOption("--fragment-retries", "2")
            addOption("-f", "bestaudio/best")
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            addOption("-o", File(workDir, "%(title)s.%(ext)s").absolutePath)
        }

        val processId = "TrueTaggerImport-${System.currentTimeMillis()}"
        YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            val percent = progress.toInt().coerceIn(0, 100)
            onStatus(
                ImportStatus(
                    url = url,
                    message = normalizeProgressLine(line, eta),
                    progress = percent,
                    mediaTitle = mediaTitle,
                )
            )
        }

        val output = workDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?: error("O MP3 final não foi encontrado.")

        if (videoInfo != null) {
            onStatus(ImportStatus(url, "Aplicando informações do vídeo...", 100, mediaTitle = mediaTitle))
            runCatching { applyVideoInfo(output, videoInfo) }
        }

        val cleanOutput = cleanedOutputFile(output, mediaTitle)
        onStatus(ImportStatus(url, "Salvando arquivo...", 100, mediaTitle = mediaTitle))
        val savedName = copyIntoTree(cleanOutput, destinationTree)
        scanPrimaryTreeFile(destinationTree, "${MigrationPlanner.SONGS_DIR}/$savedName")
        val finalName = savedName
        output.delete()
        workDir.deleteRecursively()
        return finalName
    }

    private fun applyVideoInfo(file: File, info: VideoInfo) {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault

        fun set(key: FieldKey, value: String?) {
            if (value.isNullOrBlank()) return
            runCatching { tag.deleteField(key) }
            tag.setField(key, value.trim())
        }

        set(FieldKey.TITLE, cleanFeaturingText(info.title.orEmpty()))
        set(FieldKey.ARTIST, info.uploader)
        set(FieldKey.ALBUM_ARTIST, info.uploader)
        set(FieldKey.YEAR, info.uploadDate?.take(4))

        val prefs = context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, true)) {
            val thumbnail = info.thumbnail
            if (!thumbnail.isNullOrBlank()) {
                runCatching {
                    val bytes = URL(thumbnail).openStream().use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        val coverFile = File.createTempFile("truetagger-youtube-cover-", ".jpg", context.cacheDir)
                        try {
                            coverFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                            tag.deleteArtworkField()
                            tag.setField(ArtworkFactory.createArtworkFromFile(coverFile))
                        } finally {
                            coverFile.delete()
                            bitmap.recycle()
                        }
                    }
                }
            }
        }

        audio.commit()
    }

    private fun cleanedOutputFile(source: File, title: String): File {
        val baseTitle = sanitizeFileName(cleanFeaturingText(title).ifBlank { source.nameWithoutExtension })
        val target = File(source.parentFile, "$baseTitle.mp3")
        if (target.absolutePath == source.absolutePath) return source
        if (target.exists()) target.delete()
        return if (source.renameTo(target)) target else source
    }

    private fun sanitizeFileName(value: String): String {
        val cleaned = value
            .replace(Regex("[\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "audio" }
    }

    private fun cleanFeaturingText(value: String): String {
        var result = value
        val featuring = Regex("(?i)\\s*\\(\\s*(?:ft|feat|featuring)\\.?\\s+[^)]*\\)", RegexOption.MULTILINE)
        repeat(3) { result = result.replace(featuring, " ") }
        return result.replace(Regex("\\s+"), " ").trim()
    }

    private fun updateEngineIfNeeded(url: String, onStatus: (ImportStatus) -> Unit) {
        val prefs = context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ShareContract.PREF_AUTO_UPDATE_DOWNLOADER, true)) return

        val last = prefs.getLong(PREF_ENGINE_UPDATE, 0L)
        val now = System.currentTimeMillis()
        if (now - last < ENGINE_UPDATE_INTERVAL_MS) return

        onStatus(ImportStatus(url, "Atualizando compatibilidade com o YouTube...", 0))
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            rememberEngineUpdate()
        }
    }

    private fun rememberEngineUpdate() {
        context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_ENGINE_UPDATE, System.currentTimeMillis())
            .apply()
    }

    private fun normalizeProgressLine(raw: String, eta: Long): String {
        val line = raw.trim()
        val etaText = if (eta > 0) " • ${eta}s restantes" else ""
        if (line.isBlank()) return "Baixando áudio$etaText"
        if (line.contains("Sleeping for", ignoreCase = true)) return "Conectando ao YouTube..."
        if (line.contains("Downloading webpage", ignoreCase = true)) return "Lendo informações do vídeo..."
        if (line.contains("player API", ignoreCase = true)) return "Preparando áudio..."
        if (line.contains("ExtractAudio", ignoreCase = true)) return "Convertendo para MP3..."
        if (line.contains("Destination", ignoreCase = true)) return "Preparando arquivo..."
        if (line.contains("Deleting original file", ignoreCase = true)) return "Finalizando..."
        if (line.startsWith("[download]", ignoreCase = true)) return "Baixando áudio$etaText"
        if (line.startsWith("[info]", ignoreCase = true)) return "Preparando áudio..."
        if (line.startsWith("WARNING:", ignoreCase = true)) return "Preparando áudio..."
        return "Processando vídeo..."
    }

    /** Salva em `songs/` (criada se faltar). Devolve o nome final, já que o SAF renomeia duplicatas. */
    private fun copyIntoTree(source: File, treeUri: Uri): String {
        val resolver = context.contentResolver
        val tree = SafTree(context, treeUri)
        val parentDocId = tree.ensureDir(tree.rootDocId, MigrationPlanner.SONGS_DIR) ?: tree.rootDocId
        val destinationId = tree.create(parentDocId, "audio/mpeg", source.name)
            ?: error("Não foi possível criar o arquivo na pasta escolhida.")
        resolver.openOutputStream(tree.docUri(destinationId), "w")!!.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        return tree.children(parentDocId).firstOrNull { it.docId == destinationId }?.name ?: source.name
    }

    private fun scanPrimaryTreeFile(treeUri: Uri, fileName: String) {
        runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(treeUri)
            if (!treeId.startsWith("primary:")) return
            val relative = treeId.removePrefix("primary:").trim('/')
            val base = Environment.getExternalStorageDirectory()
            val file = if (relative.isBlank()) File(base, fileName) else File(File(base, relative), fileName)
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("audio/mpeg"), null)
        }
    }

    companion object {
        private const val PREF_ENGINE_UPDATE = "yt_dlp_last_update"
        private const val ENGINE_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
