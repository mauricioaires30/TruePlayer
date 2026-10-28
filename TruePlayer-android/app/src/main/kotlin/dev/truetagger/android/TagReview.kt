package dev.truetagger.android

import android.content.Context
import dev.truetagger.model.TrackKeys

/** Músicas baixadas cujas tags o app não teve certeza e que ficam esperando revisão do usuário. */
object TagReview {
    private const val PREFS = "truetagger_tag_review"
    private const val KEY = "paths"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(context: Context): Set<String> = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toSet()

    @Synchronized
    fun add(context: Context, relPath: String) {
        val key = TrackKeys.compareKey(relPath)
        if (key.isBlank()) return
        prefs(context).edit().putStringSet(KEY, all(context) + key).apply()
        AppEvents.bump()
    }

    @Synchronized
    fun remove(context: Context, relPath: String) {
        val key = TrackKeys.compareKey(relPath)
        val current = all(context)
        if (key !in current) return
        prefs(context).edit().putStringSet(KEY, current - key).apply()
        AppEvents.bump()
    }
}
