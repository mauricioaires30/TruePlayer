package dev.truetagger.model

/**
 * Chave portátil de uma música: o caminho relativo à pasta de músicas escolhida
 * (ex.: "songs/Artista/Faixa.mp3"). Não depende do aparelho, então serve de
 * identidade para playlists e estatísticas ao trocar de celular.
 */
object TrackKeys {
    /**
     * @param rootRelativePath pasta escolhida relativa ao volume, com "/" no fim (ex.: "Music/TruePlayer/").
     * @param mediaPath RELATIVE_PATH do MediaStore (API 29+, só a pasta) ou DATA (API < 29, caminho completo).
     */
    fun relPath(rootRelativePath: String, mediaPath: String, displayName: String): String {
        val root = normalizeDir(rootRelativePath)
        val path = mediaPath.replace('\\', '/')
        val rootIndex = if (root.isBlank()) -1 else path.indexOf(root, ignoreCase = true)
        val afterRoot = if (rootIndex >= 0) path.substring(rootIndex + root.length) else path.trimStart('/')
        if (afterRoot.endsWith(displayName)) return afterRoot
        val dir = afterRoot.trim('/')
        return if (dir.isEmpty()) displayName else "$dir/$displayName"
    }

    /** Normaliza para comparação: barras "/", sem "./" ou "../" iniciais, sem barra no começo. */
    fun normalize(relPath: String): String {
        val parts = relPath.replace('\\', '/').split('/')
        val kept = ArrayList<String>(parts.size)
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (kept.isNotEmpty()) kept.removeAt(kept.lastIndex)
                else -> kept += part
            }
        }
        return kept.joinToString("/")
    }

    /** Chave para comparar sem diferenciar maiúsculas (SAF/FAT às vezes alteram caixa). */
    fun compareKey(relPath: String): String = normalize(relPath).lowercase()

    private fun normalizeDir(dir: String): String {
        val clean = dir.replace('\\', '/').trim('/')
        return if (clean.isEmpty()) "" else "$clean/"
    }
}
