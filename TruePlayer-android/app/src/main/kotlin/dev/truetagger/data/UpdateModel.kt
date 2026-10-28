package dev.truetagger.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Versão no formato 1.3.0 (aceita "v1.3.0" e partes a mais ou a menos). */
data class AppVersion(val parts: List<Int>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val size = maxOf(parts.size, other.parts.size)
        for (i in 0 until size) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        /** Devolve null se o texto não tem nenhum número de versão. */
        fun parse(text: String): AppVersion? {
            val numbers = Regex("\\d+(?:\\.\\d+)*").find(text.trim().removePrefix("v").removePrefix("V"))?.value ?: return null
            return AppVersion(numbers.split('.').mapNotNull { it.toIntOrNull() })
        }
    }
}

/** Uma release publicada no GitHub com um APK anexado. */
data class ReleaseInfo(
    val version: AppVersion,
    val tag: String,
    val title: String,
    /** Texto das novidades (corpo da release). */
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    /** SHA-256 em hexadecimal, quando o GitHub informa. */
    val sha256: String?,
)

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()

    /** Lê a resposta de `releases/latest`. Devolve null se não for uma release publicada com APK. */
    fun parse(text: String): ReleaseInfo? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        if ((obj["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        if ((obj["prerelease"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = obj["tag_name"].text()
        val version = AppVersion.parse(tag) ?: return null
        val assets = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.filter { it["name"].text().endsWith(".apk", ignoreCase = true) }
            .let { apks -> apks.firstOrNull { it["name"].text().contains("trueplayer", ignoreCase = true) } ?: apks.firstOrNull() }
            ?: return null
        val url = apk["browser_download_url"].text()
        if (url.isBlank()) return null
        val digest = apk["digest"].text().removePrefix("sha256:").takeIf { it.length == 64 }
        return ReleaseInfo(
            version = version,
            tag = tag,
            title = obj["name"].text().ifBlank { tag },
            notes = obj["body"].text().trim(),
            apkName = apk["name"].text(),
            apkUrl = url,
            apkSizeBytes = (apk["size"] as? JsonPrimitive)?.longOrNull ?: 0L,
            sha256 = digest?.lowercase(),
        )
    }

    /** Só vale atualizar se a release for estritamente mais nova que a versão instalada. */
    fun isNewer(release: ReleaseInfo, installed: String): Boolean {
        val current = AppVersion.parse(installed) ?: return false
        return release.version > current
    }
}
