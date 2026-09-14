package dev.truetagger.android

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
)

object PlayerState {
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
 * Estado observável dos números de reprodução.
 * Os valores continuam persistidos em SharedPreferences, mas a UI não precisa
 * reler o arquivo a cada recomposição: o serviço atualiza este cache e dispara
 * uma revisão imediatamente.
 */
object PlayStatsState {
    private const val PREFS = "truetagger_play_stats"
    private const val PREFIX = "track|"

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val counts = LinkedHashMap<String, Long>()
    private var initialized = false

    @Synchronized
    fun initialize(context: android.content.Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX) && value is Number) {
                counts[key.removePrefix(PREFIX)] = value.toLong()
            }
        }
        initialized = true
    }

    @Synchronized
    fun record(context: android.content.Context, trackId: String): Long {
        if (trackId.isBlank()) return 0L
        initialize(context)
        val next = (counts[trackId] ?: 0L) + 1L
        counts[trackId] = next
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putLong(PREFIX + trackId, next)
            .apply()
        _revision.value = _revision.value + 1L
        return next
    }

    @Synchronized
    fun count(context: android.content.Context, trackId: String): Long {
        initialize(context)
        return counts[trackId] ?: 0L
    }

    @Synchronized
    fun snapshot(context: android.content.Context): Map<String, Long> {
        initialize(context)
        return counts.toMap()
    }
}
