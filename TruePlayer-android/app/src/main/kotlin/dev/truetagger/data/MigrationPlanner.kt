package dev.truetagger.data

import dev.truetagger.model.TrackKeys

/** Arquivo encontrado na pasta de músicas; [relPath] é relativo à pasta escolhida. */
data class FolderFile(val relPath: String, val sizeBytes: Long = 0L)

data class PlannedMove(val from: String, val to: String, val kind: Kind) {
    enum class Kind { SONG, PLAYLIST }
}

/**
 * Decide, sem tocar em nada, para onde cada música/playlist solta deve ir ao criar o
 * layout `songs/` + `playlists/`. Só planeja movimentos: nunca apaga nem sobrescreve.
 */
object MigrationPlanner {
    const val SONGS_DIR = "songs"
    const val PLAYLISTS_DIR = M3u.PLAYLISTS_DIR
    const val META_DIR = ".trueplayer"
    const val TRASH_DIR = "_lixeira"

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "alac", "mka",
    )
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")

    /** Pastas que o app gerencia e que nunca entram na migração. */
    private val RESERVED_TOP_LEVEL = setOf(SONGS_DIR, PLAYLISTS_DIR, META_DIR, TRASH_DIR, ".git")

    fun isAudio(name: String) = extension(name) in AUDIO_EXTENSIONS
    fun isPlaylist(name: String) = extension(name) in PLAYLIST_EXTENSIONS

    /** @param existing tudo o que já existe na pasta (para detectar colisões de nome). */
    fun plan(existing: List<FolderFile>): List<PlannedMove> {
        val taken = existing.mapTo(HashSet()) { TrackKeys.compareKey(it.relPath) }
        val moves = ArrayList<PlannedMove>()
        existing.sortedBy { it.relPath.lowercase() }.forEach { file ->
            val rel = TrackKeys.normalize(file.relPath)
            val top = rel.substringBefore('/')
            if (top.lowercase() in RESERVED_TOP_LEVEL || rel.startsWith(".") ) return@forEach
            val name = rel.substringAfterLast('/')
            val move = when {
                isAudio(name) -> PlannedMove(rel, "$SONGS_DIR/$rel", PlannedMove.Kind.SONG)
                isPlaylist(name) -> PlannedMove(rel, "$PLAYLISTS_DIR/$name", PlannedMove.Kind.PLAYLIST)
                else -> return@forEach
            }
            val target = uniqueTarget(move.to, taken)
            taken += TrackKeys.compareKey(target)
            moves += move.copy(to = target)
        }
        return moves
    }

    /** "songs/a.mp3" ocupado -> "songs/a (2).mp3". */
    internal fun uniqueTarget(target: String, taken: Set<String>): String {
        if (TrackKeys.compareKey(target) !in taken) return target
        val dir = target.substringBeforeLast('/', "")
        val name = target.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (true) {
            val candidate = (if (dir.isEmpty()) "" else "$dir/") + "$base ($n)$ext"
            if (TrackKeys.compareKey(candidate) !in taken) return candidate
            n++
        }
    }

    private fun extension(name: String): String =
        if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
}
