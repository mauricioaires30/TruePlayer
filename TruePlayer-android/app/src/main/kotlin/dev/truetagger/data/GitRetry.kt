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

    /** Resumo curto do motivo, para a mensagem de "tentando de novo". */
    fun brief(error: Throwable): String {
        val text = generateSequence(error) { it.cause }.take(8).joinToString(" ") { it.message.orEmpty() }.lowercase()
        return when {
            "500" in text -> "erro 500"
            "502" in text -> "erro 502"
            "503" in text -> "erro 503"
            "504" in text -> "erro 504"
            "timed out" in text || "timeout" in text -> "tempo esgotado"
            else -> "falha de conexão"
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
