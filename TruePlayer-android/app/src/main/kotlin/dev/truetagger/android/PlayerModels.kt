package dev.truetagger.android

import dev.truetagger.data.MigrationPlanner
import dev.truetagger.data.PlayStats
import dev.truetagger.data.StatValue
import dev.truetagger.data.StatsRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.truetagger.model.AudioTrack

enum class RepeatMode { OFF, ONE }


data class PlaybackRequest(
    val track: AudioTrack,
    val queue: List<AudioTrack>,
    val index: Int,
    val shuffle: Boolean = false,
    val sourceType: String = MusicPlaybackService.SOURCE_LIBRARY,
    val sourceName: String = "",
    val restorePositionMs: Long? = null,
)

/**
 * Ponte em memória entre a UI e o foreground service.
 * A fila nunca é persistida: ela só fica aqui até o service consumi-la.
 */
object PlaybackRequests {
    private var pending: PlaybackRequest? = null

    @Synchronized
    fun set(request: PlaybackRequest) {
        pending = request.copy(queue = request.queue.toList())
    }

    @Synchronized
    fun take(): PlaybackRequest? {
        val request = pending
        pending = null
        return request
    }
}

data class PlayerSnapshot(
    val current: AudioTrack? = null,
    val queue: List<AudioTrack> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Quando [positionMs] foi medida (relógio do aparelho, `SystemClock.elapsedRealtime`); 0 = desconhecido. */
    val updatedAtMs: Long = 0L,
)

object PlayerState {
    /** Tocando agora (true) ou pausado/parado (false). Só a música atual da lista lê isto, para animar. */
    val playing = kotlinx.coroutines.flow.MutableStateFlow(false)

    @Volatile var snapshot: PlayerSnapshot = PlayerSnapshot()

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun publish(snapshot: PlayerSnapshot) {
        this.snapshot = snapshot
        _revision.value = _revision.value + 1L
    }
}

/**
 * Único sinal de invalidação do aplicativo. Qualquer mudança que possa afetar
 * mais de uma tela dispara bump(), e as telas interessadas coletam este fluxo.
 * Assim não precisamos criar um observer/polling diferente para cada recurso.
 */
object AppEvents {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    fun bump() {
        _revision.value = _revision.value + 1L
    }
}

/**
 * Estatísticas de reprodução: tempo ouvido e plays por música e por dia.
 *
 * A chave é o caminho relativo da música (ver [dev.truetagger.model.TrackKeys]). Os dados ficam em
 * cache local e, em lote, em `.trueplayer/stats.json` na pasta de músicas, para sobreviver à troca
 * de celular. Os plays antigos (preferências `truetagger_play_stats`, por content URI) são
 * convertidos quando a biblioteca já dá para resolver o caminho; o dado antigo nunca é apagado.
 */
object PlayStatsState {
    private const val LEGACY_PREFS = "truetagger_play_stats"
    private const val LEGACY_PREFIX = "track|"
    private const val CACHE_PREFS = "truetagger_stats_v2"
    private const val KEY_JSON = "json"
    private const val KEY_LEGACY_DONE = "legacy_done"
    private const val LOCAL_SAVE_EVERY_MS = 15_000L
    private const val FOLDER_SAVE_EVERY_MS = 90_000L

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val stats = PlayStats()
    private val legacyById = LinkedHashMap<String, Long>()
    private var initialized = false
    private var lastLocalSaveMs = 0L
    private var lastFolderSaveMs = 0L
    private var dirty = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folderLock = Mutex()

    /** Chave estável da faixa: caminho relativo; só cai no id bruto se ele ainda não for conhecido. */
    fun keyFor(track: AudioTrack): String =
        track.relPath.ifBlank { LibraryIndex.track(track.id)?.relPath.orEmpty() }.ifBlank { track.id }

