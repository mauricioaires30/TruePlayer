package dev.truetagger.android

import android.content.Context
import dev.truetagger.data.OnboardingRule

/** Guarda se o tutorial de primeira abertura já foi resolvido (visto, pulado ou desnecessário). */
object OnboardingState {
    private const val PREFS = "truetagger_onboarding"
    private const val KEY_DONE = "done"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Decide se o tutorial aparece agora. Quem atualizou de uma versão antiga, ou já tem pasta
     * escolhida, é marcado como resolvido na hora e nunca vê o tutorial.
     */
    fun shouldShow(context: Context): Boolean {
        val app = context.applicationContext
        if (prefs(app).getBoolean(KEY_DONE, false)) return false
        val fresh = runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            OnboardingRule.isFreshInstall(info.firstInstallTime, info.lastUpdateTime)
        }.getOrDefault(false)
        val show = OnboardingRule.shouldShow(alreadyShown = false, freshInstall = fresh, hasFolder = MusicFolder.savedUri(app) != null)
        if (!show) finish(app)
        return show
    }

    fun finish(context: Context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply()
    }
}
