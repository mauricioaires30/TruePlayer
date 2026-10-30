package dev.truetagger.android

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.truetagger.data.M3u
import dev.truetagger.data.PlaylistEntry
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.TrackKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.util.Locale

/** [trackIds] são ids de [AudioTrack] já resolvidos na biblioteca atual (entradas pendentes ficam de fora). */
internal data class PlaylistData(val name: String, val trackIds: List<String>)

/** Playlist como é guardada: lista de entradas portáteis, inclusive as que ainda não existem neste aparelho. */
internal data class StoredPlaylist(val name: String, val entries: List<PlaylistEntry>)

/**
 * Índice da biblioteca atual, usado para traduzir entradas portáteis (caminho relativo) em
 * faixas. [reliable] só vira true depois de uma varredura que terminou sem erro — enquanto
 * isso não acontece, nada pode ser considerado "removido".
 */
internal object LibraryIndex {
    @Volatile private var byId: Map<String, AudioTrack> = emptyMap()
    @Volatile private var byRel: Map<String, AudioTrack> = emptyMap()
    @Volatile private var byName: Map<String, List<AudioTrack>> = emptyMap()
    @Volatile var reliable: Boolean = false
        private set

    fun update(tracks: List<AudioTrack>, reliable: Boolean) {
        byId = tracks.associateBy { it.id }
        byRel = tracks.filter { it.relPath.isNotBlank() }.associateBy { TrackKeys.compareKey(it.relPath) }
        byName = tracks.groupBy { it.displayName.lowercase(Locale.ROOT) }
        this.reliable = reliable
    }

    fun track(id: String): AudioTrack? = byId[id]

    /** Todas as músicas da última varredura. */
    fun all(): List<AudioTrack> = byId.values.toList()

    fun resolve(entry: PlaylistEntry): AudioTrack? {
        val key = entry.key
        if (key.startsWith("content:")) return byId[key]
        byRel[TrackKeys.compareKey(key)]?.let { return it }
        // Nome de arquivo único na biblioteca: cobre playlists antigas e arquivos movidos de subpasta.
        if (!reliable) return null
        val name = key.replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)
        return byName[name]?.singleOrNull()
    }

    /** Chave portátil para guardar na playlist; cai no id bruto se o caminho ainda não é conhecido. */
    fun entryFor(trackId: String): PlaylistEntry {
        val track = byId[trackId] ?: return PlaylistEntry(trackId)
        return PlaylistEntry(
            key = track.relPath.ifBlank { track.id },
            label = listOf(track.artist, track.title.ifBlank { track.displayName.substringBeforeLast('.') })
                .filter { it.isNotBlank() }.joinToString(" - "),
            durationSec = (track.durationMs / 1000L).toInt(),
        )
    }
}

internal class PlaylistStore(private val context: Context) {
    companion object {
        private val _revision = MutableStateFlow(0L)
        val revision: StateFlow<Long> = _revision.asStateFlow()

        private const val PREFS = "truetagger_player"
        private const val KEY_V1 = "playlists_json"
        private const val KEY_V2 = "playlists_v2"
        private const val SCHEMA = 2

        private fun bumpRevision() {
            _revision.value = _revision.value + 1L
        }
    }

