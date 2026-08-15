package dev.truetagger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class YoutubeDownloadService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var importer: YoutubeImportManager

    override fun onCreate() {
        super.onCreate()
        importer = YoutubeImportManager(this)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(ShareContract.EXTRA_SHARED_URL)
        if (url.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        var videoTitle = ""

        startForeground(
            ShareContract.NOTIFICATION_ID,
            buildNotification(
                title = "Preparando download",
                text = "Lendo informações do vídeo...",
                indeterminate = true,
            ),
        )

        serviceScope.launch {
            val tree = getSharedPreferences(ShareContract.PREFS, MODE_PRIVATE)
                .getString(ShareContract.PREF_DOWNLOAD_TREE, null)
                ?.let(Uri::parse)

            if (tree == null) {
                finishWithNotification(
                    "Escolha uma pasta",
                    "Abra o TrueTagger para configurar a pasta de download.",
                    failed = true,
                )
                stopSelf(startId)
                return@launch
            }

            runCatching {
                importer.downloadAudio(url, tree) { status ->
                    if (status.mediaTitle.isNotBlank()) videoTitle = status.mediaTitle
                    val progress = status.progress?.coerceIn(0, 100)
                    val notificationTitle = when {
                        videoTitle.isNotBlank() && progress != null && progress > 0 -> "$videoTitle • $progress%"
                        videoTitle.isNotBlank() -> videoTitle
                        progress != null && progress > 0 -> "Baixando música • $progress%"
                        else -> "Preparando download"
                    }
                    startForeground(
                        ShareContract.NOTIFICATION_ID,
                        buildNotification(
                            title = notificationTitle,
                            text = status.message.ifBlank { "Processando vídeo..." },
                            progress = progress,
                            indeterminate = progress == null || progress == 0,
                        ),
                    )
                }
            }.onSuccess { fileName ->
                clearLastError()
                finishWithNotification(
                    title = "Download concluído",
                    text = videoTitle.ifBlank { fileName },
                    completed = true,
                )
            }.onFailure { error ->
                saveLastError(error)
                finishWithNotification(
                    title = "Falha no download",
                    text = if (videoTitle.isBlank()) {
                        "Não foi possível baixar este vídeo."
                    } else {
                        "Não foi possível baixar: $videoTitle"
                    },
                    failed = true,
                )
            }
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
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
            "Downloads do TrueTagger",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progresso dos downloads de áudio"
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
        fun start(context: Context, url: String) {
            val intent = Intent(context, YoutubeDownloadService::class.java).apply {
                action = ShareContract.ACTION_START_DOWNLOAD
                putExtra(ShareContract.EXTRA_SHARED_URL, url)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
