package dev.truetagger.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Confere uma vez por dia (com internet) se saiu uma versão nova e avisa por notificação. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { UpdateManager.checkAndNotify(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val NAME = "trueplayer-update-check"

        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (!enabled) {
                manager.cancelUniqueWork(NAME)
                return
            }
            // Funciona com o app fechado: o Android roda a conferência sozinho e o app avisa por notificação.
            // De hora em hora (o Android pode atrasar quando o celular está parado ou economizando bateria).
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE troca o intervalo de quem já tinha o agendamento antigo, sem reiniciar a contagem.
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
