package dev.truetagger.data

/** Descobre, pelo texto do erro do yt-dlp, o motivo de um download ter falhado, para o usuário saber o que fazer. */
object DownloadErrors {
    enum class Reason { NETWORK, UNAVAILABLE, OTHER }

    private val UNAVAILABLE = listOf(
        "video unavailable", "this video is unavailable", "this video is not available", "private video", "this video is private",
        "has been removed", "members-only", "sign in to confirm your age", "age-restricted", "available in your country",
        "blocked it in your country", "account associated with this video has been terminated",
    )
    private val NETWORK = listOf(
        "unable to resolve host", "failed to resolve", "name or service not known", "temporary failure in name resolution",
        "no address associated", "network is unreachable", "no route to host", "connection reset", "timed out", "timeout",
    )

    /** O texto de [error] e das suas causas, em minúsculas. */
    private fun textOf(error: Throwable): String =
        generateSequence(error) { it.cause }.take(6).mapNotNull { it.message }.joinToString(" ").lowercase()

    fun reasonOf(text: String): Reason {
        val lower = text.lowercase()
        return when {
            UNAVAILABLE.any { it in lower } -> Reason.UNAVAILABLE
            NETWORK.any { it in lower } -> Reason.NETWORK
            else -> Reason.OTHER
        }
    }

    fun reasonOf(error: Throwable): Reason = reasonOf(textOf(error))
}
