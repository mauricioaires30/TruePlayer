package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test fun systemFollowsTheAndroidTheme() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test fun lightAndDarkIgnoreTheAndroidTheme() {
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test fun keysRoundTripAndUnknownFallsBackToSystem() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("sepia"))
    }
}
