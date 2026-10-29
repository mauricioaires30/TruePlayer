package dev.truetagger.data

/** Um idioma que o app oferece. [tag] é a etiqueta BCP 47 (ex.: "pt", "zh-CN"); [nativeName] aparece na lista de escolha. */
data class AppLanguage(val tag: String, val nativeName: String)

/** Idiomas do app e a regra de qual vale. Lógica pura (sem Android) para poder ser testada. */
object AppLanguages {
    const val SYSTEM = "system"

    /** Em ordem de exibição, cada um com o nome na própria língua. Cada um tem sua pasta `values-xx` em res. */
    val all: List<AppLanguage> = listOf(
        AppLanguage("en", "English"),
        AppLanguage("pt", "Português"),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("it", "Italiano"),
        AppLanguage("nl", "Nederlands"),
        AppLanguage("sv", "Svenska"),
        AppLanguage("pl", "Polski"),
        AppLanguage("tr", "Türkçe"),
        AppLanguage("ru", "Русский"),
        AppLanguage("uk", "Українська"),
        AppLanguage("ar", "العربية"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("id", "Bahasa Indonesia"),
        AppLanguage("vi", "Tiếng Việt"),
        AppLanguage("th", "ไทย"),
        AppLanguage("ja", "日本語"),
        AppLanguage("ko", "한국어"),
        AppLanguage("zh-CN", "简体中文"),
        AppLanguage("zh-TW", "繁體中文"),
    )

    /** Idiomas escritos da direita para a esquerda (o layout inteiro espelha). */
    val rightToLeft: Set<String> = setOf("ar")

    /** Texto guardado -> idioma que vale ("system" e o que não existe mais viram [SYSTEM]). */
    fun normalize(saved: String?): String = if (saved != null && all.any { it.tag == saved }) saved else SYSTEM

    /**
     * Qual idioma do app corresponde a uma etiqueta do Android (ex.: "pt-BR" -> "pt", "zh-Hant-TW" -> "zh-TW").
     * Devolve null se o app não tem esse idioma.
     */
    fun matchTag(tag: String): String? {
        val parts = tag.replace('_', '-').split('-')
        val language = parts.first().lowercase().let { if (it == "iw") "he" else if (it == "in") "id" else it }
        if (language == "zh") {
            val traditional = parts.drop(1).any { it.equals("Hant", true) || it.uppercase() in setOf("TW", "HK", "MO") }
            return if (traditional) "zh-TW" else "zh-CN"
        }
        return all.firstOrNull { it.tag == language }?.tag
    }

    /** Qual idioma do app usar para a lista de idiomas do celular, em ordem de preferência; sem correspondência, inglês. */
    fun resolveSystem(systemTags: List<String>): String = systemTags.firstNotNullOfOrNull(::matchTag) ?: "en"
}
