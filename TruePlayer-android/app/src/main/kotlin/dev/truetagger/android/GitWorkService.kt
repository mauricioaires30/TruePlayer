package dev.truetagger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Se alguma tela do app está aberta agora (para não repetir em notificação o que já aparece na tela). */
internal object AppVisibility {
    @Volatile var visible = false
}

/**
 * Mantém um commit, envio ou download do Git rodando com o app em segundo plano ou fechado, com o andamento
 * na notificação. O trabalho em si roda no [GitController]; este serviço segura o processo vivo, deixa o
 * processador e o Wi-Fi acordados com a tela apagada (senão o envio fica muito mais lento) e mostra o progresso.
 */
class GitWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var inForeground = false
    private var lastStartId = 0

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        ensureChannels()
        goForeground(GitController.ui.value)
        scope.launch {
            // Uma atualização por segundo é o que a notificação aguenta sem o Android descartar.
            GitController.ui.sample(1_000).collect { ui ->
                if (ui.busy != null) {
                    if (!inForeground) goForeground(ui) else notifyManager().notify(PROGRESS_ID, progress(ui))
                } else {
                    finishWork(ui)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            GitController.cancel()
            return START_NOT_STICKY
        }
        // Todo startForegroundService precisa de um startForeground, mesmo que a operação já tenha acabado
        // (senão o Android encerra o app). Se já acabou, sai logo em seguida.
        val ui = GitController.ui.value
        goForeground(ui)
        if (ui.busy == null) finishWork(ui)
        return START_NOT_STICKY
    }

    // Android 15+: um serviço de sincronização só pode rodar ~6 h por dia. Ao chegar no limite ele precisa parar
    // na hora; a operação é cancelada (o que já foi enviado fica salvo e o próximo envio continua dali).
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForTimeout()
    }

    override fun onTimeout(startId: Int) {
        stopForTimeout()
    }

    private fun stopForTimeout() {
        GitController.cancel()
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        inForeground = false
        stopSelf()
    }

    private fun goForeground(ui: GitUi) {
        if (wakeLock == null) acquireLocks()
        val notification = progress(ui)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, notification)
            }
            inForeground = true
        }
    }

    private fun finishWork(ui: GitUi) {
        // Outra operação pode ter começado logo depois desta; nesse caso o serviço continua.
        if (GitController.ui.value.busy != null) return
        releaseLocks()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        inForeground = false
        val text = ui.message ?: if (ui.replaceBranch != null) t(R.string.git_abra_o_app_para_confirmar) else null
        if (text != null && !AppVisibility.visible) notifyManager().notify(RESULT_ID, result(text))
        stopSelf(lastStartId)
    }

    private fun progress(ui: GitUi): Notification {
        val overall = ui.overallFraction
        val percent = overall?.let { (it * 100).toInt() }
        val title = ui.busyTitle.ifBlank { "Git" } + (percent?.let { " • $it%" } ?: "")
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(ui.busy.orEmpty())
            .setSubText(if (ui.busySteps > 0) t(R.string.git_etapa_de, ui.busyStep, ui.busySteps) else null)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(0, t(R.string.cancelar), cancelIntent())
            .build()
    }

    private fun result(text: String): Notification =
        NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(if (text.startsWith(t(R.string.git_nao_deu_certo, "").trimEnd())) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload_done)
            .setContentTitle("GitHub")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 31, Intent(this, MainActivity::class.java).apply { action = ShareContract.ACTION_OPEN_APP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 32, Intent(this, GitWorkService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acquireLocks() {
        runCatching {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TruePlayer:git")
                .apply { setReferenceCounted(false); acquire(MAX_WORK_MS) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TruePlayer:git")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notifyManager()
        manager.createNotificationChannel(NotificationChannel(PROGRESS_CHANNEL, t(R.string.git_git_em_andamento), NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(RESULT_CHANNEL, t(R.string.git_git_concluido), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notifyManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PROGRESS_CHANNEL = "truetagger_git_progress"
        private const val RESULT_CHANNEL = "truetagger_git_result"
        private const val PROGRESS_ID = 501
        private const val RESULT_ID = 502
        private const val ACTION_CANCEL = "dev.truetagger.git.CANCEL"

        /** Limite de segurança: nenhuma operação segura o aparelho acordado por mais de 6 horas. */
        private const val MAX_WORK_MS = 6L * 60L * 60L * 1000L

        fun start(context: Context) {
            val intent = Intent(context, GitWorkService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }
        }
    }
}
