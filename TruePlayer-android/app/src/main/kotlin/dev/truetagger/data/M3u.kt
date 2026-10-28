package dev.truetagger.data

import dev.truetagger.model.TrackKeys
import java.net.URLDecoder

/**
 * Uma linha de playlist. [key] é o caminho relativo à pasta de músicas
 * (ex.: "songs/Faixa.mp3") quando portátil; entradas legadas que não dá para
 * converter (content URI, caminho absoluto de outro aparelho) guardam a linha
 * original e continuam na playlist como "não resolvidas".
 */
data class PlaylistEntry(
    val key: String,
    val label: String = "",
    val durationSec: Int = -1,
) {
    val isPortable: Boolean get() = M3u.isPortable(key)
}

object M3u {
    const val PLAYLISTS_DIR = "playlists"

    fun isPortable(key: String): Boolean =
        key.isNotBlank() &&
            !key.startsWith("content:") &&
            !key.startsWith("/") &&
            !(key.length > 1 && key[1] == ':')

    /**
     * @param playlistDir pasta onde o .m3u está, relativa à raiz (para resolver caminhos relativos).
     * @param rootRelativePath pasta escolhida relativa ao volume (ex.: "Music/TruePlayer/"), usada
     *        para converter caminhos absolutos de exportações antigas.
     */
    fun parse(text: String, playlistDir: String = PLAYLISTS_DIR, rootRelativePath: String = ""): List<PlaylistEntry> {
        val entries = ArrayList<PlaylistEntry>()
        var label = ""
        var duration = -1
        for (rawLine in text.removePrefix("﻿").lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val body = line.substringAfter(':')
                    duration = body.substringBefore(',').trim().toIntOrNull() ?: -1
                    label = body.substringAfter(',', "").trim()
                }
                continue
            }
            entries += PlaylistEntry(toKey(line, playlistDir, rootRelativePath), label, duration)
            label = ""
            duration = -1
        }
        return entries
    }

    fun write(entries: List<PlaylistEntry>, playlistDir: String = PLAYLISTS_DIR): String = buildString {
        append("#EXTM3U\n")
        entries.forEach { entry ->
            if (entry.label.isNotBlank() || entry.durationSec >= 0) {
                append("#EXTINF:").append(entry.durationSec).append(',').append(entry.label.replace('\n', ' ')).append('\n')
            }
            append(if (entry.isPortable) relativeTo(playlistDir, entry.key) else entry.key).append('\n')
        }
    }

    /**
     * Troca as chaves portáteis que aparecem em [map] (chave antiga normalizada por
     * [TrackKeys.compareKey] -> novo caminho). Entradas legadas e desconhecidas ficam como estão.
     */
    fun remapKeys(entries: List<PlaylistEntry>, map: Map<String, String>): List<PlaylistEntry> =
        entries.map { entry ->
            val target = if (entry.isPortable) map[TrackKeys.compareKey(entry.key)] else null
            if (target != null) entry.copy(key = target) else entry
        }

    /** Caminho de [key] visto de dentro de [fromDir]: ("playlists", "songs/a.mp3") -> "../songs/a.mp3". */
    fun relativeTo(fromDir: String, key: String): String {
        val from = TrackKeys.normalize(fromDir).split('/').filter { it.isNotEmpty() }
        val to = TrackKeys.normalize(key).split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
        return buildList {
            repeat(from.size - common) { add("..") }
            addAll(to.drop(common))
        }.joinToString("/")
    }

    private fun toKey(rawLine: String, playlistDir: String, rootRelativePath: String): String {
        var line = rawLine.trim().trim('"')
        if (line.startsWith("file://", ignoreCase = true)) line = line.substring(7)
        if (line.contains('%') && Regex("%[0-9A-Fa-f]{2}").containsMatchIn(line)) {
            line = runCatching { URLDecoder.decode(line.replace("+", "%2B"), "UTF-8") }.getOrDefault(line)
        }
        line = line.replace('\\', '/')
        if (line.startsWith("content:")) return line
        val absolute = line.startsWith("/") || (line.length > 1 && line[1] == ':')
        if (absolute) {
            val root = TrackKeys.normalize(rootRelativePath)
            if (root.isNotEmpty()) {
                val index = line.indexOf("$root/", ignoreCase = true)
                if (index >= 0) return TrackKeys.normalize(line.substring(index + root.length + 1))
            }
            return line
        }
        return TrackKeys.normalize("$playlistDir/$line")
    }
}