    @Synchronized
    fun initialize(context: android.content.Context) {
        if (initialized) return
        val app = context.applicationContext
        app.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_JSON, null)
            ?.let { PlayStats.parse(it) }?.let(stats::mergeMax)
        app.getSharedPreferences(LEGACY_PREFS, android.content.Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (key.startsWith(LEGACY_PREFIX) && value is Number) legacyById[key.removePrefix(LEGACY_PREFIX)] = value.toLong()
        }
        initialized = true
    }

    /** Converte os plays antigos (por content URI) para caminho relativo, quando a biblioteca já resolve. */
    @Synchronized
    fun convertLegacy(context: android.content.Context) {
        initialize(context)
        if (!LibraryIndex.reliable || legacyById.isEmpty()) return
        val prefs = context.applicationContext.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
        val done = prefs.getStringSet(KEY_LEGACY_DONE, emptySet()).orEmpty().toMutableSet()
        var changed = false
        legacyById.forEach { (id, plays) ->
            if (id in done) return@forEach
            val relPath = LibraryIndex.track(id)?.relPath.orEmpty()
            if (relPath.isBlank()) return@forEach
            stats.addLegacy(relPath, plays)
            done += id
            changed = true
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_LEGACY_DONE, done).apply()
            dirty = true
            saveLocal(context)
            _revision.value = _revision.value + 1L
        }
    }

    /** Conta um play no momento em que a música começa (início, retomada de uma faixa ainda não contada ou repetição). */
    @Synchronized
    fun countPlay(context: android.content.Context, key: String) {
        if (key.isBlank()) return
        initialize(context)
        stats.add(LocalDate.now(), key, 0L, 1L)
        dirty = true
        saveLocal(context)
        _revision.value = _revision.value + 1L
    }

    /** Soma [deltaMs] de tempo ouvido. */
    @Synchronized
    fun addListening(context: android.content.Context, key: String, deltaMs: Long) {
        if (key.isBlank() || deltaMs <= 0L) return
        initialize(context)
        stats.add(LocalDate.now(), key, deltaMs, 0L)
        dirty = true
        var bump = false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLocalSaveMs >= LOCAL_SAVE_EVERY_MS) {
            saveLocal(context)
            bump = true
        }
        if (now - lastFolderSaveMs >= FOLDER_SAVE_EVERY_MS) scheduleFolderWrite(context)
        if (bump) _revision.value = _revision.value + 1L
    }

    /** Minutos ouvidos hoje (para a mensagem de commit). */
    @Synchronized
    fun minutesToday(context: android.content.Context): Long {
        initialize(context)
        return stats.totalMsOn(LocalDate.now()) / 60_000L
    }

    @Synchronized
    fun totals(context: android.content.Context, range: StatsRange): Map<String, StatValue> {
        initialize(context)
        return stats.totals(range, LocalDate.now())
    }

    /**
     * Zera as estatísticas. Antes guarda uma cópia em `.trueplayer/backup/` e esvazia também o
     * `stats.json` da pasta (senão ele voltaria na próxima sincronização). Os plays antigos
     * (formato antigo) são marcados como já tratados para não reaparecerem.
     */
    suspend fun reset(context: android.content.Context) {
        val app = context.applicationContext
        initialize(app)
        val backup: String? = synchronized(this) {
            val previous = if (stats.isEmpty) null else stats.toJson()
            stats.clear()
            val prefs = app.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_LEGACY_DONE, legacyById.keys.toSet()).apply()
            dirty = false
            saveLocal(app)
            _revision.value = _revision.value + 1L
            previous
        }
        folderLock.withLock {
            runCatching {
                val tree = MusicFolder.tree(app) ?: return@runCatching
                val metaDir = tree.ensureDir(tree.rootDocId, MigrationPlanner.META_DIR) ?: return@runCatching
                if (backup != null) {
                    val backupDir = tree.ensureDir(metaDir, "backup") ?: return@runCatching
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    tree.writeTextFile(backupDir, "stats-$stamp.json", backup, "application/json")
                }
                tree.writeTextFile(metaDir, "stats.json", PlayStats().toJson(), "application/json")
            }
        }
    }

    /** Grava agora (pausa, app em segundo plano, serviço encerrando). */
    @Synchronized
    fun flush(context: android.content.Context) {
        if (!initialized || !dirty) return
        saveLocal(context)
        scheduleFolderWrite(context)
    }

    /** Como [flush], mas só volta depois de gravar o `stats.json` na pasta. Usado antes de um commit do Git (fora da thread principal). */
    fun flushNow(context: android.content.Context) {
        val app = context.applicationContext
        if (!initialized) return
        synchronized(this) { saveLocal(app) }
        kotlinx.coroutines.runBlocking { folderLock.withLock { runCatching { syncFolder(app, write = true) } } }
    }

    /** Junta o `.trueplayer/stats.json` da pasta com o que há no aparelho. Chame depois de uma varredura confiável. */
    suspend fun loadFromFolder(context: android.content.Context) {
        val app = context.applicationContext
        initialize(app)
        folderLock.withLock { runCatching { syncFolder(app, write = false) } }
    }

    private fun saveLocal(context: android.content.Context) {
        context.applicationContext.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY_JSON, stats.toJson()).apply()
        lastLocalSaveMs = android.os.SystemClock.elapsedRealtime()
    }

    private fun scheduleFolderWrite(context: android.content.Context) {
        lastFolderSaveMs = android.os.SystemClock.elapsedRealtime()
        val app = context.applicationContext
        scope.launch { folderLock.withLock { runCatching { syncFolder(app, write = true) } } }
    }

    private fun syncFolder(context: android.content.Context, write: Boolean) {
        val tree = MusicFolder.tree(context) ?: return
        val metaDir = tree.ensureDir(tree.rootDocId, MigrationPlanner.META_DIR) ?: return
        val existing = tree.child(metaDir, "stats.json")
        val fromFile = existing?.let { tree.readText(it.docId) }?.let { PlayStats.parse(it) }
        // Arquivo de uma versão mais nova do app: lê, mas não reescreve por cima.
        if (fromFile?.futureSchema == true && write) return
        val json = synchronized(this) {
            fromFile?.let(stats::mergeMax)
            if (fromFile != null) _revision.value = _revision.value + 1L
            if (!write || (fromFile != null && stats.sameContentAs(fromFile))) null else stats.toJson()
        } ?: return
        tree.writeTextFile(metaDir, "stats.json", json, "application/json")
    }
}
