package dev.truetagger.data

/**
 * Quem vê o tutorial de primeira abertura: só quem acabou de instalar o app e ainda não escolheu
 * uma pasta. Quem já usava o app e apenas atualizou nunca vê.
 */
object OnboardingRule {
    /** Instalação nova: a data da instalação e a da última atualização são a mesma (com folga de 2 s). */
    fun isFreshInstall(firstInstallTimeMs: Long, lastUpdateTimeMs: Long): Boolean =
        lastUpdateTimeMs - firstInstallTimeMs < 2_000L

    fun shouldShow(alreadyShown: Boolean, freshInstall: Boolean, hasFolder: Boolean): Boolean =
        !alreadyShown && freshInstall && !hasFolder
}
