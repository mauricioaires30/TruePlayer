package dev.truetagger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.truetagger.data.ReleaseInfo
import dev.truetagger.data.ReleaseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** O que a interface mostra sobre atualizações. */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class UpToDate(val version: String) : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    data class Downloading(val release: ReleaseInfo, val percent: Int) : UpdateUi
    data class NeedsInstallPermission(val release: ReleaseInfo) : UpdateUi
    data class Failed(val message: String) : UpdateUi
}

/**
 * Atualização do app pelo GitHub Releases: consulta a última release, baixa o APK e instala com o
 * instalador do Android (que sempre pede a confirmação do usuário). O APK novo precisa estar
 * assinado com a mesma chave do instalado, então os dados do app não são perdidos.
 */
object UpdateManager {
    private const val REPO = "mauricioaires30/TruePlayer"
    private const val PREFS = "truetagger_updates"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_NOTES_VERSION = "whats_new_version"
    private const val KEY_NOTES_TEXT = "whats_new_notes"
    private const val CHANNEL = "truetagger_updates"
    private const val NOTIFICATION_ID = 401
    const val ACTION_INSTALL_RESULT = "dev.truetagger.action.UPDATE_INSTALL_RESULT"
    private const val MIN_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    /** Ao abrir o app o intervalo é curto, só para não bater na API do GitHub a cada abertura seguida. */
    private const val MIN_START_CHECK_INTERVAL_MS = 5L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun installedVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }.getOrDefault("")

    /** Fecha o aviso atual (o usuário escolheu "Depois" ou leu o resultado). */
    fun dismiss() {
        _ui.value = UpdateUi.Idle
    }

    /**
     * Consulta o GitHub. [manual] mostra "você já está na versão mais recente" e erros. Sem [manual],
     * a conferência é silenciosa: só aparece algo se existir versão nova. [force] ignora o intervalo
     * mínimo entre conferências (usado ao abrir o app).
     */
    fun check(context: Context, manual: Boolean, force: Boolean = false) {
        val app = context.applicationContext
        if (!manual) {
            val last = prefs(app).getLong(KEY_LAST_CHECK, 0L)
            val minimum = if (force) MIN_START_CHECK_INTERVAL_MS else MIN_CHECK_INTERVAL_MS
            if (System.currentTimeMillis() - last < minimum) return
        }
        if (_ui.value is UpdateUi.Downloading) return
        if (manual) _ui.value = UpdateUi.Checking
        scope.launch {
            val result = fetchLatest()
            prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val installed = installedVersion(app)
            result.onSuccess { release ->
                when {
                    release != null && ReleaseParser.isNewer(release, installed) -> _ui.value = UpdateUi.Available(release)
                    manual -> _ui.value = UpdateUi.UpToDate(installed)
                    else -> if (_ui.value is UpdateUi.Checking) _ui.value = UpdateUi.Idle
                }
            }.onFailure {
                _ui.value = if (manual) UpdateUi.Failed("Não consegui consultar o GitHub. Confira a internet e tente de novo.") else UpdateUi.Idle
            }
        }
    }

    /** Usado pelo agendamento em segundo plano: consulta e, se houver versão nova, avisa por notificação. */
    suspend fun checkAndNotify(context: Context) {
        val app = context.applicationContext
        val release = fetchLatest().getOrNull() ?: return
        prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (!ReleaseParser.isNewer(release, installedVersion(app))) return
        if (prefs(app).getString(KEY_NOTIFIED, "") == release.version.toString()) return
        prefs(app).edit().putString(KEY_NOTIFIED, release.version.toString()).apply()
        _ui.value = UpdateUi.Available(release)
        notifyAvailable(app, release)
    }

    private suspend fun fetchLatest(): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TruePlayer")
            }
            try {
                when (connection.responseCode) {
                    200 -> ReleaseParser.parse(connection.inputStream.bufferedReader().use { it.readText() })
                    404 -> null // ainda não há nenhuma release publicada
                    else -> error("GitHub respondeu ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Abre a tela do Android onde o usuário libera "instalar apps desconhecidos" para o TruePlayer. */
    fun openInstallPermissionSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Baixa o APK e entrega ao instalador. Se faltar a permissão, pede e espera o usuário voltar. */
    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        val app = context.applicationContext
        if (!canInstallPackages(app)) {
            _ui.value = UpdateUi.NeedsInstallPermission(release)
            openInstallPermissionSettings(app)
            return
        }
        if (_ui.value is UpdateUi.Downloading) return
        _ui.value = UpdateUi.Downloading(release, 0)
        // Guarda as novidades para mostrar na primeira abertura depois de atualizar.
        prefs(app).edit().putString(KEY_NOTES_VERSION, release.version.toString()).putString(KEY_NOTES_TEXT, release.notes).apply()
        scope.launch {
            runCatching {
                val file = download(app, release) { percent ->
                    if (_ui.value is UpdateUi.Downloading) _ui.value = UpdateUi.Downloading(release, percent)
                }
                install(app, file)
            }.onFailure {
                _ui.value = UpdateUi.Failed("Não deu pra atualizar: ${it.message ?: "erro desconhecido"}. Seus dados não foram alterados.")
            }
        }
    }

    private fun download(context: Context, release: ReleaseInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val target = File(dir, release.apkName)
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "TruePlayer")
        }
        try {
            check(connection.responseCode == 200) { "o download respondeu ${connection.responseCode}" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
                        }
                    }
                }
            }
            check(target.length() > 0) { "o arquivo baixado veio vazio" }
            if (release.apkSizeBytes > 0) check(target.length() == release.apkSizeBytes) { "o arquivo baixado está incompleto" }
            release.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual == expected) { "o arquivo baixado não confere com o publicado" }
            }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallStatus(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _ui.value = UpdateUi.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> _ui.value = UpdateUi.Idle // o usuário cancelou
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val conflict = status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                _ui.value = UpdateUi.Failed(
                    if (conflict) "O Android recusou: este app foi instalado com outra assinatura. Seus dados não foram alterados."
                    else "A instalação não terminou. $reason".trim(),
                )
            }
        }
    }

    // ---- novidades depois de atualizar

    /** Novidades da versão instalada, se acabou de ser instalada por uma atualização feita pelo app. */
    fun takeWhatsNew(context: Context): Pair<String, String>? {
        val app = context.applicationContext
        val saved = prefs(app).getString(KEY_NOTES_VERSION, null) ?: return null
        val installed = installedVersion(app)
        if (saved != installed) return null
        val notes = prefs(app).getString(KEY_NOTES_TEXT, "").orEmpty()
        prefs(app).edit().remove(KEY_NOTES_VERSION).remove(KEY_NOTES_TEXT).apply()
        return installed to notes.ifBlank { "Sem notas para esta versão." }
    }

    // ---- notificação

    /** Primeiras linhas das novidades, sem os símbolos do Markdown, para o corpo da notificação. */
    private fun notesPreview(notes: String): String {
        val lines = dev.truetagger.data.SimpleMarkdown.parse(notes, dropLeadingHeading = true)
            .filter { it.kind != dev.truetagger.data.MdKind.BLANK }
            .take(4)
            .map { line -> (if (line.kind == dev.truetagger.data.MdKind.BULLET) "• " else "") + line.spans.joinToString("") { it.text } }
        return if (lines.isEmpty()) "Toque para ver as novidades e atualizar" else lines.joinToString("\n")
    }

    private fun notifyAvailable(context: Context, release: ReleaseInfo) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Atualizações do TruePlayer", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).apply { action = ShareContract.ACTION_OPEN_APP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("TruePlayer ${release.version} disponível")
            .setContentText("Toque para ver as novidades e atualizar")
            .setStyle(Notification.BigTextStyle().bigText(notesPreview(release.notes)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

/** Recebe o resultado da instalação feita pelo PackageInstaller. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UpdateManager.ACTION_INSTALL_RESULT) UpdateManager.onInstallStatus(context, intent)
    }
}
