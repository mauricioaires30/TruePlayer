package dev.truetagger.data

/**
 * O que a limpeza da pasta de músicas pode oferecer para mover à lixeira.
 * Regra principal: nada que tenha música ou playlist dentro, e nada que o app ou o git usam.
 */
object CleanupRules {
    private val RESERVED = setOf(
        MigrationPlanner.SONGS_DIR,
        MigrationPlanner.PLAYLISTS_DIR,
        MigrationPlanner.META_DIR,
        MigrationPlanner.TRASH_DIR,
        "readme", "readme.md", "readme.txt", "license", "license.md", "license.txt",
    )

    /** Nunca entra na lista de limpeza (pastas do app, arquivos de git, README...). */
    fun isProtectedName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith(".") || lower in RESERVED
    }

    /** Arquivo solto que é música ou playlist: quem cuida é a organização, não a limpeza. */
    fun isMusicFile(name: String): Boolean = MigrationPlanner.isAudio(name) || MigrationPlanner.isPlaylist(name)

    /** Nome livre na lixeira e na pasta de origem: "x" -> "x (2)" (sem mexer na extensão). */
    fun freeName(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val base = if (name.contains('.') && !name.startsWith(".")) name.substringBeforeLast('.') else name
        val ext = if (base.length != name.length) "." + name.substringAfterLast('.') else ""
        var n = 2
        while ("$base ($n)$ext".lowercase() in taken) n++
        return "$base ($n)$ext"
    }
}
