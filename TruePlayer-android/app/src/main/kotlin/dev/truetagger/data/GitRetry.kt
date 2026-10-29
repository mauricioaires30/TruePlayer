package dev.truetagger.data

/**
 * Tentar de novo quando o GitHub ou a conexão falham de forma passageira (erro 5xx, conexão caída).
 * Erros de verdade (token errado, branch com outro histórico, arquivo recusado) nunca são repetidos.
 */
object GitRetry {
    private val transientMarks = listOf(
        "500 internal server error", "502 bad gateway", "503 service unavailable", "504 gateway timeout",
        "internal server error", "bad gateway", "service unavailable", "gateway timeout",
        "timed out", "timeout", "connection reset", "connection refused", "broken pipe",
        "unexpected end of stream", "unexpected end of file", "software caused connection abort",
        "unable to resolve host", "unknownhost", "failed to connect", "sslexception", "premature eof",
    )

    /** O erro (ou alguma causa dele) parece falha passageira do servidor ou da rede? */
    fun isTransient(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.take(8).any { cause ->
            val text = (cause.message.orEmpty() + " " + cause::class.simpleName.orEmpty()).lowercase()
            transientMarks.any { it in text }
        }

    /** Motivo resumido de uma falha, para a mensagem de "tentando de novo". O texto vem de quem mostra. */
    enum class Reason(val httpCode: Int?) {
        HTTP_500(500), HTTP_502(502), HTTP_503(503), HTTP_504(504), TIMEOUT(null), CONNECTION(null)
    }

    fun reason(error: Throwable): Reason {
        val text = generateSequence(error) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            "500" in text -> Reason.HTTP_500
            "502" in text -> Reason.HTTP_502
            "503" in text -> Reason.HTTP_503
            "504" in text -> Reason.HTTP_504
            "timed out" in text || "timeout" in text -> Reason.TIMEOUT
            else -> Reason.CONNECTION
        }
    }

    /**
     * Roda [block]; se falhar e [shouldRetry] permitir, espera o próximo valor de [delaysMs] e tenta de novo.
     * Depois de esgotar os atrasos, relança o último erro.
     */
    fun <T> run(
        delaysMs: List<Long>,
        shouldRetry: (Throwable) -> Boolean = ::isTransient,
        onRetry: (attempt: Int, delayMs: Long, error: Throwable) -> Unit = { _, _, _ -> },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        block: () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: Exception) {
                if (attempt >= delaysMs.size || !shouldRetry(error)) throw error
                val delay = delaysMs[attempt]
                attempt++
                onRetry(attempt, delay, error)
                sleep(delay)
            }
        }
    }
}
