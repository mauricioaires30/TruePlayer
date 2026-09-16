package dev.truetagger.android

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.EditableTags
import dev.truetagger.model.ImportStatus
import dev.truetagger.model.TagWriteResult
import dev.truetagger.ui.TrueTaggerPlayerApp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var audioService: AndroidAudioService
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences(ShareContract.PREFS, MODE_PRIVATE) }

    private var tracks by mutableStateOf<List<AudioTrack>>(emptyList())
    private var selectedTrack by mutableStateOf<AudioTrack?>(null)
    private var libraryLoading by mutableStateOf(false)
    private var downloadFolderLabel by mutableStateOf("")
    private var importStatus by mutableStateOf<ImportStatus?>(null)
    private var settings by mutableStateOf(AppSettings())
    private var pendingSharedUrl: String? = null
    private var shouldCloseAfterFolderPick = false
    private var writeContinuation: CancellableContinuation<Boolean>? = null
    private var mediaRefreshJob: Job? = null
    private var lastLibraryTrackIds: Set<String>? = null
    private var lastRefreshAtMs = 0L
    private var refreshInFlight = false
    private lateinit var mediaObserver: ContentObserver

    private companion object {
        /** Intervalo mínimo entre varreduras completas do MediaStore. */
        const val MIN_REFRESH_INTERVAL_MS = 1_500L
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshLibrary()
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString(ShareContract.PREF_DOWNLOAD_TREE, uri.toString()).apply()
        downloadFolderLabel = folderLabel(uri)

        pendingSharedUrl?.let { url ->
            pendingSharedUrl = null
            YoutubeDownloadService.start(this, url)
            importStatus = ImportStatus(url, "Download iniciado em segundo plano.", completed = true)
            if (shouldCloseAfterFolderPick) {
                finish()
                return@registerForActivityResult
            }
        }
    }

    private val writeLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        writeContinuation?.let { continuation ->
            writeContinuation = null
            if (continuation.isActive) continuation.resume(result.resultCode == RESULT_OK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        audioService = AndroidAudioService(this)
        downloadFolderLabel = savedDownloadTree()?.let(::folderLabel).orEmpty()
        settings = loadSettings()
        releaseStalePermissions()
        registerMediaObserver()

        setContent {
            TrueTaggerPlayerApp(
                tracks = tracks,
                selectedTrack = selectedTrack,
                libraryLoading = libraryLoading,
                onSelectTrack = ::selectTrack,
                onBackToLibrary = { selectedTrack = null },
                onRefreshLibrary = ::ensurePermissionThenRefresh,
                onSaveEdits = ::saveEdits,
                settings = settings,
                downloadFolderLabel = downloadFolderLabel,
                onChooseDownloadFolder = { folderPicker.launch(savedDownloadTree()) },
                onReleaseDownloadFolder = ::releaseDownloadFolder,
                onSettingsChange = ::updateSettings,
                appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault(""),
                importStatus = importStatus,
                onDismissImportStatus = { importStatus = null },
            )
        }

        ensurePermissionThenRefresh()
        ensureNotificationPermission()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        writeContinuation?.cancel()
        mediaRefreshJob?.cancel()
        if (::mediaObserver.isInitialized) {
            runCatching { contentResolver.unregisterContentObserver(mediaObserver) }
        }
        if (isFinishing) {
            runCatching { MusicPlaybackService.command(this, MusicPlaybackService.ACTION_STOP) }
        }
        appScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // Antes o app varria o MediaStore inteiro a cada volta para a tela,
        // inclusive ao sair de um diálogo. Agora só revarre se passou tempo
        // suficiente desde a última varredura.
        val now = android.os.SystemClock.elapsedRealtime()
        if (::audioService.isInitialized && hasAudioPermission() &&
            now - lastRefreshAtMs >= MIN_REFRESH_INTERVAL_MS
        ) {
            refreshLibrary()
        }
    }

    private fun registerMediaObserver() {
        mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                scheduleLibraryRefresh()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(this).forEach { volume ->
                contentResolver.registerContentObserver(
                    MediaStore.Audio.Media.getContentUri(volume),
                    true,
                    mediaObserver,
                )
            }
        } else {
            contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver,
            )
        }
    }

    private fun scheduleLibraryRefresh() {
        if (!settings.autoRefreshLibrary || !hasAudioPermission()) return
        mediaRefreshJob?.cancel()
        mediaRefreshJob = appScope.launch {
            delay(500)
            refreshLibrary()
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ShareContract.ACTION_CONFIGURE_SHARE -> {
                val url = intent.getStringExtra(ShareContract.EXTRA_SHARED_URL)
                if (!url.isNullOrBlank()) {
                    pendingSharedUrl = url
                    shouldCloseAfterFolderPick = true
                    importStatus = ImportStatus(url, "Escolha a pasta de download.")
                    folderPicker.launch(savedDownloadTree())
                    setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
                }
            }
            ShareContract.ACTION_OPEN_APP, Intent.ACTION_MAIN, null -> Unit
            Intent.ACTION_SEND -> {
                val url = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                if (url.isNotBlank() && savedDownloadTree() != null) YoutubeDownloadService.start(this, url)
            }
        }
    }

    private fun ensurePermissionThenRefresh() {
        if (hasAudioPermission()) refreshLibrary() else permissionsLauncher.launch(requiredPermissions())
    }

    private fun refreshLibrary() {
        if (!hasAudioPermission() || refreshInFlight) return
        refreshInFlight = true
        lastRefreshAtMs = android.os.SystemClock.elapsedRealtime()
        libraryLoading = true
        appScope.launch {
            val refreshedTracks = withContext(Dispatchers.IO) {
                runCatching { audioService.scanLibrary(savedDownloadTree()) }.getOrDefault(emptyList())
            }
            val refreshedIds = refreshedTracks.asSequence().map { it.id }.toSet()
            val removedIds = lastLibraryTrackIds?.minus(refreshedIds).orEmpty()
            lastLibraryTrackIds = refreshedIds
            tracks = refreshedTracks
            if (removedIds.isNotEmpty()) {
                MusicPlaybackService.reconcileDeletedTracks(this@MainActivity, removedIds)
            }
            AppEvents.bump()
            selectedTrack = selectedTrack?.let { selected ->
                tracks.firstOrNull { it.id == selected.id }?.copy(
                    albumArtist = selected.albumArtist,
                    trackTotal = selected.trackTotal,
                    discNumber = selected.discNumber,
                    discTotal = selected.discTotal,
                    genre = selected.genre,
                    isrc = selected.isrc,
                    artworkBytes = selected.artworkBytes,
                ) ?: selected
            }
            lastRefreshAtMs = android.os.SystemClock.elapsedRealtime()
            libraryLoading = false
            refreshInFlight = false
        }
    }

    private fun selectTrack(summary: AudioTrack) {
        selectedTrack = summary
        appScope.launch {
            val detailed = withContext(Dispatchers.IO) {
                runCatching { audioService.read(Uri.parse(summary.id)) }.getOrNull()
            }
            if (detailed != null && selectedTrack?.id == summary.id) {
                selectedTrack = detailed.copy(
                    durationMs = detailed.durationMs.takeIf { it > 0 } ?: summary.durationMs,
                    modifiedAtSeconds = summary.modifiedAtSeconds,
                    sizeBytes = summary.sizeBytes,
                    location = summary.location,
                )
            }
        }
    }

    private suspend fun saveEdits(track: AudioTrack, edits: EditableTags): TagWriteResult {
        val uri = Uri.parse(track.id)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY) {
                if (!requestWriteAccess(uri)) return TagWriteResult(false, "Alteração cancelada.")
            }
            return withContext(Dispatchers.IO) {
                audioService.applyEditsOrThrow(track, edits)
            }.also { result ->
                result.updatedTrack?.let { updatedRaw ->
                    val updated = updatedRaw.copy(
                        modifiedAtSeconds = System.currentTimeMillis() / 1000L,
                        sizeBytes = track.sizeBytes,
                        location = track.location,
                    )
                    selectedTrack = updated
                    tracks = tracks.map { current -> if (current.id == updated.id) updated else current }
                    AppEvents.bump()
                }
            }
        } catch (error: RecoverableSecurityException) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val granted = requestIntentSender(error.userAction.actionIntent.intentSender)
                if (granted) return saveEdits(track, edits)
            }
            return TagWriteResult(false, "O Android não concedeu acesso para alterar esse arquivo.")
        } catch (error: Throwable) {
            return TagWriteResult(false, "Erro ao salvar tags: ${error.message ?: error::class.simpleName}")
        }
    }

    private suspend fun requestWriteAccess(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val pending = MediaStore.createWriteRequest(contentResolver, listOf(uri))
        return requestIntentSender(pending.intentSender)
    }

    private suspend fun requestIntentSender(intentSender: android.content.IntentSender): Boolean =
        suspendCancellableCoroutine { continuation ->
            writeContinuation?.cancel()
            writeContinuation = continuation
            continuation.invokeOnCancellation {
                if (writeContinuation === continuation) writeContinuation = null
            }
            writeLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
        }

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasAudioPermission(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun loadSettings(): AppSettings = AppSettings(
        autoRefreshLibrary = prefs.getBoolean(ShareContract.PREF_AUTO_REFRESH_LIBRARY, true),
        embedYoutubeThumbnail = prefs.getBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, true),
        autoUpdateDownloader = prefs.getBoolean(ShareContract.PREF_AUTO_UPDATE_DOWNLOADER, true),
        geniusAccessToken = prefs.getString(ShareContract.PREF_GENIUS_ACCESS_TOKEN, "").orEmpty(),
    )

    private fun updateSettings(newSettings: AppSettings) {
        settings = newSettings
        prefs.edit()
            .putBoolean(ShareContract.PREF_AUTO_REFRESH_LIBRARY, newSettings.autoRefreshLibrary)
            .putBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, newSettings.embedYoutubeThumbnail)
            .putBoolean(ShareContract.PREF_AUTO_UPDATE_DOWNLOADER, newSettings.autoUpdateDownloader)
            .putString(ShareContract.PREF_GENIUS_ACCESS_TOKEN, newSettings.geniusAccessToken)
            .apply()
    }

    private fun savedDownloadTree(): Uri? = prefs.getString(ShareContract.PREF_DOWNLOAD_TREE, null)?.let(Uri::parse)

    /**
     * Devolve a pasta ao sistema.
     *
     * Duas coisas prendem a pasta: o descritor de arquivo que o player mantém
     * aberto na faixa atual e a permissão persistente concedida via SAF.
     * Soltamos as duas — depois disso a pasta pode ser renomeada ou excluída
     * normalmente pelo gerenciador de arquivos.
     */
    private fun releaseDownloadFolder() {
        MusicPlaybackService.releaseFileHandles(this)
        savedDownloadTree()?.let { uri ->
            runCatching {
                contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs.edit().remove(ShareContract.PREF_DOWNLOAD_TREE).apply()
        downloadFolderLabel = ""
        tracks = emptyList()
        lastLibraryTrackIds = null
    }

    /**
     * Permissões persistentes de pastas que não são mais a pasta de download
     * ficariam presas para sempre. Soltamos as órfãs a cada abertura.
     */
    private fun releaseStalePermissions() {
        val current = prefs.getString(ShareContract.PREF_DOWNLOAD_TREE, null)
        runCatching {
            contentResolver.persistedUriPermissions
                .filter { it.uri.toString() != current }
                .forEach { permission ->
                    runCatching {
                        contentResolver.releasePersistableUriPermission(
                            permission.uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                }
        }
    }

    private fun folderLabel(uri: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
            .substringAfter(':', DocumentsContract.getTreeDocumentId(uri))
            .ifBlank { "Pasta escolhida" }
    }.getOrDefault("Pasta escolhida")
}
