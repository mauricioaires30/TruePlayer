package dev.truetagger.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Foto de cada playlist: escolhida da galeria ou pega do link (álbum/playlist do YouTube, SoundCloud, Spotify).
 * Fica em `files/playlist-covers/`, uma imagem JPEG pequena por playlist, e acompanha renomear e apagar.
 */
internal object PlaylistCovers {
    private const val DIR = "playlist-covers"
    private const val MAX_SIDE = 800

    private val _revision = MutableStateFlow(0)

    /** Sobe a cada mudança, para as telas recarregarem a foto. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private fun file(context: Context, name: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(name.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.applicationContext.filesDir, "$DIR/$hash.jpg")
    }

    fun has(context: Context, name: String): Boolean = file(context, name).isFile

    /** Lê a foto já reduzida, ou null se a playlist não tem. */
    fun load(context: Context, name: String): Bitmap? {
        val f = file(context, name)
        if (!f.isFile) return null
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** Guarda [raw] (qualquer imagem que o Android decodifique) reduzida e em JPEG. Devolve false se não é uma imagem. */
    fun set(context: Context, name: String, raw: ByteArray): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return false
        val sample = generateSequence(1) { it * 2 }.takeWhile { longest / it >= MAX_SIDE }.lastOrNull() ?: 1
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val scaled = if (maxOf(decoded.width, decoded.height) > MAX_SIDE) {
            val ratio = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
            Bitmap.createScaledBitmap(decoded, (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
        } else decoded
        val target = file(context, name)
        target.parentFile?.mkdirs()
        val ok = runCatching { target.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrDefault(false)
        if (ok) _revision.value++
        return ok
    }

    fun setFromUri(context: Context, name: String, uri: Uri): Boolean = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false
        set(context, name, raw)
    }.getOrDefault(false)

    /** Baixa a imagem do link. Falha em silêncio (sem internet, link quebrado): a playlist fica sem foto. */
    fun setFromUrl(context: Context, name: String, url: String): Boolean {
        if (!url.startsWith("http")) return false
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }
            try {
                if (connection.responseCode !in 200..299) return false
                set(context, name, connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }

    fun remove(context: Context, name: String) {
        if (file(context, name).delete()) _revision.value++
    }

    fun rename(context: Context, oldName: String, newName: String) {
        val from = file(context, oldName)
        val to = file(context, newName)
        if (from.isFile && from != to) {
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) runCatching { from.copyTo(to, overwrite = true); from.delete() }
            _revision.value++
        }
    }
}
