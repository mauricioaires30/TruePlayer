package dev.truetagger.data

import dev.truetagger.model.AudioTrack

/**
 * Nome dos arquivos de capa em `.trueplayer/covers/`: uma capa por álbum (artista + álbum) e,
 * para músicas sem álbum, uma por música. Tudo em minúsculas para não haver duas capas que só
 * diferem na caixa (o GitHub diferencia, o Windows não).
 */
object CoverNames {
    private const val MAX_LENGTH = 110
    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Primeiro artista, sem "feat.", vírgulas ou "&". */
    private fun primaryArtist(name: String): String =
        name.lowercase().split(" feat", " ft.", ",", " & ", ";", " x ").first().trim()

    fun clean(text: String): String {
        val flat = text.replace(illegal, "_").replace(Regex("\\s+"), " ").trim().trim('.').lowercase()
        if (flat.length <= MAX_LENGTH) return flat
        // Nome longo: corta e põe um número de verificação para dois nomes longos não colidirem.
        return flat.take(MAX_LENGTH - 9).trimEnd() + "-" + Integer.toHexString(flat.hashCode()).padStart(8, '0')
    }

    /** Nome (sem extensão) da capa desta música. Vazio se não dá para nomear. */
    fun keyFor(track: AudioTrack): String {
        val album = track.album.trim()
        val raw = if (album.isNotEmpty()) {
            listOf(primaryArtist(track.artist), album).filter { it.isNotBlank() }.joinToString(" - ")
        } else {
            "faixa - " + track.displayName.substringBeforeLast('.', track.displayName)
        }
        return clean(raw)
    }

    /** Extensão certa para os bytes da imagem, pelo cabeçalho do arquivo. */
    fun extensionFor(bytes: ByteArray): String = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
        else -> "jpg"
    }

    /** Uma música de cada capa distinta, na ordem da biblioteca. */
    fun firstTrackPerCover(tracks: List<AudioTrack>): Map<String, List<AudioTrack>> =
        tracks.filter { keyFor(it).isNotEmpty() }.groupBy { keyFor(it) }
}
