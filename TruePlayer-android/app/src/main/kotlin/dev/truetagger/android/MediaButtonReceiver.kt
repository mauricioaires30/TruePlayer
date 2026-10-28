package dev.truetagger.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Recebe os botões de mídia (fone, volante, "tocar última música" do sistema) mesmo com o app
 * fechado. O Android guarda este receiver como o último dono dos botões de mídia, então ele vence
 * apps que se registraram antes; o serviço retoma a última música quando não há nada tocando.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        } ?: return
        if (event.action != KeyEvent.ACTION_DOWN || event.keyCode !in HANDLED_KEYS) return
        // Este broadcast permite iniciar o serviço em primeiro plano mesmo com o app em segundo plano.
        context.startForegroundService(
            Intent(context, MusicPlaybackService::class.java)
                .setAction(MusicPlaybackService.ACTION_MEDIA_BUTTON)
                .putExtra(MusicPlaybackService.EXTRA_KEY_CODE, event.keyCode),
        )
    }

    private companion object {
        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
