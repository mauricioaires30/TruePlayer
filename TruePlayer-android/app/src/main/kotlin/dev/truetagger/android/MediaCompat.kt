package dev.truetagger.android

import android.media.MediaMetadataRetriever

/**
 * Usa um [MediaMetadataRetriever] e sempre o libera no fim. Não use `use {}`: antes do Android 10 a classe não é
 * `AutoCloseable`, e o `use` falha em todo aparelho com Android 8 ou 9 (as capas nunca carregavam lá).
 */
internal inline fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T {
    val retriever = MediaMetadataRetriever()
    try {
        return block(retriever)
    } finally {
        runCatching { retriever.release() }
    }
}
