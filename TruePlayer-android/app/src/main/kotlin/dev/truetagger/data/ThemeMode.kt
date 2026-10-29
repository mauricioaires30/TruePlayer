package dev.truetagger.data

/** Aparência escolhida em Configurações: seguir o Android, ou forçar claro ou escuro. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** O tema escuro vale agora? [systemDark] é o que o Android está usando. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Valor guardado -> modo. Texto desconhecido (ou de uma versão futura) cai em [SYSTEM]. */
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}
