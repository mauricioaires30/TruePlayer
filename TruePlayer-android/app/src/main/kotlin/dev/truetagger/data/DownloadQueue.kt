package dev.truetagger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** De onde vem o item: música solta, faixa de playlist ou faixa de álbum (muda só o texto do botão de cancelar). */
@Serializable
enum class DownloadKind { SINGLE, PLAYLIST, ALBUM }

/**
 * Um item da fila de downloads. [expand] é um link de playlist ainda não lido; os demais são músicas
 * soltas ou faixas de uma playlist ([playlist] guarda o nome da playlist do app e [position] a ordem original).
 */
@Serializable
data class QueuedDownload(
    val id: Long,
    val url: String,
    val expand: Boolean = false,
    val playlist: String? = null,
    val total: Int = 0,
    val position: Int = 0,
    val kind: DownloadKind = DownloadKind.SINGLE,
    /** Música do Spotify: o [url] é só o link dela; o áudio vem de onde a busca achar. */
    val hint: TrackHint? = null,
    /** Falso nas faixas de um álbum: elas só viram músicas na biblioteca, sem criar playlist. */
    val addToPlaylist: Boolean = true,
)

/** Quantas músicas de uma playlist já baixaram, quantas falharam e quantas foram canceladas. */
@Serializable
data class QueueJobCounts(val done: Int = 0, val failed: Int = 0, val canceled: Int = 0)

/**
 * Fila de downloads guardada em arquivo, para continuar de onde parou se o processo for encerrado.
 * Um item só sai do arquivo depois de terminar (com sucesso ou erro).
 */
class DownloadQueueStore(private val file: File) {
    @Serializable
    private data class Dto(
        val schemaVersion: Int = SCHEMA,
        val nextId: Long = 1,
        val items: List<QueuedDownload> = emptyList(),
        val jobs: Map<String, QueueJobCounts> = emptyMap(),
    )

    @Synchronized
    fun load(): List<QueuedDownload> = read().items

    @Synchronized
    fun add(
        url: String,
        expand: Boolean = false,
        playlist: String? = null,
        total: Int = 0,
        position: Int = 0,
        kind: DownloadKind = if (playlist != null) DownloadKind.PLAYLIST else DownloadKind.SINGLE,
        hint: TrackHint? = null,
        addToPlaylist: Boolean = true,
    ): QueuedDownload {
        val dto = read()
        val item = QueuedDownload(dto.nextId, url, expand, playlist, total, position, kind, hint, addToPlaylist)
        write(dto.copy(nextId = dto.nextId + 1, items = dto.items + item))
        return item
    }

    @Synchronized
    fun remove(id: Long) {
        val dto = read()
        if (dto.items.none { it.id == id }) return
        write(dto.copy(items = dto.items.filterNot { it.id == id }))
    }

    @Synchronized
    fun contains(id: Long): Boolean = read().items.any { it.id == id }

    /**
     * Cancela o que falta de uma playlist ou álbum: tira os itens da fila (menos o [exceptId], que está baixando e
     * se cancela sozinho) e soma ao contador de cancelados. Devolve os itens tirados.
     */
    @Synchronized
    fun removeJob(playlist: String, exceptId: Long? = null): List<QueuedDownload> = removeJob(playlist, setOfNotNull(exceptId))

    /** Como [removeJob], deixando de fora todas as que estão baixando agora ([exceptIds]). */
    @Synchronized
    fun removeJob(playlist: String, exceptIds: Set<Long>): List<QueuedDownload> {
        val dto = read()
        val removed = dto.items.filter { it.playlist == playlist && it.id !in exceptIds }
        if (removed.isEmpty()) return emptyList()
        val now = dto.jobs[playlist] ?: QueueJobCounts()
        write(dto.copy(items = dto.items - removed.toSet(), jobs = dto.jobs + (playlist to now.copy(canceled = now.canceled + removed.size))))
        return removed
    }

    @Synchronized
    fun recordCanceled(playlist: String) {
        val dto = read()
        val now = dto.jobs[playlist] ?: QueueJobCounts()
        write(dto.copy(jobs = dto.jobs + (playlist to now.copy(canceled = now.canceled + 1))))
    }

    /** Itens da playlist que ainda faltam, sem contar o [exceptId]. */
    @Synchronized
    fun pendingFor(playlist: String, exceptId: Long): Int =
        read().items.count { it.playlist == playlist && it.id != exceptId }

    @Synchronized
    fun record(playlist: String, success: Boolean) {
        val dto = read()
        val now = dto.jobs[playlist] ?: QueueJobCounts()
        val next = if (success) now.copy(done = now.done + 1) else now.copy(failed = now.failed + 1)
        write(dto.copy(jobs = dto.jobs + (playlist to next)))
    }

    /** Devolve os totais da playlist e tira o registro dela. */
    @Synchronized
    fun finishJob(playlist: String): QueueJobCounts {
        val dto = read()
        val counts = dto.jobs[playlist] ?: QueueJobCounts()
        write(dto.copy(jobs = dto.jobs - playlist))
        return counts
    }

    private fun read(): Dto {
        val text = runCatching { if (file.isFile) file.readText() else null }.getOrNull() ?: return Dto()
        val dto = runCatching { json.decodeFromString<Dto>(text) }.getOrNull() ?: return Dto()
        return if (dto.schemaVersion > SCHEMA) Dto() else dto
    }

    private fun write(dto: Dto) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(json.encodeToString(dto))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private companion object {
        const val SCHEMA = 1
        val json = Json { ignoreUnknownKeys = true }
    }
}
