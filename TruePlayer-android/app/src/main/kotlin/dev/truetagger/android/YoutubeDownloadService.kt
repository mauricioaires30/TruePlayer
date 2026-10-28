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
import dev.truetagger.data.DownloadQueueStore
import dev.truetagger.data.PlaylistEntry
import dev.truetagger.data.QueuedDownload
import dev.truetagger.data.UrlSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private lateinit var queue: DownloadQueueStore
    private val downloadChannel = Channel<QueuedDownload>(Channel.UNLIMITED)
    private val queuedDownloads = AtomicInteger(0)

    /** Itens que entraram e ainda não terminaram, inclusive o que está baixando agora. */
    private val unfinished = AtomicInteger(0)

    /** Músicas do lote atual (zera quando a fila esvazia): "Música 2 de 5" na notificação. */
    private val batchLock = Any()
    private val batchTotal = AtomicInteger(0)
    private val batchDone = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        importer = YoutubeImportManager(this)
        queue = queueStore(this)
        ensureNotificationChannel()
        serviceScope.launch {
            for (item in downloadChannel) {
                queuedDownloads.decrementAndGet()
                if (item.expand) expandPlaylist(item) else processDownload(item)
                // Se o serviço foi encerrado no meio, o item continua na fila para a próxima vez.
                currentCoroutineContext().ensureActive()
                queue.remove(item.id)
                synchronized(batchLock) {
                    if (!item.expand) batchDone.incrementAndGet()
                    unfinished.decrementAndGet()
                }
                finishBatchIfIdle()
            }
        }
        // Retoma o que ficou pela metade se o processo foi encerrado.
        queue.load().forEach { enqueue(it) }
    }

    private fun enqueue(item: QueuedDownload) {
        synchronized(batchLock) {
            if (unfinished.get() == 0) {
                batchTotal.set(0)
                batchDone.set(0)
            }
            if (!item.expand) batchTotal.incrementAndGet()
            unfinished.incrementAndGet()
        }
        queuedDownloads.incrementAndGet()
        downloadChannel.trySend(item)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(ShareContract.EXTRA_SHARED_URL).orEmpty().trim()
        if (url.isBlank() || !UrlSupport.isSupported(url)) {
            // Reinício do sistema ou pedido para retomar: só segue se sobrou algo na fila.
            synchronized(batchLock) {
                lastStartId = startId
                if (unfinished.get() == 0) stopSelf(startId)
            }
            return START_STICKY
        }

        // Sob a mesma trava do "fila vazia, pode encerrar": um link novo nunca é perdido no meio do encerramento.
        synchronized(batchLock) {
            lastStartId = startId
            enqueue(queue.add(url, expand = UrlSupport.needsListing(url)))
        }
        return START_STICKY
    }

    private fun finishBatchIfIdle() {
        if (queuedDownloads.get() != 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopForeground(true) }
        }
        // Fila vazia: encerra o serviço em vez de ficar parado na memória. Se chegou um link depois do
        // último pedido visto aqui, o Android ignora este stopSelf e o serviço continua.
        synchronized(batchLock) {
            if (unfinished.get() == 0) stopSelf(lastStartId)
        }
    }

    @Volatile private var lastStartId = 0

    /** Lê a playlist, cria a playlist do app (vazia) e põe cada música na fila, na ordem original. */
    private suspend fun expandPlaylist(task: QueuedDownload) {
        runCatching {
            startForeground(
                ShareContract.NOTIFICATION_ID,
                buildNotification(title = "Lendo a playlist", text = "Pegando a lista de músicas do link…", indeterminate = true),
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
                processDownload(task.copy(expand = false))
            }
            return
        }

        val store = PlaylistStore(this)
        val name = store.uniqueNameFor(playlist.title)
        store.createWithEntries(name, emptyList())
        playlist.entries.forEachIndexed { index, entry ->
            enqueue(queue.add(entry.url, playlist = name, total = playlist.entries.size, position = index + 1))
        }
    }

    private suspend fun processDownload(task: QueuedDownload) {
        val url = task.url
        val job = task.playlist
        var videoTitle = ""
        val position = when {
            job != null -> "Playlist $job • ${task.position} de ${task.total}"
            batchTotal.get() > 1 -> "Música ${batchDone.get() + 1} de ${batchTotal.get()}"
            else -> null
        }

        runCatching {
            startForeground(
                ShareContract.NOTIFICATION_ID,
                buildNotification(
                    title = "Preparando download",
                    text = "Lendo as informações do link…",
                    subText = position,
                    indeterminate = true,
                ),
            )

            val tree = MusicFolder.treeUri(this)
                ?: error("Libere a pasta de músicas em Configurações antes de baixar.")

            importer.downloadAudio(url, tree) { status ->
                if (status.mediaTitle.isNotBlank()) videoTitle = status.mediaTitle
                val progress = status.progress?.coerceIn(0, 100)
                val waiting = queuedDownloads.get()
                val step = status.message.ifBlank { "Processando…" }.trimEnd('.', '…')
                startForeground(
                    ShareContract.NOTIFICATION_ID,
                    buildNotification(
                        title = videoTitle.ifBlank { "Baixando música" },
                        text = buildString {
                            append(step)
                            if (progress != null && progress > 0) append(" • $progress%")
                            if (waiting > 0) append(" • $waiting na fila")
                        },
                        subText = position,
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
                    text = result.label,
                    completed = true,
                )
            } else {
                PlaylistStore(this).appendEntries(job, listOf(PlaylistEntry(result.relPath, result.label)))
                queue.record(job, success = true)
                if (queue.pendingFor(job, task.id) == 0) finishPlaylist(job, task.total)
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
                queue.record(job, success = false)
                if (queue.pendingFor(job, task.id) == 0) finishPlaylist(job, task.total)
            }
        }
    }

    private fun finishPlaylist(name: String, total: Int) {
        val counts = queue.finishJob(name)
        val missing = if (counts.failed > 0) " (${counts.failed} não baixaram)" else ""
        finishWithNotification(
            title = if (counts.done == total) "Playlist baixada" else "Playlist baixada com falhas",
            text = "$name: ${counts.done} de $total músicas$missing",
            completed = counts.done > 0,
            failed = counts.done == 0,
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
        subText: String? = null,
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
            .setSubText(subText)
            .setCategory(if (ongoing) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
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

    companion object {
        private fun queueStore(context: Context) = DownloadQueueStore(File(context.filesDir, "download-queue.json"))

        /** Chamado ao abrir o app: se algum download ficou pela metade, retoma. */
        fun resumePending(context: Context) {
            val app = context.applicationContext
            if (queueStore(app).load().isEmpty()) return
            val intent = Intent(app, YoutubeDownloadService::class.java).apply { action = ShareContract.ACTION_START_DOWNLOAD }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
            }
        }

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
