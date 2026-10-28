package dev.truetagger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.truetagger.data.PlaylistEntry
import dev.truetagger.data.UrlSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import java.io.File

/**
 * Baixa músicas de links do YouTube e do SoundCloud, uma de cada vez. Links de playlist viram uma
 * playlist do app, com as músicas na mesma ordem da original.
 */
class YoutubeDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var importer: YoutubeImportManager
    private val downloadChannel = Channel<DownloadTask>(Channel.UNLIMITED)
    private val queuedDownloads = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        importer = YoutubeImportManager(this)
        ensureNotificationChannel()
        serviceScope.launch {
            for (task in downloadChannel) {
                queuedDownloads.decrementAndGet()
                when (task) {
                    is DownloadTask.Single -> processDownload(task)
                    is DownloadTask.Expand -> expandPlaylist(task)
                }
                finishBatchIfIdle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(ShareContract.EXTRA_SHARED_URL).orEmpty().trim()
        if (url.isBlank() || !UrlSupport.isSupported(url)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        queuedDownloads.incrementAndGet()
        downloadChannel.trySend(if (UrlSupport.needsListing(url)) DownloadTask.Expand(url) else DownloadTask.Single(url))
        return START_NOT_STICKY
    }

    private fun finishBatchIfIdle() {
        if (queuedDownloads.get() != 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
    }

    /** Lê a playlist, cria a playlist do app (vazia) e põe cada música na fila, na ordem original. */
    private suspend fun expandPlaylist(task: DownloadTask.Expand) {
        runCatching {
            startForeground(
                ShareContract.NOTIFICATION_ID,
                buildNotification(title = "Lendo a playlist", text = "Pegando a lista de músicas do link...", indeterminate = true),
            )
            MusicFolder.treeUri(this) ?: error("Libere a pasta de músicas em Configurações antes de baixar.")
        }.onFailure { error ->
            saveLastError(error)
            finishWithNotification("Falha no download", error.message ?: "Não foi possível ler a playlist.", failed = true)
            return
        }

        val playlist = importer.listPlaylist(task.url) { }
        if (playlist == null) {
            if (UrlSupport.isPlaylist(task.url)) {
                finishWithNotification(
                    "Não consegui ler a playlist",
                    "Confira se o link está certo e se a playlist é pública.",
                    failed = true,
                )
            } else {
                // Link curto que na verdade era uma música só.
                processDownload(DownloadTask.Single(task.url))
            }
            return
        }

        val store = PlaylistStore(this)
        val name = store.uniqueNameFor(playlist.title)
        store.createWithEntries(name, emptyList())
        val job = PlaylistJob(name, playlist.entries.size)
        playlist.entries.forEachIndexed { index, entry ->
            queuedDownloads.incrementAndGet()
            downloadChannel.trySend(DownloadTask.Single(entry.url, job, index + 1))
        }
    }

    private suspend fun processDownload(task: DownloadTask.Single) {
        val url = task.url
        val job = task.job
        var videoTitle = ""
        val queuedAfterCurrent = queuedDownloads.get()
        val position = job?.let { "Playlist ${it.name} (${task.position}/${it.total})" }

        runCatching {
            startForeground(
                ShareContract.NOTIFICATION_ID,
                buildNotification(
                    title = position ?: "Preparando download",
                    text = if (queuedAfterCurrent > 0) "Lendo informações... • $queuedAfterCurrent na fila" else "Lendo informações...",
                    indeterminate = true,
                ),
            )

            val tree = MusicFolder.treeUri(this)
                ?: error("Libere a pasta de músicas em Configurações antes de baixar.")

            importer.downloadAudio(url, tree) { status ->
                if (status.mediaTitle.isNotBlank()) videoTitle = status.mediaTitle
                val progress = status.progress?.coerceIn(0, 100)
                val queued = queuedDownloads.get()
                val queueText = if (queued > 0) " • $queued na fila" else ""
                val shownTitle = when {
                    videoTitle.isNotBlank() && progress != null && progress > 0 -> "$videoTitle • $progress%"
                    videoTitle.isNotBlank() -> videoTitle
                    progress != null && progress > 0 -> "Baixando música • $progress%"
                    else -> "Preparando download"
                }
                startForeground(
                    ShareContract.NOTIFICATION_ID,
                    buildNotification(
                        title = position ?: shownTitle,
                        text = (if (position != null) "$shownTitle — " else "") + status.message.ifBlank { "Processando..." } + queueText,
                        progress = progress,
                        indeterminate = progress == null || progress == 0,
                    ),
                )
            }
        }.onSuccess { result ->
            clearLastError()
            if (job == null) {
                finishWithNotification(
                    title = "Download concluído",
                    text = result.label + if (result.needsReview) " • revise as tags em Configurações" else "",
                    completed = true,
                )
            } else {
                PlaylistStore(this).appendEntries(job.name, listOf(PlaylistEntry(result.relPath, result.label)))
                job.done++
                if (task.position == job.total) finishPlaylist(job)
            }
        }.onFailure { error ->
            saveLastError(error)
            if (job == null) {
                finishWithNotification(
                    title = "Falha no download",
                    text = if (videoTitle.isBlank()) "Não foi possível baixar este link." else "Não foi possível baixar: $videoTitle",
                    failed = true,
                )
            } else {
                job.failed++
                if (task.position == job.total) finishPlaylist(job)
            }
        }
    }

    private fun finishPlaylist(job: PlaylistJob) {
        val missing = if (job.failed > 0) " (${job.failed} não baixaram)" else ""
        finishWithNotification(
            title = "Playlist baixada",
            text = "${job.name}: ${job.done} de ${job.total} músicas$missing",
            completed = job.done > 0,
            failed = job.done == 0,
        )
    }

    override fun onDestroy() {
        downloadChannel.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun finishWithNotification(
        title: String,
        text: String,
        completed: Boolean = false,
        failed: Boolean = false,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
        notificationManager().notify(
            ShareContract.NOTIFICATION_RESULT_ID,
            buildNotification(
                title = title,
                text = text,
                progress = if (completed) 100 else null,
                indeterminate = false,
                ongoing = false,
                completed = completed,
                failed = failed,
            ),
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int? = null,
        indeterminate: Boolean = false,
        ongoing: Boolean = true,
        completed: Boolean = false,
        failed: Boolean = false,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { action = ShareContract.ACTION_OPEN_APP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, ShareContract.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(
                when {
                    completed -> android.R.drawable.stat_sys_download_done
                    failed -> android.R.drawable.stat_notify_error
                    else -> android.R.drawable.stat_sys_download
                }
            )
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(completed || failed)
            .setContentIntent(contentIntent)
            .setProgress(100, progress ?: 0, indeterminate)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            ShareContract.NOTIFICATION_CHANNEL_ID,
            "Downloads do TruePlayer",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progresso dos downloads de músicas"
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun saveLastError(error: Throwable) {
        runCatching {
            File(filesDir, "last-download-error.txt").writeText(
                buildString {
                    appendLine(error::class.qualifiedName ?: error::class.simpleName ?: "Erro")
                    appendLine(error.message.orEmpty())
                    appendLine()
                    append(error.stackTraceToString())
                }
            )
        }
    }

    private fun clearLastError() {
        runCatching { File(filesDir, "last-download-error.txt").delete() }
    }

    /** Playlist em andamento: quantas músicas já baixaram e quantas falharam. */
    private class PlaylistJob(val name: String, val total: Int) {
        var done = 0
        var failed = 0
    }

    private sealed interface DownloadTask {
        data class Single(val url: String, val job: PlaylistJob? = null, val position: Int = 0) : DownloadTask
        data class Expand(val url: String) : DownloadTask
    }

    companion object {
        fun start(context: Context, url: String) {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) return
            val intent = Intent(context, YoutubeDownloadService::class.java).apply {
                action = ShareContract.ACTION_START_DOWNLOAD
                putExtra(ShareContract.EXTRA_SHARED_URL, cleanUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
