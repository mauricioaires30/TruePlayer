package dev.truetagger.data

/**
 * Linhas de apoio de uma música, na ordem em que aparecem em todo o app: primeiro o álbum, depois o artista.
 * Linha em branco some (música sem álbum mostra só o artista, e vice-versa).
 */
fun subtitleLines(album: String?, artist: String?): List<String> =
    listOf(album, artist).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
