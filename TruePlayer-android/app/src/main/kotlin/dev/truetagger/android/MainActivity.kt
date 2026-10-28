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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.EditableTags
import dev.truetagger.model.ImportStatus
import dev.truetagger.model.PermissionItem
import dev.truetagger.model.TagWriteResult
import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.UrlSupport
import dev.truetagger.data.PlannedMove
import dev.truetagger.model.TrackKeys
import dev.truetagger.ui.FolderCleanupDialog
import dev.truetagger.ui.UpdateDialogs
import dev.truetagger.ui.FolderMigrationDialogs
import dev.truetagger.ui.OnboardingDialog
import dev.truetagger.ui.GitDialogs
import dev.truetagger.ui.TrueTaggerPlayerApp
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
    private var migrationPreview by mutableStateOf<FolderMigrator.Preview?>(null)
    private var migrationProgress by mutableStateOf<FolderMigrator.Progress?>(null)
    private var migrationReport by mutableStateOf<FolderMigrator.Report?>(null)
    private var migrationMessage by mutableStateOf<String?>(null)
    private var whatsNew by mutableStateOf<Pair<String, String>?>(null)
    private var cleanupScan by mutableStateOf<FolderOrganizer.Scan?>(null)
    private var folderAccessNeeded by mutableStateOf(false)
    private var accessPromptDismissed = false
    private var permissionsRevision by mutableStateOf(0)
    private var showTutorial by mutableStateOf(false)
    private var openSettingsIfDenied = false
    private var migrationRunning = false
    private var folderChecked = false
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
        const val PREF_MIGRATION_DECLINED_AT = "migration_declined_at"
        const val MIGRATION_ASK_AGAIN_MS = 24L * 60 * 60 * 1000
    }

    private val permissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsRevision++
        if (!hasAudioPermission()) openAppSettingsIfAsked()
        refreshLibrary()
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionsRevision++
        if (!notificationsGranted()) openAppSettingsIfAsked()
    }

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
        folderAccessNeeded = false
        permissionsRevision++
        folderChecked = false
        lastLibraryTrackIds = null
        refreshLibrary()

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
        ConnectivityObserver.start(this)
        whatsNew = UpdateManager.takeWhatsNew(this)
        UpdateWorker.schedule(this, settings.autoCheckUpdates)
        // E de novo sempre que a internet volta (no máximo a cada 6 horas).
        appScope.launch {
            ConnectivityObserver.reconnects.drop(1).collect {
                if (settings.autoCheckUpdates) UpdateManager.check(this@MainActivity, manual = false)
            }
        }
        releaseStalePermissions()
        checkFolderAccess()
        registerMediaObserver()
        YoutubeDownloadService.resumePending(this)
        // Depois de "Baixar do GitHub": relê a biblioteca, as playlists e as estatísticas da pasta.
        GitController.onFolderChanged = {
            runOnUiThread { lastRefreshAtMs = 0L; refreshLibrary() }
            appScope.launch(Dispatchers.IO) {
                runCatching { PlaylistFolderSync.importFromFolder(this@MainActivity) }
                runCatching { PlayStatsState.loadFromFolder(this@MainActivity) }
            }
        }
        // Tutorial só para instalação nova e sem pasta; quem atualizou nunca vê.
        showTutorial = OnboardingState.shouldShow(this)

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
                onOrganizeFolder = ::organizeFolderNow,
                onCleanFolder = ::cleanFolderNow,
                onCheckUpdate = { UpdateManager.check(this, manual = true) },
                permissions = permissionItems(),
                appVersion = runCatching { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault(""),
                importStatus = importStatus,
                onDismissImportStatus = { importStatus = null },
            )
            cleanupScan?.let { scan ->
                FolderCleanupDialog(
                    scan = scan,
                    onConfirm = { selected -> cleanupScan = null; runCleanup(selected) },
                    onCancel = { cleanupScan = null },
                )
            }
            val updateState by UpdateManager.ui.collectAsState()
            UpdateDialogs(
                state = updateState,
                whatsNew = whatsNew,
                onUpdate = { UpdateManager.downloadAndInstall(this@MainActivity, it) },
                onDismiss = UpdateManager::dismiss,
                onWhatsNewDismiss = { whatsNew = null },
                onHideDownload = UpdateManager::hideDownload,
            )
            GitDialogs()
            if (showTutorial) {
                OnboardingDialog(
                    folderLabel = downloadFolderLabel,
                    permissions = permissionItems(),
                    onChooseFolder = { folderPicker.launch(savedDownloadTree()) },
                    onFinish = { OnboardingState.finish(this@MainActivity); showTutorial = false },
                )
            }
            FolderMigrationDialogs(
                preview = migrationPreview,
                progress = migrationProgress,
                report = migrationReport,
                message = migrationMessage,
                onMessageDismiss = { migrationMessage = null },
                accessNeeded = folderAccessNeeded && !accessPromptDismissed,
                onGrantAccess = { folderPicker.launch(savedDownloadTree()) },
                onAccessLater = { accessPromptDismissed = true; folderAccessNeeded = false },
                onConfirm = ::runMigration,
                onLater = {
                    prefs.edit().putLong(PREF_MIGRATION_DECLINED_AT, System.currentTimeMillis()).apply()
                    migrationPreview = null
                },
                onReportDismiss = { migrationReport = null },
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

    override fun onStart() {
        super.onStart()
        AppVisibility.visible = true
        // Toda vez que o app volta para a frente (abrir, ou voltar de outro app) confere se há versão nova.
        // É silencioso: só aparece algo se houver.
        if (settings.autoCheckUpdates) UpdateManager.check(this, manual = false, force = true)
    }

    override fun onStop() {
        AppVisibility.visible = false
        PlayStatsState.flush(this)
        SettingsBackup.scheduleWrite(this, settings)
        super.onStop()
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
        checkFolderAccess()
        permissionsRevision++
        GitController.refresh(this)
        // Voltou da tela de "instalar apps desconhecidos" com a permissão dada: continua a atualização.
        (UpdateManager.ui.value as? UpdateUi.NeedsInstallPermission)?.let {
            if (UpdateManager.canInstallPackages(this)) UpdateManager.downloadAndInstall(this, it.release)
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (::audioService.isInitialized && hasAudioPermission() &&
            now - lastRefreshAtMs >= MIN_REFRESH_INTERVAL_MS
        ) {
            refreshLibrary()
        }
    }

    /** A pasta está salva, mas o Android não deu (ou perdeu) a permissão sobre ela? */
    private fun checkFolderAccess() {
        val saved = MusicFolder.savedUri(this)
        folderAccessNeeded = saved != null && !MusicFolder.hasPermission(this, saved)
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
                if (UrlSupport.isSupported(url) && MusicFolder.treeUri(this) != null) YoutubeDownloadService.start(this, url)
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
            val scan = withContext(Dispatchers.IO) { runCatching { audioService.scanLibrary(savedDownloadTree()) } }
            // Se a varredura falhar, a biblioteca anterior continua valendo: nada pode parecer "removido".
            val refreshedTracks = scan.getOrNull() ?: tracks
            val reliable = scan.isSuccess && refreshedTracks.isNotEmpty()
            LibraryIndex.update(refreshedTracks, reliable)
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
            if (reliable) onReliableScan()
        }
    }

    /** Depois de uma varredura que deu certo: acerta chaves das playlists e confere o layout da pasta. */
    private fun onReliableScan() {
        PlaylistStore(this).upgradeKeys()
        PlayStatsState.convertLegacy(this)
        if (folderChecked || migrationRunning) return
        val tree = MusicFolder.tree(this) ?: return
        folderChecked = true
        appScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching {
                    val migrator = FolderMigrator(this@MainActivity, tree)
                    if (migrator.ensureLayout() == null) null else migrator.preview()
                }.getOrNull()
            }
            restoreSettingsFromFolder()
            Log.i("TruePlayer", "checagem da pasta: plano=${preview?.plan?.size}")
            PlaylistFolderSync.importFromFolder(this@MainActivity)
            PlaylistFolderSync.exportAllNow(this@MainActivity)
            PlayStatsState.loadFromFolder(this@MainActivity)
            val declinedAt = prefs.getLong(PREF_MIGRATION_DECLINED_AT, 0L)
            val recentlyDeclined = System.currentTimeMillis() - declinedAt < MIGRATION_ASK_AGAIN_MS
            if (preview != null && preview.plan.isNotEmpty() && !recentlyDeclined) migrationPreview = preview
        }
    }

    /** Botão em Configurações: confere a pasta agora e mostra exatamente o que encontrou. */
    private fun organizeFolderNow() {
        val tree = MusicFolder.tree(this)
        if (tree == null) {
            if (MusicFolder.savedUri(this) != null) {
                accessPromptDismissed = false
                folderAccessNeeded = true
            } else {
                migrationMessage = "Você ainda não escolheu a pasta de músicas. Escolha uma em Configurações."
            }
            return
        }
        appScope.launch {
            val diagnosis = withContext(Dispatchers.IO) { FolderMigrator(this@MainActivity, tree).diagnose() }
            Log.i("TruePlayer", "diagnostico da pasta: $diagnosis")
            if (diagnosis.error != null) {
                migrationMessage = "Não consegui ler a pasta: ${diagnosis.error}"
            } else if (diagnosis.plan.isNotEmpty()) {
                migrationPreview = FolderMigrator.Preview(diagnosis.audioOutside, diagnosis.playlistsOutside, diagnosis.plan)
            } else {
                migrationMessage = "Não tem nada pra mover.\n\n" +
                    "Pasta: $downloadFolderLabel\n" +
                    "Arquivos: ${diagnosis.totalFiles}\n" +
                    "Músicas: ${diagnosis.audioFiles} (${diagnosis.audioInSongs} já estão em songs)\n" +
                    "Pastas songs e playlists: " + if (diagnosis.layoutOk) "ok" else "não consegui criar"
            }
        }
    }

    private fun notificationsGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        }

    /** Se o Android não abrir mais o pedido (negado antes), leva o usuário direto para os ajustes do app. */
    private fun openAppSettingsIfAsked() {
        if (!openSettingsIfDenied) return
        openSettingsIfDenied = false
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
    }

    /** Sem a "otimização de bateria", o Android não congela o app em segundo plano (downloads e envio continuam). */
    private fun ignoresBatteryLimits(): Boolean =
        runCatching { getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(true)

    private fun requestNoBatteryLimits() {
        val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    /** Checklist de Configurações; reler `permissionsRevision` faz a lista atualizar quando algo muda. */
    private fun permissionItems(): List<PermissionItem> {
        @Suppress("UNUSED_VARIABLE") val refresh = permissionsRevision
        val savedFolder = MusicFolder.savedUri(this)
        return listOf(
            PermissionItem("Músicas do celular", hasAudioPermission(), "Para listar e tocar as músicas") {
                openSettingsIfDenied = true
                permissionsLauncher.launch(requiredPermissions())
            },
            PermissionItem("Notificações", notificationsGranted(), "Controles do player, downloads e avisos") {
                openSettingsIfDenied = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppSettingsIfAsked()
                }
            },
            PermissionItem("Pasta de músicas", savedFolder != null && MusicFolder.hasPermission(this, savedFolder), "Para ler e salvar na pasta escolhida") {
                folderPicker.launch(savedDownloadTree())
            },
            PermissionItem("Instalar atualizações", UpdateManager.canInstallPackages(this), "Para o app se atualizar sozinho") {
                UpdateManager.openInstallPermissionSettings(this)
            },
            PermissionItem("Rodar em segundo plano", ignoresBatteryLimits(), "Downloads, envio ao GitHub e avisos com o app fechado") {
                requestNoBatteryLimits()
            },
        )
    }

    /** Botão "Limpar pasta": mostra o que não é música/playlist para o usuário decidir. */
    private fun cleanFolderNow() {
        val tree = MusicFolder.tree(this)
        if (tree == null) {
            if (MusicFolder.savedUri(this) != null) {
                accessPromptDismissed = false
                folderAccessNeeded = true
            } else {
                migrationMessage = "Você ainda não escolheu a pasta de músicas. Escolha uma em Configurações."
            }
            return
        }
        appScope.launch {
            val scan = withContext(Dispatchers.IO) { runCatching { FolderOrganizer(tree).scan() } }
            scan.onSuccess {
                if (it.candidates.isEmpty()) {
                    migrationMessage = "Não tem nada pra limpar. Só sobraram músicas, playlists e arquivos do app." +
                        if (it.foldersWithMusic.isNotEmpty()) "\n\nPastas com música fora de songs: ${it.foldersWithMusic.joinToString(", ")}. Use \"Organizar pasta\"." else ""
                } else {
                    cleanupScan = it
                }
            }.onFailure { migrationMessage = "Não consegui ler a pasta: ${it.message}" }
        }
    }

    private fun runCleanup(selected: List<FolderOrganizer.Candidate>) {
        val tree = MusicFolder.tree(this) ?: return
        appScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { FolderOrganizer(tree).moveToTrash(selected) } }
            migrationMessage = outcome.fold(
                onSuccess = { r ->
                    "${dev.truetagger.ui.plural(r.moved, "item movido", "itens movidos")} para a pasta _lixeira." +
                        if (r.failed.isNotEmpty()) "\n\nNão consegui mover: ${r.failed.joinToString(", ")}." else ""
                },
                onFailure = { "Não deu pra limpar a pasta: ${it.message}. Nada foi apagado." },
            )
            lastRefreshAtMs = 0L
            refreshLibrary()
        }
    }

    /** Reinstalou ou trocou de celular (sem configurações salvas)? Traz de volta as da pasta; senão só garante que o arquivo existe. */
    private suspend fun restoreSettingsFromFolder() {
        val fresh = !prefs.contains(ShareContract.PREF_AUTO_REFRESH_LIBRARY)
        val restored = withContext(Dispatchers.IO) {
            if (fresh) SettingsBackup.read(this@MainActivity)
            else {
                if (!SettingsBackup.exists(this@MainActivity)) SettingsBackup.write(this@MainActivity, settings)
                null
            }
        }
        if (restored != null) updateSettings(restored)
    }

    private fun runMigration() {
        val tree = MusicFolder.tree(this) ?: return
        migrationPreview = null
        migrationRunning = true
        migrationProgress = FolderMigrator.Progress(PlannedMove.Kind.SONG, "", 0, 0, 0, 1)
        // Mantém a tela acesa enquanto organiza (só vale com o app aberto).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MusicPlaybackService.releaseFileHandles(this)
        appScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    FolderMigrator(this@MainActivity, tree).run(
                        beforeMove = { plan -> prepareForMigration(tree, plan) },
                        onProgress = { migrationProgress = it },
                    )
                }
            }
            migrationProgress = null
            migrationRunning = false
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            outcome.onSuccess { migrationReport = it }.onFailure {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Não deu pra organizar a pasta: ${it.message}. Nada foi apagado.",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            PlaylistFolderSync.importFromFolder(this@MainActivity)
            lastRefreshAtMs = 0L
            refreshLibrary()
        }
    }

    /** Antes de mover: backup das playlists do app e gravação delas em playlists/ já com os caminhos finais. */
    private fun prepareForMigration(tree: SafTree, plan: List<PlannedMove>) {
        val store = PlaylistStore(this)
        val backupDir = tree.ensureDirPath("${MigrationPlanner.META_DIR}/backup") ?: error("não consegui criar a pasta de backup")
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        tree.writeTextFile(backupDir, "playlists-$stamp.json", store.rawBackup(), "application/json")
            ?: error("não consegui salvar o backup das playlists")
        store.remapKeys(
            plan.filter { it.kind == PlannedMove.Kind.SONG }.associate { TrackKeys.compareKey(it.from) to it.to },
        )
        PlaylistFolderSync.exportBlocking(this)
    }

    private fun selectTrack(summary: AudioTrack) {
        selectedTrack = summary
        appScope.launch {
            val detailed = withContext(Dispatchers.IO) {
                runCatching { audioService.read(Uri.parse(summary.id)) }.getOrNull()
            }
            if (detailed != null && selectedTrack?.id == summary.id) {
                selectedTrack = detailed.copy(
                    relPath = summary.relPath,
                    trackNumber = detailed.trackNumber.ifBlank { summary.trackNumber },
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
                        relPath = track.relPath,
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
        autoTag = prefs.getBoolean(ShareContract.PREF_AUTO_TAG, true),
        autoCheckUpdates = prefs.getBoolean(ShareContract.PREF_AUTO_CHECK_UPDATES, true),
    )

    private fun updateSettings(newSettings: AppSettings) {
        settings = newSettings
        UpdateWorker.schedule(this, newSettings.autoCheckUpdates)
        SettingsBackup.scheduleWrite(this, newSettings)
        prefs.edit()
            .putBoolean(ShareContract.PREF_AUTO_REFRESH_LIBRARY, newSettings.autoRefreshLibrary)
            .putBoolean(ShareContract.PREF_EMBED_YOUTUBE_THUMBNAIL, newSettings.embedYoutubeThumbnail)
            .putBoolean(ShareContract.PREF_AUTO_UPDATE_DOWNLOADER, newSettings.autoUpdateDownloader)
            .putBoolean(ShareContract.PREF_AUTO_TAG, newSettings.autoTag)
            .putBoolean(ShareContract.PREF_AUTO_CHECK_UPDATES, newSettings.autoCheckUpdates)
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
        LibraryIndex.update(emptyList(), false)
        lastLibraryTrackIds = null
        folderChecked = false
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
