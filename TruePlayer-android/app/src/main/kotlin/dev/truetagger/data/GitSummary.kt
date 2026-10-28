package dev.truetagger.data

import java.time.LocalDate

/** Uma mudança na pasta de músicas desde o último commit. */
data class GitChange(val path: String, val kind: Kind) {
    enum class Kind { ADDED, MODIFIED, REMOVED }
}

/** Mudanças agrupadas pelo que significam para o usuário (música nova, playlist, tag editada...). */
data class GitSummary(
    val songsAdded: List<String>,
    val songsRemoved: List<String>,
    val tagsEdited: List<String>,
    val playlistsAdded: List<String>,
    val playlistsChanged: List<String>,
    val playlistsRemoved: List<String>,
    val statsChanged: Boolean,
    val other: Int,
    val settingsChanged: Boolean = false,
) {
    val isEmpty: Boolean
        get() = songsAdded.isEmpty() && songsRemoved.isEmpty() && tagsEdited.isEmpty() && playlistsAdded.isEmpty() &&
            playlistsChanged.isEmpty() && playlistsRemoved.isEmpty() && !statsChanged && !settingsChanged && other == 0

    companion object {
        fun from(changes: List<GitChange>): GitSummary {
            val songsAdded = ArrayList<String>()
            val songsRemoved = ArrayList<String>()
            val tagsEdited = ArrayList<String>()
            val playlistsAdded = ArrayList<String>()
            val playlistsChanged = ArrayList<String>()
            val playlistsRemoved = ArrayList<String>()
            var stats = false
            var settings = false
            var other = 0
            for (change in changes.distinctBy { it.path }) {
                val path = change.path.replace('\\', '/')
                val name = path.substringAfterLast('/')
                val top = path.substringBefore('/')
                when {
                    top == MigrationPlanner.SONGS_DIR && MigrationPlanner.isAudio(name) -> when (change.kind) {
                        GitChange.Kind.ADDED -> songsAdded += name
                        GitChange.Kind.REMOVED -> songsRemoved += name
                        GitChange.Kind.MODIFIED -> tagsEdited += name
                    }
                    top == MigrationPlanner.PLAYLISTS_DIR && MigrationPlanner.isPlaylist(name) -> {
                        val playlist = name.substringBeforeLast('.')
                        when (change.kind) {
                            GitChange.Kind.ADDED -> playlistsAdded += playlist
                            GitChange.Kind.REMOVED -> playlistsRemoved += playlist
                            GitChange.Kind.MODIFIED -> playlistsChanged += playlist
                        }
                    }
                    path == "${MigrationPlanner.META_DIR}/stats.json" -> stats = true
                    path == "${MigrationPlanner.META_DIR}/settings.json" -> settings = true
                    // backups e a lixeira não entram na mensagem, mas contam como mudança
                    else -> other++
                }
            }
            return GitSummary(songsAdded, songsRemoved, tagsEdited, playlistsAdded, playlistsChanged, playlistsRemoved, stats, other, settings)
        }
    }
}

object CommitMessage {
    /**
     * "2026-10-27: +3 músicas, +1 playlist (Treino), 5 tags editadas, 142 min ouvidos".
     * [minutesListened] é o tempo ouvido no dia (0 = não menciona).
     */
    fun build(summary: GitSummary, date: LocalDate, minutesListened: Long = 0L): String {
        val parts = ArrayList<String>()
        if (summary.songsAdded.isNotEmpty()) parts += "+${summary.songsAdded.size} " + plural(summary.songsAdded.size, "música", "músicas")
        if (summary.songsRemoved.isNotEmpty()) parts += "-${summary.songsRemoved.size} " + plural(summary.songsRemoved.size, "música", "músicas")
        if (summary.playlistsAdded.isNotEmpty()) {
            parts += "+${summary.playlistsAdded.size} " + plural(summary.playlistsAdded.size, "playlist", "playlists") + names(summary.playlistsAdded)
        }
        if (summary.playlistsChanged.isNotEmpty()) {
            parts += "${summary.playlistsChanged.size} " + plural(summary.playlistsChanged.size, "playlist alterada", "playlists alteradas") + names(summary.playlistsChanged)
        }
        if (summary.playlistsRemoved.isNotEmpty()) {
            parts += "-${summary.playlistsRemoved.size} " + plural(summary.playlistsRemoved.size, "playlist", "playlists")
        }
        if (summary.tagsEdited.isNotEmpty()) {
            parts += "${summary.tagsEdited.size} " + plural(summary.tagsEdited.size, "tag editada", "tags editadas")
        }
        if (summary.settingsChanged) parts += "configurações atualizadas"
        if (minutesListened > 0L) parts += "$minutesListened min ouvidos"
        if (parts.isEmpty()) parts += "atualização da pasta"
        return "$date: " + parts.joinToString(", ")
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun names(list: List<String>): String {
        val shown = list.take(3).joinToString(", ")
        val extra = if (list.size > 3) " e mais ${list.size - 3}" else ""
        return " ($shown$extra)"
    }
}

/** Arquivos grandes demais para o GitHub (limite de 100 MB por arquivo). */
object GitLimits {
    const val MAX_FILE_BYTES = 95L * 1024L * 1024L

    fun isTooLarge(sizeBytes: Long) = sizeBytes > MAX_FILE_BYTES

    /** Linhas padrão de um .gitignore novo para a pasta de músicas. */
    val DEFAULT_GITIGNORE = listOf(
        "# Criado pelo TruePlayer",
        "${MigrationPlanner.TRASH_DIR}/",
        "${MigrationPlanner.META_DIR}/backup/",
        "${MigrationPlanner.META_DIR}/tmp/",
    )
}
