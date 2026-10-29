package dev.truetagger.android

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.truetagger.data.AppLanguages
import java.util.Locale

/**
 * Textos do app no idioma escolhido, de qualquer lugar (telas, serviços, notificações, mensagens).
 * Trocar o idioma recria a tela; o que está guardado aqui é atualizado antes disso por [LocaleHelper.apply].
 */
@SuppressLint("StaticFieldLeak")
object AppText {
    @Volatile
    private var localized: Context? = null

    /** Só para testes de JVM (sem Android): resolve os textos sem Context. */
    interface TestResolver {
        fun string(id: Int, args: Array<out Any?>): String
        fun plural(id: Int, count: Int, args: Array<out Any?>): String
    }

    @Volatile
    var testResolver: TestResolver? = null

    fun init(app: Context) {
        val language = LocaleHelper.saved(app)
        localized = LocaleHelper.wrap(app.applicationContext, language)
        // Antes do Android 13 o sistema não sabe do idioma escolhido no app, e datas relativas ("há 5 min") e
        // números formatados usam o idioma padrão do processo; ele passa a acompanhar a escolha.
        if (Build.VERSION.SDK_INT < 33) {
            Locale.setDefault(
                if (language == AppLanguages.SYSTEM) android.content.res.Resources.getSystem().configuration.locales[0]
                else Locale.forLanguageTag(language),
            )
        }
    }

    fun context(): Context = checkNotNull(localized) { "AppText.init não foi chamado" }
}

/** Etiqueta do idioma em uso agora (ex.: "pt-BR"), para escolher o texto certo das notas de versão. */
fun currentLanguageTag(): String = AppText.context().resources.configuration.locales[0].toLanguageTag()

/** Texto de recurso no idioma do app. */
fun t(@StringRes id: Int, vararg args: Any?): String =
    AppText.testResolver?.string(id, args) ?: AppText.context().getString(id, *args)

/** Texto com plural. O número já entra como primeiro argumento de formato (`%1$s`). */
fun tp(@PluralsRes id: Int, count: Int, vararg args: Any?): String =
    AppText.testResolver?.plural(id, count, args) ?: AppText.context().resources.getQuantityString(id, count, count, *args)

object LocaleHelper {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(ShareContract.PREFS, Context.MODE_PRIVATE)

    /** Idioma escolhido em Configurações: "system" ou a etiqueta (ex.: "pt"). */
    fun saved(context: Context): String = AppLanguages.normalize(prefs(context).getString(ShareContract.PREF_LANGUAGE, null))

    /** Devolve [base] no idioma [language]. "system" devolve o contexto como está (segue o celular). */
    fun wrap(base: Context, language: String): Context {
        if (language == AppLanguages.SYSTEM) return base
        val locale = Locale.forLanguageTag(language)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Guarda a escolha e atualiza os textos. Quem chama recria a tela depois. */
    fun apply(context: Context, language: String) {
        val value = AppLanguages.normalize(language)
        prefs(context).edit().putString(ShareContract.PREF_LANGUAGE, value).apply()
        AppText.init(context)
        // No Android 13+ o seletor de idioma do próprio sistema mostra a mesma escolha.
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(android.app.LocaleManager::class.java)
            manager?.applicationLocales = if (value == AppLanguages.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(value)
        }
    }

    /**
     * Android 13+: se o idioma do app foi trocado pelo seletor do sistema (Configurações do Android), a escolha
     * do app acompanha, para as duas telas nunca discordarem.
     */
    fun adoptSystemChoice(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(android.app.LocaleManager::class.java) ?: return
        val locales = manager.applicationLocales
        val chosen = if (locales.isEmpty) AppLanguages.SYSTEM else AppLanguages.matchTag(locales[0].toLanguageTag()) ?: AppLanguages.SYSTEM
        if (chosen != saved(context)) {
            prefs(context).edit().putString(ShareContract.PREF_LANGUAGE, chosen).apply()
            AppText.init(context)
        }
    }
}

/** Prepara os textos antes de qualquer tela, serviço ou tarefa em segundo plano. */
class TruePlayerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleHelper.adoptSystemChoice(this)
        AppText.init(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // "Sistema": o celular trocou de idioma com o app em segundo plano.
        AppText.init(this)
    }
}
