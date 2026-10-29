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

/** Frases da mensagem de commit sugerida. O app usa o idioma escolhido; o padrão (e os testes) é português. */
interface CommitText {
    fun songsAdded(n: Int): String
    fun songsRemoved(n: Int): String
    fun playlistsAdded(n: Int): String
    fun playlistsChanged(n: Int): String
    fun playlistsRemoved(n: Int): String
    fun tagsEdited(n: Int): String
    fun settingsUpdated(): String
    fun minutesListened(minutes: Long): String
    fun folderUpdate(): String
    fun andMore(n: Int): String
}

object PortugueseCommitText : CommitText {
    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
    override fun songsAdded(n: Int) = "+$n " + plural(n, "música", "músicas")
    override fun songsRemoved(n: Int) = "-$n " + plural(n, "música", "músicas")
    override fun playlistsAdded(n: Int) = "+$n " + plural(n, "playlist", "playlists")
    override fun playlistsChanged(n: Int) = "$n " + plural(n, "playlist alterada", "playlists alteradas")
    override fun playlistsRemoved(n: Int) = "-$n " + plural(n, "playlist", "playlists")
    override fun tagsEdited(n: Int) = "$n " + plural(n, "tag editada", "tags editadas")
    override fun settingsUpdated() = "configurações atualizadas"
    override fun minutesListened(minutes: Long) = "$minutes min ouvidos"
    override fun folderUpdate() = "atualização da pasta"
    override fun andMore(n: Int) = " e mais $n"
}

object CommitMessage {
    /**
     * "2026-10-27: +3 músicas, +1 playlist (Treino), 5 tags editadas, 142 min ouvidos".
     * [minutesListened] é o tempo ouvido no dia (0 = não menciona).
     */
    fun build(summary: GitSummary, date: LocalDate, minutesListened: Long = 0L, text: CommitText = PortugueseCommitText): String {
        val parts = ArrayList<String>()
        if (summary.songsAdded.isNotEmpty()) parts += text.songsAdded(summary.songsAdded.size)
        if (summary.songsRemoved.isNotEmpty()) parts += text.songsRemoved(summary.songsRemoved.size)
        if (summary.playlistsAdded.isNotEmpty()) parts += text.playlistsAdded(summary.playlistsAdded.size) + names(summary.playlistsAdded, text)
        if (summary.playlistsChanged.isNotEmpty()) parts += text.playlistsChanged(summary.playlistsChanged.size) + names(summary.playlistsChanged, text)
        if (summary.playlistsRemoved.isNotEmpty()) parts += text.playlistsRemoved(summary.playlistsRemoved.size)
        if (summary.tagsEdited.isNotEmpty()) parts += text.tagsEdited(summary.tagsEdited.size)
        if (summary.settingsChanged) parts += text.settingsUpdated()
        if (minutesListened > 0L) parts += text.minutesListened(minutesListened)
        if (parts.isEmpty()) parts += text.folderUpdate()
        return "$date: " + parts.joinToString(", ")
    }

    private fun names(list: List<String>, text: CommitText): String {
        val shown = list.take(3).joinToString(", ")
        val extra = if (list.size > 3) text.andMore(list.size - 3) else ""
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
