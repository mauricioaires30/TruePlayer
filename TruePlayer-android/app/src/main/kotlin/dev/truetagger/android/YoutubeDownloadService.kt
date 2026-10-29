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
import com.yausername.youtubedl_android.YoutubeDL
import dev.truetagger.data.DownloadKind
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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

    /** Item baixando agora e a tarefa dele (para cancelar). Mexer só sob [batchLock]. */
    private var currentItem: QueuedDownload? = null
    private var currentJob: Job? = null

    /** Itens que o usuário cancelou enquanto baixavam: a falha deles não é erro. */
    private val canceledIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private fun processIdOf(id: Long) = "TrueTaggerDownload-$id"

    override fun onCreate() {
        super.onCreate()
        importer = YoutubeImportManager(this)
        queue = queueStore(this)
        ensureNotificationChannel()
        serviceScope.launch {
            for (item in downloadChannel) {
                queuedDownloads.decrementAndGet()
                // Já saiu da fila: o usuário cancelou a playlist ou o álbum enquanto este item esperava.
                val skipped = !queue.contains(item.id)
                if (!skipped) {
                    val job = serviceScope.launch { if (item.expand) expandPlaylist(item) else processDownload(item) }
                    synchronized(batchLock) { currentItem = item; currentJob = job }
                    job.join()
                    synchronized(batchLock) { currentItem = null; currentJob = null }
                    canceledIds.remove(item.id)
                }
                // Se o serviço foi encerrado no meio, o item continua na fila para a próxima vez.
                currentCoroutineContext().ensureActive()
                queue.remove(item.id)
                synchronized(batchLock) {
                    if (!item.expand) {
                        if (skipped) batchTotal.decrementAndGet() else batchDone.incrementAndGet()
                    }
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
        when (intent?.action) {
            ShareContract.ACTION_CANCEL_ITEM -> {
                cancelItem(intent.getLongExtra(ShareContract.EXTRA_ITEM_ID, -1L))
                return START_STICKY
            }
            ShareContract.ACTION_CANCEL_JOB -> {
                cancelJob(intent.getStringExtra(ShareContract.EXTRA_JOB).orEmpty())
                return START_STICKY
            }
        }
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

    /** Para só a música que está baixando; se ela é de uma playlist ou álbum, as outras continuam. */
    private fun cancelItem(id: Long) {
        serviceScope.launch {
            val job = synchronized(batchLock) {
                val item = currentItem?.takeIf { it.id == id } ?: return@launch
                canceledIds.add(item.id)
                currentJob
            }
            runCatching { YoutubeDL.getInstance().destroyProcessById(processIdOf(id)) }
            job?.cancel()
        }
    }

    /** Cancela a playlist ou o álbum inteiro: o que falta sai da fila e a música que baixa agora para. */
    private fun cancelJob(name: String) {
        if (name.isBlank()) return
        serviceScope.launch {
            val (removed, running) = synchronized(batchLock) {
                val running = currentItem?.takeIf { it.playlist == name }
                val removed = queue.removeJob(name, exceptId = running?.id)
                if (running != null) canceledIds.add(running.id)
                removed to (running to currentJob)
            }
            val (runningItem, runningJob) = running
            if (runningItem != null) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processIdOf(runningItem.id)) }
                runningJob?.cancel()
            } else if (removed.isNotEmpty()) {
                // Nada deste lote estava baixando: fecha o resumo aqui.
                finishPlaylist(name, removed.first().total)
            }
        }
    }

    private fun finishBatchIfIdle() {
        if (queuedDownloads.get() != 0) return
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
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
                buildNotification(
                    title = t(R.string.dl_lendo_a_playlist),
                    text = t(R.string.dl_pegando_a_lista_de_musicas),
                    indeterminate = true,
                    actions = listOf(cancelItemAction(task, t(R.string.dl_cancelar))),
                ),
            )
            MusicFolder.treeUri(this) ?: error(t(R.string.dl_libere_a_pasta_de_musicas))
        }.onFailure { error ->
            saveLastError(error)
            finishWithNotification(t(R.string.dl_falha_no_download), error.message ?: t(R.string.dl_nao_foi_possivel_ler_a), failed = true)
            return
        }

        val playlist = importer.listPlaylist(task.url, processIdOf(task.id)) { }
        // Cancelado enquanto lia: sai sem avisar de erro.
        if (task.id in canceledIds) return
        if (playlist == null) {
            if (UrlSupport.isPlaylist(task.url)) {
                finishWithNotification(
                    t(R.string.dl_nao_consegui_ler_a_playlist),
                    t(R.string.dl_confira_se_o_link_esta),
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
        // A notificação é da música. Só a linha pequena diz em que ponto do lote ela está ("3 de 10").
        val position = when {
            job != null -> t(R.string.dl_position, task.position, task.total)
            batchTotal.get() > 1 -> t(R.string.dl_position, batchDone.get() + 1, batchTotal.get())
            else -> null
        }
        val actions = downloadActions(task)

        runCatching {
            startForeground(
                ShareContract.NOTIFICATION_ID,
                buildNotification(
                    title = t(R.string.dl_preparando_download),
                    text = t(R.string.dl_lendo_as_informacoes_do_link),
                    subText = position,
                    indeterminate = true,
                    actions = actions,
                ),
            )

            val tree = MusicFolder.treeUri(this)
                ?: error(t(R.string.dl_libere_a_pasta_de_musicas))

            importer.downloadAudio(url, tree, processIdOf(task.id)) { status ->
                if (status.mediaTitle.isNotBlank()) videoTitle = status.mediaTitle
                val progress = status.progress?.coerceIn(0, 100)
                val waiting = queuedDownloads.get()
                val step = status.message.ifBlank { t(R.string.dl_processando) }.trimEnd('.', '…')
                startForeground(
                    ShareContract.NOTIFICATION_ID,
                    buildNotification(
                        title = videoTitle.ifBlank { t(R.string.dl_baixando_musica) },
                        text = buildString {
                            append(step)
                            if (progress != null && progress > 0) append(" • $progress%")
                            if (waiting > 0) append(t(R.string.dl_na_fila, waiting))
                        },
                        subText = position,
                        progress = progress,
                        indeterminate = progress == null || progress == 0,
                        actions = actions,
                    ),
                )
            }
        }.onSuccess { result ->
            clearLastError()
            if (job == null) {
                finishWithNotification(
                    title = t(R.string.dl_download_concluido),
                    text = result.label,
                    completed = true,
                )
            } else {
                PlaylistStore(this).appendEntries(job, listOf(PlaylistEntry(result.relPath, result.label)))
                queue.record(job, success = true)
                if (queue.pendingFor(job, task.id) == 0) finishPlaylist(job, task.total)
            }
        }.onFailure { error ->
            if (task.id in canceledIds) {
                // Cancelado pelo usuário: não é erro. Avulso some sem aviso; de playlist/álbum conta como cancelado.
                if (job != null) {
                    queue.recordCanceled(job)
                    if (queue.pendingFor(job, task.id) == 0) finishPlaylist(job, task.total)
                }
                return
            }
            if (error is CancellationException) throw error
            saveLastError(error)
            if (job == null) {
                finishWithNotification(
                    title = t(R.string.dl_falha_no_download),
                    text = if (videoTitle.isBlank()) t(R.string.dl_nao_foi_possivel_baixar_este) else t(R.string.dl_nao_foi_possivel_baixar, videoTitle),
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
        val missing = (if (counts.failed > 0) t(R.string.dl_nao_baixaram, counts.failed) else "") +
            (if (counts.canceled > 0) t(R.string.dl_canceladas, counts.canceled) else "")
        finishWithNotification(
            title = when {
                counts.done == total -> t(R.string.dl_playlist_baixada)
                counts.canceled > 0 && counts.failed == 0 -> t(R.string.dl_download_cancelado)
                else -> t(R.string.dl_playlist_baixada_com_falhas)
            },
            text = t(R.string.dl_de_musicas, name, counts.done, total, missing),
            completed = counts.done > 0,
            failed = counts.done == 0 && counts.canceled == 0,
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
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
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
        actions: List<NotificationCompat.Action> = emptyList(),
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { action = ShareContract.ACTION_OPEN_APP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, ShareContract.NOTIFICATION_CHANNEL_ID)
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
        actions.forEach { builder.addAction(it) }
        return builder.build()
    }

    private fun serviceAction(title: String, action: String, requestCode: Int, fill: Intent.() -> Unit): NotificationCompat.Action {
        val intent = Intent(this, YoutubeDownloadService::class.java).setAction(action).apply(fill)
        val pending = PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, title, pending).build()
    }

    private fun cancelItemAction(task: QueuedDownload, title: String) =
        serviceAction(title, ShareContract.ACTION_CANCEL_ITEM, 1) { putExtra(ShareContract.EXTRA_ITEM_ID, task.id) }

    /** "Cancelar música" sempre; "Cancelar playlist/álbum" só quando a música faz parte de um lote. */
    private fun downloadActions(task: QueuedDownload): List<NotificationCompat.Action> {
        val actions = mutableListOf(cancelItemAction(task, t(R.string.dl_cancelar_musica)))
        val job = task.playlist
        if (job != null) {
            val title = if (task.kind == DownloadKind.ALBUM) t(R.string.dl_cancelar_album) else t(R.string.dl_cancelar_playlist)
            actions += serviceAction(title, ShareContract.ACTION_CANCEL_JOB, 2) { putExtra(ShareContract.EXTRA_JOB, job) }
        }
        return actions
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            ShareContract.NOTIFICATION_CHANNEL_ID,
            t(R.string.dl_downloads_do_trueplayer),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = t(R.string.dl_progresso_dos_downloads_de_musicas)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    private fun saveLastError(error: Throwable) {
        runCatching {
            File(filesDir, "last-download-error.txt").writeText(
                buildString {
                    appendLine(error::class.qualifiedName ?: error::class.simpleName ?: t(R.string.dl_erro))
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
                app.startForegroundService(intent)
            }
        }

        fun start(context: Context, url: String) {
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) return
            val intent = Intent(context, YoutubeDownloadService::class.java).apply {
                action = ShareContract.ACTION_START_DOWNLOAD
                putExtra(ShareContract.EXTRA_SHARED_URL, cleanUrl)
            }
            context.startForegroundService(intent)
        }
    }
}
