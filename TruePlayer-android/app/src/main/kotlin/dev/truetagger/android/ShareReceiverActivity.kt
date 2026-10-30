package dev.truetagger.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.truetagger.data.UrlSupport

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val url = extractUrl(intent)
        if (url == null || !UrlSupport.isSupported(url)) {
            // Esta tela recebe qualquer texto compartilhado: sem aviso o usuário não saberia por que nada aconteceu.
            val message = if (url != null && UrlSupport.isSpotify(url)) R.string.dl_spotify_nao_suportado else R.string.dl_link_nao_suportado
            Toast.makeText(this, t(message), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Sem permissão ativa sobre a pasta o download falharia: pede para escolher/autorizar de novo.
        val hasDownloadFolder = MusicFolder.treeUri(this) != null

        if (hasDownloadFolder) {
            YoutubeDownloadService.start(this, url)
            Toast.makeText(this, t(R.string.download_iniciado_em_segundo_plano), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = ShareContract.ACTION_CONFIGURE_SHARE
                putExtra(ShareContract.EXTRA_SHARED_URL, url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
        finish()
    }

    private fun extractUrl(intent: Intent?): String? {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        return URL_REGEX.find(text)?.value
    }

    companion object {
        private val URL_REGEX = Regex("https?://[^\\s]+")
    }
}
