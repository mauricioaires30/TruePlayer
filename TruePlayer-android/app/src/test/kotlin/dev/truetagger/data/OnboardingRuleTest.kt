package dev.truetagger.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingRuleTest {
    @Test fun freshInstallHasSameInstallAndUpdateTime() {
        assertTrue(OnboardingRule.isFreshInstall(1_000L, 1_000L))
        assertTrue(OnboardingRule.isFreshInstall(1_000L, 2_500L))
    }

    @Test fun updatedAppIsNotFresh() {
        assertFalse(OnboardingRule.isFreshInstall(1_000L, 86_400_000L))
    }

    @Test fun newInstallWithoutFolderSeesTheTutorialOnce() {
        assertTrue(OnboardingRule.shouldShow(alreadyShown = false, freshInstall = true, hasFolder = false))
        assertFalse(OnboardingRule.shouldShow(alreadyShown = true, freshInstall = true, hasFolder = false))
    }

    @Test fun peopleWhoJustUpdatedNeverSeeIt() {
        assertFalse(OnboardingRule.shouldShow(alreadyShown = false, freshInstall = false, hasFolder = false))
        assertFalse(OnboardingRule.shouldShow(alreadyShown = false, freshInstall = false, hasFolder = true))
    }

    @Test fun anyoneWithAFolderAlreadyKnowsTheApp() {
        assertFalse(OnboardingRule.shouldShow(alreadyShown = false, freshInstall = true, hasFolder = true))
    }
}
