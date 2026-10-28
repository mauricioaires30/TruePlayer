package dev.truetagger.data

/** Leitura do endereço `origin` de um repositório que já existe, sem expor credenciais. */
object GitRemote {
    // O host precisa ter ponto (github.com): assim "C:\pasta\repo.git" não é confundido com um endereço.
    private val SCP_STYLE = Regex("^(?:[^@/\\s]+@)?([^:/\\s]*\\.[^:/\\s]+):(?!//)([^\\s]+)$")
    private val SSH_URL = Regex("^ssh://(?:[^@/\\s]+@)?([^/:\\s]+)(?::\\d+)?/(.+)$")

    /** `git@github.com:user/repo.git` e `ssh://git@github.com/user/repo.git` viram https; o resto fica igual. */
    fun toHttps(url: String): String {
        val clean = url.trim()
        SSH_URL.find(clean)?.let { return "https://${it.groupValues[1]}/${it.groupValues[2]}" }
        if (!clean.startsWith("http") && !clean.startsWith("file") && !clean.startsWith("/")) {
            SCP_STYLE.find(clean)?.let { return "https://${it.groupValues[1]}/${it.groupValues[2]}" }
        }
        return stripCredentials(clean)
    }

    /** Tira `usuario:senha@` do endereço (para mostrar ou usar sem vazar o token). */
    fun stripCredentials(url: String): String = url.replace(Regex("^(https?://)[^/@\\s]+@"), "$1")

    /** Usuário e senha/token embutidos em `https://usuario:token@github.com/...`, se houver. */
    fun embeddedCredentials(url: String): Pair<String, String>? {
        val match = Regex("^https?://([^/@\\s:]+):([^/@\\s]+)@").find(url.trim()) ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    /** Dono do repositório no GitHub (a parte de `github.com/<dono>/<repo>`). */
    fun owner(url: String): String? {
        val https = toHttps(url)
        return Regex("^https?://[^/]+/([^/]+)/[^/]+").find(https)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** Usuário para a autenticação por token: o embutido na URL, o dono do repositório ou um nome qualquer aceito pelo GitHub. */
    fun authUser(url: String): String = embeddedCredentials(url)?.first ?: owner(url) ?: "x-access-token"
}
