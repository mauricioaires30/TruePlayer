package dev.truetagger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/** Tempo ouvido e quantidade de plays. */
data class StatValue(val ms: Long = 0L, val plays: Long = 0L) {
    val minutes: Long get() = ms / 60_000L
    operator fun plus(other: StatValue) = StatValue(ms + other.ms, plays + other.plays)
}

enum class StatsRange(val label: String, val days: Int?) {
    ALL("Sempre", null),
    WEEK("Semana", 7),
    MONTH("Mês", 30),
    SIX_MONTHS("6 meses", 183),
    YEAR("Ano", 365),
}

@Serializable
data class DayEntryDto(val ms: Long = 0L, val plays: Long = 0L)

@Serializable
data class StatsDto(
    val schemaVersion: Int = PlayStats.SCHEMA,
    /** data ISO (aaaa-mm-dd) -> caminho relativo da música -> totais do dia. */
    val days: Map<String, Map<String, DayEntryDto>> = emptyMap(),
    /** Plays de antes das estatísticas por tempo (só contam em "Sempre"). */
    val legacyPlays: Map<String, Long> = emptyMap(),
)

/**
 * Estatísticas de reprodução por dia. A chave é o caminho relativo da música, então o arquivo
 * `.trueplayer/stats.json` vale em qualquer celular. Não é thread-safe: quem usa sincroniza.
 */
class PlayStats {
    companion object {
        const val SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Devolve null se o texto não for um arquivo de estatísticas válido (nunca lança exceção). */
        fun parse(text: String): PlayStats? = runCatching {
            val dto = json.decodeFromString<StatsDto>(text)
            PlayStats().also { stats ->
                dto.days.forEach { (date, perTrack) ->
                    perTrack.forEach { (key, v) -> stats.days.getOrPut(date) { HashMap() }[key] = StatValue(v.ms, v.plays) }
                }
                stats.legacy.putAll(dto.legacyPlays)
                stats.futureSchema = dto.schemaVersion > SCHEMA
            }
        }.getOrNull()
    }

    private val days = HashMap<String, HashMap<String, StatValue>>()
    private val legacy = HashMap<String, Long>()

    /** Arquivo gravado por uma versão mais nova do app: pode ser lido, mas não deve ser reescrito. */
    var futureSchema = false
        private set

    val isEmpty: Boolean get() = days.isEmpty() && legacy.isEmpty()

    fun add(date: LocalDate, key: String, ms: Long, plays: Long) {
        if (key.isBlank() || (ms <= 0L && plays <= 0L)) return
        val perTrack = days.getOrPut(date.toString()) { HashMap() }
        perTrack[key] = (perTrack[key] ?: StatValue()) + StatValue(maxOf(ms, 0L), maxOf(plays, 0L))
    }

    /** Zera tudo (usado pelo botão de resetar estatísticas). */
    fun clear() {
        days.clear()
        legacy.clear()
    }

    fun addLegacy(key: String, plays: Long) {
        if (key.isBlank() || plays <= 0L) return
        legacy[key] = maxOf(legacy[key] ?: 0L, plays)
    }

    /** Totais por música no intervalo; "Sempre" inclui os plays antigos. */
    fun totals(range: StatsRange, today: LocalDate): Map<String, StatValue> {
        val result = HashMap<String, StatValue>()
        val firstDay = range.days?.let { today.minusDays(it - 1L) }
        days.forEach { (dateText, perTrack) ->
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return@forEach
            if (date.isAfter(today) || (firstDay != null && date.isBefore(firstDay))) return@forEach
            perTrack.forEach { (key, v) -> result[key] = (result[key] ?: StatValue()) + v }
        }
        if (range == StatsRange.ALL) {
            legacy.forEach { (key, plays) -> result[key] = (result[key] ?: StatValue()) + StatValue(0L, plays) }
        }
        return result
    }

    /**
     * Junta dados de outra fonte (cache local × arquivo da pasta) sem contar nada duas vezes:
     * para cada dia e música vale o maior valor.
     */
    fun mergeMax(other: PlayStats) {
        other.days.forEach { (date, perTrack) ->
            val mine = days.getOrPut(date) { HashMap() }
            perTrack.forEach { (key, v) ->
                val current = mine[key]
                mine[key] = if (current == null) v else StatValue(maxOf(current.ms, v.ms), maxOf(current.plays, v.plays))
            }
        }
        other.legacy.forEach { (key, plays) -> addLegacy(key, plays) }
    }

    fun toJson(): String = json.encodeToString(
        StatsDto(
            days = days.mapValues { (_, perTrack) -> perTrack.mapValues { DayEntryDto(it.value.ms, it.value.plays) } },
            legacyPlays = legacy.toMap(),
        ),
    )

    /** Mesmo conteúdo? (para saber se vale regravar o arquivo) */
    fun sameContentAs(other: PlayStats): Boolean = days == other.days && legacy == other.legacy
}
