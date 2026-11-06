package dev.truetagger.data

/**
 * Número do disco a partir de um texto de tag ("2", "2/3", " 1 "). Vazio, inválido ou zero viram 0,
 * que é o grupo "sem disco" (fica separado do disco 1).
 */
fun discIndex(value: String?): Int =
    value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 0

/**
 * Disco de uma faixa vinda do MediaStore. Usa a coluna DISC_NUMBER (Android 11+) e, sem ela,
 * o número da faixa, que o MediaStore guarda como disco × 1000 + faixa quando o arquivo tem disco.
 * Devolve "" quando a música não tem disco.
 */
fun mediaStoreDisc(discColumn: String?, rawTrack: Int): String {
    discIndex(discColumn).takeIf { it > 0 }?.let { return it.toString() }
    return if (rawTrack >= 1000) (rawTrack / 1000).toString() else ""
}