    @Serializable private data class StoredEntry(val key: String, val label: String = "", val durationSec: Int = -1)
    @Serializable private data class StoredItem(val name: String, val entries: List<StoredEntry>)
    @Serializable private data class StoredFile(val schemaVersion: Int = SCHEMA, val playlists: List<StoredItem> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tudo o que está guardado, inclusive entradas que não existem neste aparelho. */
    fun loadStored(): List<StoredPlaylist> {
        val raw = prefs.getString(KEY_V2, null)
        if (raw != null) {
            val parsed = runCatching { json.decodeFromString<StoredFile>(raw) }.getOrNull()
            if (parsed != null) {
                return parsed.playlists
                    .filter { it.name.isNotBlank() }
                    .map { item -> StoredPlaylist(item.name, item.entries.map { PlaylistEntry(it.key, it.label, it.durationSec) }) }
            }
        }
        return migrateLegacy()
    }

    /** Formato antigo: lista de ids de MediaStore. O dado antigo nunca é apagado nem alterado. */
    private fun migrateLegacy(): List<StoredPlaylist> {
        val raw = prefs.getString(KEY_V1, null) ?: return emptyList()
        val migrated = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    if (name.isBlank()) continue
                    val ids = obj.optJSONArray("trackIds") ?: JSONArray()
                    val entries = buildList {
                        for (j in 0 until ids.length()) {
                            val id = ids.optString(j)
                            if (id.isNotBlank()) add(LibraryIndex.entryFor(id))
                        }
                    }
                    add(StoredPlaylist(name, entries))
                }
            }
        }.getOrNull() ?: return emptyList()
        if (migrated.isNotEmpty()) writeStored(migrated, notify = false)
        return migrated
    }

    private fun writeStored(items: List<StoredPlaylist>, notify: Boolean = true) {
        val file = StoredFile(
            playlists = items.map { p -> StoredItem(p.name, p.entries.map { StoredEntry(it.key, it.label, it.durationSec) }) },
        )
        prefs.edit().putString(KEY_V2, json.encodeToString(file)).apply()
        if (notify) {
            bumpRevision()
            AppEvents.bump()
            PlaylistFolderSync.scheduleExport(context)
        }
    }

    /** Playlists como a interface usa: só faixas que existem na biblioteca atual, na ordem guardada. */
    fun load(): List<PlaylistData> = loadStored().map { it.toData() }

    private fun StoredPlaylist.toData() = PlaylistData(
        name,
        entries.mapNotNull { LibraryIndex.resolve(it)?.id }.distinct(),
    )

    private fun save(items: List<StoredPlaylist>) = writeStored(items)

    private fun mutate(name: String, transform: (StoredPlaylist) -> StoredPlaylist) {
        save(loadStored().map { if (it.name == name) transform(it) else it })
    }

    private fun idOf(entry: PlaylistEntry): String? = LibraryIndex.resolve(entry)?.id

    fun create(name: String, trackIds: List<String> = emptyList()) {
        createWithEntries(name, trackIds.distinct().map(LibraryIndex::entryFor))
    }

    fun createWithEntries(name: String, entries: List<PlaylistEntry>) {
        val clean = name.trim()
        val current = loadStored()
        if (clean.isBlank() || current.any { it.name.equals(clean, true) }) return
        save(current + StoredPlaylist(clean, entries))
    }

    /** Acrescenta no fim da playlist, mantendo a ordem de chegada (usado pelos downloads de playlist). */
    fun appendEntries(name: String, entries: List<PlaylistEntry>) {
        if (entries.isEmpty()) return
        mutate(name) { it.copy(entries = it.entries + entries) }
    }

    /** Põe [entries] na posição [index] (ou no fim, se a playlist for menor). */
    fun insertEntries(name: String, entries: List<PlaylistEntry>, index: Int) {
        if (entries.isEmpty()) return
        mutate(name) { stored ->
            val at = index.coerceIn(0, stored.entries.size)
            stored.copy(entries = stored.entries.take(at) + entries + stored.entries.drop(at))
        }
    }

    /** Nome que ainda não existe: "Treino" -> "Treino (2)". */
    fun uniqueNameFor(base: String): String = uniqueName(base.trim().ifBlank { t(R.string.playlist) })

    fun delete(name: String) {
        val current = loadStored()
        save(current.filterNot { it.name == name })
        PlaylistCovers.remove(context, name)
        PlaylistFolderSync.scheduleTrash(context, name)
    }

    fun rename(oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        val items = loadStored()
        if (clean.isBlank() || oldName == clean || items.any { it.name != oldName && it.name.equals(clean, true) } || items.none { it.name == oldName }) return false
        save(items.map { if (it.name == oldName) it.copy(name = clean) else it })
        PlaylistCovers.rename(context, oldName, clean)
        PlaylistFolderSync.scheduleTrash(context, oldName)
        return true
    }

    fun add(name: String, trackId: String) {
        mutate(name) { playlist ->
            if (playlist.entries.any { idOf(it) == trackId }) playlist
            else playlist.copy(entries = listOf(LibraryIndex.entryFor(trackId)) + playlist.entries)
        }
    }

    fun addAll(name: String, trackIds: List<String>) {
        val incoming = trackIds.distinct()
        if (incoming.isEmpty()) return
        mutate(name) { playlist ->
            val existing = playlist.entries.mapNotNull(::idOf).toHashSet()
            val fresh = incoming.filterNot(existing::contains).map(LibraryIndex::entryFor)
            playlist.copy(entries = fresh + playlist.entries)
        }
    }

    fun remove(name: String, trackId: String) = removeAll(name, listOf(trackId))

    fun removeAll(name: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val removeSet = trackIds.toHashSet()
        mutate(name) { playlist -> playlist.copy(entries = playlist.entries.filterNot { idOf(it) in removeSet }) }
    }

    /** Só para exclusões explícitas feitas pelo usuário no app. */
    fun removeTracksEverywhere(trackIds: Collection<String>): Boolean {
        val removeSet = trackIds.filter { it.isNotBlank() }.toHashSet()
        if (removeSet.isEmpty()) return false
        val current = loadStored()
        val cleaned = current.map { playlist ->
            val filtered = playlist.entries.filterNot { idOf(it) in removeSet || it.key in removeSet }
            if (filtered.size == playlist.entries.size) playlist else playlist.copy(entries = filtered)
        }
        if (cleaned == current) return false
        save(cleaned)
        return true
    }

    fun replaceOrderInMemory(current: List<PlaylistData>, name: String, orderedIds: List<String>): List<PlaylistData> {
        val cleanIds = orderedIds.distinct()
        return current.map { if (it.name == name) it.copy(trackIds = cleanIds) else it }
    }

    /** Persiste uma nova ordem vinda da interface; entradas pendentes (sem arquivo aqui) são preservadas no fim. */
    fun persist(items: List<PlaylistData>) {
        val stored = loadStored().associateBy { it.name }
        save(items.map { item ->
            val old = stored[item.name]
            val oldByTrack = old?.entries?.mapNotNull { e -> idOf(e)?.let { it to e } }?.toMap().orEmpty()
            val ordered = item.trackIds.map { id -> oldByTrack[id] ?: LibraryIndex.entryFor(id) }
            val pending = old?.entries?.filter { idOf(it) == null }.orEmpty()
            StoredPlaylist(item.name, ordered + pending)
        })
    }

    /** Troca chaves antigas (content URI, nome de arquivo) pelo caminho relativo, quando já dá para resolver. */
    fun upgradeKeys() {
        if (!LibraryIndex.reliable) return
        var changed = false
        val upgraded = loadStored().map { playlist ->
            playlist.copy(entries = playlist.entries.map { entry ->
                val track = LibraryIndex.resolve(entry)
                if (track != null && track.relPath.isNotBlank() && !TrackKeys.compareKey(entry.key).equals(TrackKeys.compareKey(track.relPath))) {
                    changed = true
                    LibraryIndex.entryFor(track.id)
                } else entry
            })
        }
        if (changed) save(upgraded)
    }

    /** Depois de mover arquivos de lugar: aponta as entradas para os novos caminhos (old -> new, por compareKey). */
    fun remapKeys(map: Map<String, String>) {
        if (map.isEmpty()) return
        var changed = false
        val current = loadStored()
        val remapped = current.map { it.copy(entries = M3u.remapKeys(it.entries, map)) }
        changed = remapped != current
        if (changed) save(remapped)
    }

    /** Cópia bruta de tudo o que está guardado (formato atual e antigo), para backup antes de migrações. */
    fun rawBackup(): String = buildString {
        append("{\"playlists_v2\":").append(prefs.getString(KEY_V2, null) ?: "null")
        append(",\"playlists_json\":").append(prefs.getString(KEY_V1, null) ?: "null")
        append("}")
    }

    /** Une playlists vindas da pasta (arquivos .m3u) com as locais, sem descartar nada. */
    fun mergeFromFolder(incoming: List<StoredPlaylist>, shouldReplace: (name: String) -> Boolean) {
        if (incoming.isEmpty()) return
        val current = loadStored()
        val byName = current.associateBy { it.name.lowercase(Locale.ROOT) }
        var changed = false
        val result = current.toMutableList()
        incoming.forEach { fromFile ->
            val local = byName[fromFile.name.lowercase(Locale.ROOT)]
            if (local == null) {
                result += fromFile
                changed = true
            } else if (shouldReplace(local.name) && local.entries != fromFile.entries) {
                val index = result.indexOfFirst { it.name == local.name }
                result[index] = local.copy(entries = fromFile.entries)
                changed = true
            }
        }
        if (changed) save(result)
    }

    fun exportM3u(uri: Uri, playlist: PlaylistData, tracks: List<AudioTrack>) {
        val trackById = tracks.associateBy { it.id }
        val text = buildString {
            appendLine("#EXTM3U")
            playlist.trackIds.forEach { id ->
                val track = trackById[id] ?: return@forEach
                val seconds = (track.durationMs / 1000L).coerceAtLeast(0L)
                val label = listOf(track.artist, track.title.ifBlank { track.displayName.substringBeforeLast('.') })
                    .filter { it.isNotBlank() }.joinToString(" - ")
                appendLine("#EXTINF:$seconds,$label")
                appendLine(exportLocation(track))
            }
        }
        runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    /** Importa um .m3u. Entradas que não existem neste aparelho continuam na playlist como pendentes. */
    fun importM3u(uri: Uri): String? {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull() ?: return null
        val entries = M3u.parse(text, rootRelativePath = MusicFolder.rootRelativePath(context))
        if (entries.isEmpty()) return null
        val fileName = queryDisplayName(uri)
        val base = fileName.substringBeforeLast('.', fileName).trim().ifBlank { t(R.string.playlist_importada) }
        val name = uniqueName(base)
        createWithEntries(name, entries)
        return name
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun uniqueName(base: String): String {
        val existing = loadStored().map { it.name.lowercase(Locale.ROOT) }.toSet()
        var candidate = base
        var n = 2
        while (candidate.lowercase(Locale.ROOT) in existing) candidate = "$base ($n)".also { n++ }
        return candidate
    }

    private fun exportLocation(track: AudioTrack): String = runCatching {
        context.contentResolver.query(Uri.parse(track.id), arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: track.id
}
