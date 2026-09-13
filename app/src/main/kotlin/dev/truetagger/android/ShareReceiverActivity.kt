package dev.truetagger.android

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity

class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val url = extractUrl(intent)
        if (url == null || !isYoutubeUrl(url)) {
            finish()
            return
        }

        val prefs = getSharedPreferences(ShareContract.PREFS, MODE_PRIVATE)
        val hasDownloadFolder = prefs.getString(ShareContract.PREF_DOWNLOAD_TREE, null) != null

        if (hasDownloadFolder) {
            YoutubeDownloadService.start(this, url)
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

    private fun isYoutubeUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "youtu.be" || host.endsWith("youtube.com")
    }

    companion object {
        private val URL_REGEX = Regex("https?://[^\\s]+")
    }
}
