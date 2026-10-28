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
import dev.truetagger.data.FlatPlaylist
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.SourceInfo
import dev.truetagger.data.YtDlpJson
import dev.truetagger.model.EditableTags
import dev.truetagger.model.ImportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URL
import java.util.Locale

/** Resultado de um download: nome salvo em songs/, caminho relativo e se as tags precisam de revisão. */
data class ImportResult(val fileName: String, val relPath: String, val label: String)

class YoutubeImportManager(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)

    private fun initEngines() {
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
    }

    /** Lista o conteúdo de uma playlist (YouTube ou SoundCloud). Devolve null se o link não é uma playlist ou falhou. */
    suspend fun listPlaylist(url: String, onStatus: (ImportStatus) -> Unit): FlatPlaylist? = withContext(Dispatchers.IO) {
        initEngines()
        updateEngineIfNeeded(url, onStatus)
        onStatus(ImportStatus(url, "Lendo a playlist…", 0))
        runCatching {
            val request = YoutubeDLRequest(url).apply {
                addOption("-J")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            YtDlpJson.parseListing(YoutubeDL.getInstance().execute(request).out)
        }.getOrNull()
    }

    suspend fun downloadAudio(
        url: String,
        destinationTree: Uri,
        onStatus: (ImportStatus) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        onStatus(ImportStatus(url, "Preparando download…", 0))
        initEngines()

        updateEngineIfNeeded(url, onStatus)
        var info = fetchSourceInfo(url, onStatus)
        val initialTitle = cleanFeaturingText(info?.title.orEmpty())
        if (initialTitle.isNotBlank()) {
            onStatus(ImportStatus(url, "Preparando áudio…", 0, mediaTitle = initialTitle))
        }

        val firstAttempt = runCatching {
            executeDownload(url, destinationTree, onStatus, retry = false, info = info)
        }
        if (firstAttempt.isSuccess) return@withContext firstAttempt.getOrThrow()

        onStatus(
            ImportStatus(
                url,
                "Atualizando o baixador…",
                0,
                mediaTitle = cleanFeaturingText(info?.title.orEmpty()),
            )
        )
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._NIGHTLY)
            rememberEngineUpdate()
        }
        info = fetchSourceInfo(url, onStatus) ?: info
        executeDownload(url, destinationTree, onStatus, retry = true, info = info)
    }

    private fun fetchSourceInfo(url: String, onStatus: (ImportStatus) -> Unit): SourceInfo? {
        onStatus(ImportStatus(url, "Lendo informações…", 0))
        val raw = runCatching {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
            }
            YtDlpJson.parseInfo(YoutubeDL.getInstance().execute(request).out)
        }.getOrNull()
        val info = raw ?: runCatching { YoutubeDL.getInstance().getInfo(url) }.getOrNull()?.let {
            SourceInfo(
                title = it.title.orEmpty(),
                uploader = it.uploader.orEmpty(),
                uploadDate = it.uploadDate.orEmpty(),
                thumbnail = it.thumbnail.orEmpty(),
                durationSec = it.duration,
            )
        }
        if (info?.title?.isNotBlank() == true) {
            onStatus(ImportStatus(url, "Informações encontradas.", 0, mediaTitle = cleanFeaturingText(info.title)))
        }
        return info
    }

    private suspend fun executeDownload(
        url: String,
        destinationTree: Uri,
        onStatus: (ImportStatus) -> Unit,
        retry: Boolean,
        info: SourceInfo?,
    ): ImportResult {
        val workDir = File(context.cacheDir, if (retry) "youtube-import-retry" else "youtube-import").apply {
            deleteRecursively()
            mkdirs()
        }
        val mediaTitle = cleanFeaturingText(info?.title.orEmpty())

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
            addOption("-o", File(workDir, "%(autonumber)s.%(ext)s").absolutePath)
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

        var label = mediaTitle
        var baseName = mediaTitle.ifBlank { output.nameWithoutExtension }
        val autoTag = prefs.getBoolean(ShareContract.PREF_AUTO_TAG, true)
        val embedThumbnail = prefs.getBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, true)

        if (autoTag) {
            onStatus(ImportStatus(url, "Procurando as tags…", 100, mediaTitle = mediaTitle))
            val outcome = AutoTagger().resolve(
                info = info,
                fallbackTitle = mediaTitle.ifBlank { output.nameWithoutExtension },
            )
            // Sem uma correspondência confiável o arquivo fica exatamente como o yt-dlp entregou.
            if (outcome != null) {
                var tags = outcome.tags
                if (outcome.useThumbnail && embedThumbnail) {
                    downloadThumbnail(info?.thumbnail.orEmpty())?.let { tags = tags.copy(artworkBytes = it) }
                }
                runCatching { FileTagWriter.write(context, output, tags) }
                label = listOf(tags.artist, tags.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { mediaTitle }
                if (tags.artist.isNotBlank() && tags.title.isNotBlank()) baseName = label
            }
        } else if (info != null) {
            onStatus(ImportStatus(url, "Aplicando informações do vídeo…", 100, mediaTitle = mediaTitle))
            runCatching { applyBasicInfo(output, info, embedThumbnail) }
        }

        val cleanOutput = renamedOutputFile(output, baseName)
        onStatus(ImportStatus(url, "Salvando arquivo…", 100, mediaTitle = mediaTitle))
        val savedName = copyIntoTree(cleanOutput, destinationTree)
        val relPath = "${MigrationPlanner.SONGS_DIR}/$savedName"
        scanPrimaryTreeFile(destinationTree, relPath)
        output.delete()
        workDir.deleteRecursively()
        return ImportResult(savedName, relPath, label.ifBlank { savedName })
    }

    /** Baixa a imagem do vídeo e devolve em JPEG, ou null se não der. */
    private fun downloadThumbnail(url: String): ByteArray? {
        if (url.isBlank()) return null
        return runCatching {
            val bytes = URL(url).openStream().use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    /** Tags simples (quando o ajuste automático está desligado): título, canal, ano e capa do vídeo. */
    private fun applyBasicInfo(file: File, info: SourceInfo, embedThumbnail: Boolean) {
        val tags = EditableTags(
            title = cleanFeaturingText(info.title),
            artist = info.uploader,
            album = "",
            albumArtist = info.uploader,
            trackNumber = "", trackTotal = "", discNumber = "", discTotal = "",
            year = info.uploadDate.take(4),
            genre = "", isrc = "",
            artworkBytes = if (embedThumbnail) downloadThumbnail(info.thumbnail) else null,
        )
        FileTagWriter.write(context, file, tags)
    }

    private fun renamedOutputFile(source: File, title: String): File {
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

        onStatus(ImportStatus(url, "Atualizando o baixador…", 0))
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
        if (line.contains("Sleeping for", ignoreCase = true)) return "Conectando…"
        if (line.contains("Downloading webpage", ignoreCase = true)) return "Lendo informações…"
        if (line.contains("player API", ignoreCase = true)) return "Preparando áudio…"
        if (line.contains("ExtractAudio", ignoreCase = true)) return "Convertendo para MP3..."
        if (line.contains("Destination", ignoreCase = true)) return "Preparando arquivo…"
        if (line.contains("Deleting original file", ignoreCase = true)) return "Finalizando…"
        if (line.startsWith("[download]", ignoreCase = true)) return "Baixando áudio$etaText"
        if (line.startsWith("[info]", ignoreCase = true)) return "Preparando áudio…"
        if (line.startsWith("WARNING:", ignoreCase = true)) return "Preparando áudio…"
        return "Processando vídeo…"
    }

    /** Salva em `songs/` (criada se faltar). Devolve o nome final, já que o SAF renomeia duplicatas. */
    private fun copyIntoTree(source: File, treeUri: Uri): String {
        val resolver = context.contentResolver
        val tree = SafTree(context, treeUri)
        val parentDocId = tree.ensureDir(tree.rootDocId, MigrationPlanner.SONGS_DIR) ?: tree.rootDocId
        val destinationId = tree.create(parentDocId, "audio/mpeg", source.name)
            ?: error("Não consegui salvar o arquivo na pasta de músicas.")
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
