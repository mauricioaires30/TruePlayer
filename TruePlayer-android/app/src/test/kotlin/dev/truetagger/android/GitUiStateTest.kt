package dev.truetagger.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GitUiStateTest {
    @Test fun cancellingWithNothingRunningLeavesTheGitFree() {
        // Antes, tocar em "Cancelar" na notificação depois do fim deixava o Git preso em "Cancelando…".
        GitController.cancel()
        assertNull(GitController.ui.value.busy)
        assertFalse(GitController.ui.value.progressHidden)
    }

    @Test fun overallProgressAddsFinishedStepsAndTheCurrentOne() {
        assertEquals(0.375f, GitUi(busy = "x", busyStep = 2, busySteps = 4, busyFraction = 0.5f).overallFraction!!, 0.0001f)
        assertEquals(0.25f, GitUi(busy = "x", busyStep = 2, busySteps = 4, busyFraction = null).overallFraction!!, 0.0001f)
        assertEquals(1f, GitUi(busy = "x", busyStep = 3, busySteps = 3, busyFraction = 1f).overallFraction!!, 0.0001f)
    }

    @Test fun withoutStepsThereIsNoPercentage() {
        assertNull(GitUi(busy = "Conferindo…").overallFraction)
    }
}
