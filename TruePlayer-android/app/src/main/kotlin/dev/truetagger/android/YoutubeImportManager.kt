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
import dev.truetagger.data.MatchSource
import dev.truetagger.data.SearchHit
import dev.truetagger.data.TrackHint
import dev.truetagger.data.TrackMatcher
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.SourceInfo
import dev.truetagger.data.YtDlpJson
import dev.truetagger.model.EditableTags
import dev.truetagger.model.ImportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.net.URL
import java.util.Locale

/** Resultado de um download: nome salvo em songs/, caminho relativo e se as tags precisam de revisão. */
data class ImportResult(val fileName: String, val relPath: String, val label: String)

/**
 * Onde a música do Spotify foi achada. [infoJson] (quando veio da busca detalhada do YouTube Music) já traz os
 * formatos de áudio: o download começa direto dele, sem o yt-dlp abrir a página da música outra vez.
 */
data class FoundTrack(val url: String, val source: MatchSource, val infoJson: String? = null)

class YoutubeImportManager(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)

    /** Vários downloads correm ao mesmo tempo: preparar o motor (descompactar o Python na 1ª vez) é feito por um só. */
    private fun initEngines() = synchronized(ENGINE_LOCK) {
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
    }

    /** Pastas de trabalho que sobraram de um download interrompido (o app foi fechado no meio). */
    fun cleanWorkDirs() {
        context.cacheDir.listFiles { f -> f.isDirectory && (f.name.startsWith("dl-") || f.name.startsWith("youtube-import")) }
            ?.forEach { it.deleteRecursively() }
    }

    /** Lista o conteúdo de uma playlist (YouTube ou SoundCloud). Devolve null se o link não é uma playlist ou falhou. */
    suspend fun listPlaylist(url: String, processId: String, onStatus: (ImportStatus) -> Unit): FlatPlaylist? = withContext(Dispatchers.IO) {
        initEngines()
        updateEngineIfNeeded(url, onStatus)
        onStatus(ImportStatus(url, t(R.string.dl_lendo_a_playlist_2), 0))
        runCatching {
            val request = YoutubeDLRequest(url).apply {
                addOption("-J")
                addOption("--flat-playlist")
                addOption("--no-warnings")
            }
            YtDlpJson.parseListing(YoutubeDL.getInstance().execute(request, processId).out)
        }.getOrNull()
    }

    /**
     * [processId] identifica o yt-dlp em execução: quem cancela chama `YoutubeDL.destroyProcessById(processId)` e
     * cancela a corrotina; os pontos de checagem abaixo fazem a corrotina parar sem tentar de novo.
     */
    suspend fun downloadAudio(
        url: String,
        destinationTree: Uri,
        processId: String,
        hint: TrackHint? = null,
        infoJson: String? = null,
        onStatus: (ImportStatus) -> Unit,
    ): ImportResult = withContext(Dispatchers.IO) {
        onStatus(ImportStatus(url, t(R.string.dl_preparando_download_2), 0, mediaTitle = hint?.title.orEmpty()))
        initEngines()
        updateEngineIfNeeded(url, onStatus)

        val firstAttempt = runCatching {
            executeDownload(url, destinationTree, processId, onStatus, retry = false, hint = hint, infoJson = infoJson)
        }
        if (firstAttempt.isSuccess) return@withContext firstAttempt.getOrThrow()
        // Cancelado pelo usuário: não atualiza o baixador nem tenta de novo.
        currentCoroutineContext().ensureActive()

        // O YouTube muda e o yt-dlp antigo para de funcionar: atualiza (no máximo uma vez a cada 6 h) e tenta de novo,
        // agora lendo a página da música (os endereços guardados da busca podem ter sido o problema).
        val recentlyUpdated = System.currentTimeMillis() - prefs.getLong(PREF_ENGINE_UPDATE, 0L) < RETRY_UPDATE_INTERVAL_MS
        if (!recentlyUpdated) {
            onStatus(ImportStatus(url, t(R.string.dl_atualizando_o_baixador), 0, mediaTitle = hint?.title.orEmpty()))
            synchronized(ENGINE_LOCK) {
                runCatching {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._NIGHTLY)
                    rememberEngineUpdate()
                }
            }
        }
        executeDownload(url, destinationTree, processId, onStatus, retry = true, hint = hint, infoJson = null)
    }

    /**
     * Baixa e salva a música com uma única execução do yt-dlp: ele lê a página (ou usa [infoJson] pronto), imprime as
     * informações (`-j`) e baixa na sequência. O áudio vem como está no site (M4A/AAC do YouTube, MP3 do SoundCloud),
     * sem converter: a conversão para MP3 no celular era a parte mais lenta e ainda perdia qualidade. Só um formato
     * diferente desses dois (raro) é convertido para M4A.
     */
    private suspend fun executeDownload(
        url: String,
        destinationTree: Uri,
        processId: String,
        onStatus: (ImportStatus) -> Unit,
        retry: Boolean,
        hint: TrackHint? = null,
        infoJson: String? = null,
    ): ImportResult {
        // Uma pasta por download: vários correm ao mesmo tempo.
        val workDir = File(context.cacheDir, "dl-" + processId.replace(Regex("[^A-Za-z0-9_-]"), "_") + if (retry) "-retry" else "").apply {
            deleteRecursively()
            mkdirs()
        }
        var liveInfo: SourceInfo? = null
        var mediaTitle = cleanFeaturingText(hint?.title.orEmpty())

        val savedInfo = infoJson?.takeIf { YtDlpJson.hasFormats(it) }?.let { json ->
            File(workDir, "info.json").apply { writeText(json) }
        }
        val request = (if (savedInfo != null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)).apply {
            if (savedInfo != null) addOption("--load-info-json", savedInfo.absolutePath)
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
            addOption("--retries", "3")
            addOption("--fragment-retries", "3")
            // Formatos em pedaços (HLS/DASH) baixam 4 partes por vez.
            addOption("--concurrent-fragments", "4")
            addOption("-f", "ba[ext=m4a]/ba[ext=mp3]/ba/b")
            addOption("-x")
            // MP3 e M4A ficam como vieram (sem recodificar); qualquer outro vira M4A.
            addOption("--audio-format", "mp3>mp3/m4a>m4a/m4a")
            addOption("--audio-quality", "0")
            addOption("--embed-metadata")
            // Informações e download na mesma execução (antes eram duas: uma só para ler o título).
            addOption("-j")
            addOption("--no-simulate")
            addOption("--progress")
            addOption("-o", File(workDir, "%(autonumber)s.%(ext)s").absolutePath)
        }

        val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
            if (line.startsWith("{")) {
                // A linha de informações chega antes do download: já dá o nome da música para a notificação.
                YtDlpJson.parseInfo(line)?.let { found ->
                    liveInfo = found
                    if (hint == null && found.title.isNotBlank()) mediaTitle = cleanFeaturingText(found.title)
                }
                onStatus(ImportStatus(url, t(R.string.dl_preparando_audio), 0, mediaTitle = mediaTitle))
                return@execute
            }
            val percent = progress.toInt().coerceIn(0, 100)
            onStatus(ImportStatus(url = url, message = normalizeProgressLine(line, eta), progress = percent, mediaTitle = mediaTitle))
        }

        currentCoroutineContext().ensureActive()
        val info = mergeHint(YtDlpJson.parseInfo(response.out) ?: liveInfo, hint)
        if (mediaTitle.isBlank()) mediaTitle = cleanFeaturingText(info?.title.orEmpty())
        val output = workDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
            ?.maxByOrNull { it.length() }
            ?: error(t(R.string.dl_o_mp3_final_nao_foi))

        var label = mediaTitle
        var baseName = mediaTitle.ifBlank { output.nameWithoutExtension }
        val autoTag = prefs.getBoolean(ShareContract.PREF_AUTO_TAG, true)
        val embedThumbnail = prefs.getBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, true)

        if (autoTag || hint != null) {
            onStatus(ImportStatus(url, t(R.string.dl_procurando_as_tags), 100, mediaTitle = mediaTitle))
            val outcome = if (autoTag) {
                AutoTagger().resolve(
                    info = info,
                    fallbackTitle = mediaTitle.ifBlank { output.nameWithoutExtension },
                )
            } else null
            // Sem uma correspondência confiável o arquivo fica exatamente como o yt-dlp entregou.
            var tags = outcome?.tags
            if (outcome != null && outcome.useThumbnail && embedThumbnail) {
                downloadThumbnail(info?.thumbnail.orEmpty())?.let { tags = tags?.copy(artworkBytes = it) }
            }
            // Música do Spotify: já sabemos o nome certo; se a busca de tags não achou, usa o que o Spotify disse.
            if (hint != null) tags = tagsFromHint(tags, hint, if (embedThumbnail) info?.thumbnail.orEmpty() else "")
            tags?.let { found ->
                runCatching { FileTagWriter.write(context, output, found) }
                label = listOf(found.artist, found.title).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { mediaTitle }
                if (found.artist.isNotBlank() && found.title.isNotBlank()) baseName = label
            }
        } else if (info != null) {
            onStatus(ImportStatus(url, t(R.string.dl_aplicando_informacoes_do_video), 100, mediaTitle = mediaTitle))
            runCatching { applyBasicInfo(output, info, embedThumbnail) }
        }

        val cleanOutput = renamedOutputFile(output, baseName)
        onStatus(ImportStatus(url, t(R.string.dl_salvando_arquivo), 100, mediaTitle = mediaTitle))
        val savedName = copyIntoTree(cleanOutput, destinationTree)
        val relPath = "${MigrationPlanner.SONGS_DIR}/$savedName"
        scanPrimaryTreeFile(destinationTree, relPath)
        workDir.deleteRecursively()
        return ImportResult(savedName, relPath, label.ifBlank { savedName })
    }

    /** Dados do Spotify por cima do que o yt-dlp e a busca de tags acharam: nome, artistas e, em álbuns, álbum e faixa. */
    private fun tagsFromHint(found: EditableTags?, hint: TrackHint, thumbnailUrl: String): EditableTags {
        val base = found ?: EditableTags(
            title = hint.title,
            artist = hint.artistLine,
            album = "",
            albumArtist = "",
            trackNumber = "",
            trackTotal = "",
            discNumber = "",
            discTotal = "",
            year = hint.year,
            genre = "",
            isrc = "",
            artworkBytes = downloadThumbnail(thumbnailUrl),
        )
        if (hint.album.isBlank()) return base
        return base.copy(
            album = hint.album,
            albumArtist = hint.albumArtist.ifBlank { base.albumArtist },
            trackNumber = hint.trackNumber?.toString().orEmpty(),
            trackTotal = hint.trackTotal?.toString().orEmpty(),
        )
    }

    /** O que o Spotify sabe da música passa a ser o "metadado real" que a busca de tags usa. */
    private fun mergeHint(info: SourceInfo?, hint: TrackHint?): SourceInfo? {
        if (hint == null) return info
        val source = info ?: SourceInfo()
        return source.copy(
            title = hint.title,
            artist = hint.mainArtist,
            track = hint.title,
            album = hint.album,
            durationSec = hint.durationSec.takeIf { it > 0 } ?: source.durationSec,
            releaseYear = hint.year.ifBlank { source.releaseYear },
            extractor = "spotify",
        )
    }

    /**
     * Procura a música do Spotify (o áudio de lá tem DRM) em três lugares, nesta ordem: catálogo do YouTube Music,
     * YouTube e SoundCloud. Só aceita resultado com nome, artista e duração compatíveis ([TrackMatcher]).
     */
    suspend fun findForHint(hint: TrackHint, processId: String, onStatus: (ImportStatus) -> Unit): FoundTrack? = withContext(Dispatchers.IO) {
        initEngines()
        val query = TrackMatcher.query(hint)
        val url = "spotify:${hint.title}"
        onStatus(ImportStatus(url, t(R.string.dl_procurando_a_musica), 0))

        // 1) YouTube Music: a busca rápida não traz duração nem artista, então pede os detalhes dos 3 primeiros
        //    resultados (o yt-dlp lê os três no mesmo processo).
        val musicSearch = "https://music.youtube.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}#songs"
        val detailed = searchWithInfo(musicSearch, processId, limit = 3, detailed = true)
        TrackMatcher.best(hint, detailed.map { it.first }, MatchSource.YT_MUSIC)?.let { best ->
            val json = detailed.firstOrNull { it.first == best }?.second
            return@withContext FoundTrack(best.url, MatchSource.YT_MUSIC, json)
        }
        // 2) YouTube e 3) SoundCloud: a busca rápida já traz duração e canal.
        for ((prefix, source) in listOf("ytsearch6:" to MatchSource.YOUTUBE, "scsearch6:" to MatchSource.SOUNDCLOUD)) {
            currentCoroutineContext().ensureActive()
            TrackMatcher.best(hint, search(prefix + query, processId, limit = 6), source)
                ?.let { return@withContext FoundTrack(it.url, source) }
        }
        null
    }

    private fun search(target: String, processId: String, limit: Int): List<SearchHit> =
        searchWithInfo(target, processId, limit, detailed = false).map { it.first }

    private fun searchWithInfo(target: String, processId: String, limit: Int, detailed: Boolean): List<Pair<SearchHit, String>> = runCatching {
        val request = YoutubeDLRequest(target).apply {
            addOption("-J")
            if (!detailed) addOption("--flat-playlist")
            addOption("--no-warnings")
            addOption("--playlist-end", limit.toString())
        }
        YtDlpJson.parseSearchWithInfo(YoutubeDL.getInstance().execute(request, processId).out)
    }.getOrDefault(emptyList())

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
        val target = File(source.parentFile, "$baseTitle.${source.extension.lowercase(Locale.ROOT)}")
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

    private fun updateEngineIfNeeded(url: String, onStatus: (ImportStatus) -> Unit): Unit = synchronized(ENGINE_LOCK) {
        val prefs = context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ShareContract.PREF_AUTO_UPDATE_DOWNLOADER, true)) return

        val last = prefs.getLong(PREF_ENGINE_UPDATE, 0L)
        val now = System.currentTimeMillis()
        if (now - last < ENGINE_UPDATE_INTERVAL_MS) return

        onStatus(ImportStatus(url, t(R.string.dl_atualizando_o_baixador), 0))
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            rememberEngineUpdate()
        }
        Unit
    }

    private fun rememberEngineUpdate() {
        context.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREF_ENGINE_UPDATE, System.currentTimeMillis())
            .apply()
    }

    private fun normalizeProgressLine(raw: String, eta: Long): String {
        val line = raw.trim()
        val etaText = if (eta > 0) t(R.string.dl_s_restantes, eta) else ""
        if (line.isBlank()) return t(R.string.dl_baixando_audio, etaText)
        if (line.contains("Sleeping for", ignoreCase = true)) return t(R.string.dl_conectando)
        if (line.contains("Downloading webpage", ignoreCase = true)) return t(R.string.dl_lendo_informacoes)
        if (line.contains("player API", ignoreCase = true)) return t(R.string.dl_preparando_audio)
        if (line.contains("ExtractAudio", ignoreCase = true)) return t(R.string.dl_finalizando)
        if (line.contains("Destination", ignoreCase = true)) return t(R.string.dl_preparando_arquivo)
        if (line.contains("Deleting original file", ignoreCase = true)) return t(R.string.dl_finalizando)
        if (line.startsWith("[download]", ignoreCase = true)) return t(R.string.dl_baixando_audio, etaText)
        if (line.startsWith("[info]", ignoreCase = true)) return t(R.string.dl_preparando_audio)
        if (line.startsWith("WARNING:", ignoreCase = true)) return t(R.string.dl_preparando_audio)
        return t(R.string.dl_processando_video)
    }

    /** Salva em `songs/` (criada se faltar). Devolve o nome final, já que o SAF renomeia duplicatas. */
    private fun copyIntoTree(source: File, treeUri: Uri): String {
        val resolver = context.contentResolver
        val tree = SafTree(context, treeUri)
        val parentDocId = tree.ensureDir(tree.rootDocId, MigrationPlanner.SONGS_DIR) ?: tree.rootDocId
        // O tipo segue a extensão (MP3, M4A...), do mesmo jeito que o Android lê o nome do arquivo.
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase(Locale.ROOT)) ?: "audio/mpeg"
        val destinationId = tree.create(parentDocId, mime, source.name)
            ?: error(t(R.string.dl_nao_consegui_salvar_o_arquivo))
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
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        }
    }

    companion object {
        private const val PREF_ENGINE_UPDATE = "yt_dlp_last_update"
        private const val ENGINE_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
        private const val RETRY_UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private val ENGINE_LOCK = Any()
        private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "opus", "ogg", "flac", "wav")
    }
}
