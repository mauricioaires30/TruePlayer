package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguagesTest {
    @Test fun savedValueIsKeptOnlyWhenTheLanguageExists() {
        assertEquals("pt", AppLanguages.normalize("pt"))
        assertEquals("zh-TW", AppLanguages.normalize("zh-TW"))
        assertEquals(AppLanguages.SYSTEM, AppLanguages.normalize(null))
        assertEquals(AppLanguages.SYSTEM, AppLanguages.normalize("klingon"))
        assertEquals(AppLanguages.SYSTEM, AppLanguages.normalize(AppLanguages.SYSTEM))
    }

    @Test fun phoneLanguageMatchesByLanguageIgnoringTheRegion() {
        assertEquals("pt", AppLanguages.resolveSystem(listOf("pt-BR")))
        assertEquals("pt", AppLanguages.resolveSystem(listOf("pt_PT", "en-US")))
        assertEquals("es", AppLanguages.resolveSystem(listOf("es-MX")))
    }

    @Test fun chineseIsPickedByScriptOrRegion() {
        assertEquals("zh-CN", AppLanguages.matchTag("zh-CN"))
        assertEquals("zh-CN", AppLanguages.matchTag("zh-Hans-CN"))
        assertEquals("zh-CN", AppLanguages.matchTag("zh"))
        assertEquals("zh-TW", AppLanguages.matchTag("zh-TW"))
        assertEquals("zh-TW", AppLanguages.matchTag("zh-Hant-HK"))
        assertEquals("zh-TW", AppLanguages.matchTag("zh_HK"))
    }

    @Test fun oldAndroidCodesAreUnderstood() {
        assertEquals("id", AppLanguages.matchTag("in-ID"))
        assertNull(AppLanguages.matchTag("iw-IL"))
    }

    @Test fun firstSupportedLanguageInThePhoneListWins() {
        assertEquals("en", AppLanguages.resolveSystem(listOf("xx-YY", "en-GB", "pt-BR")))
        assertEquals("ja", AppLanguages.resolveSystem(listOf("xx-YY", "ja-JP", "pt-BR")))
    }

    @Test fun unknownLanguagesFallBackToEnglish() {
        assertEquals("en", AppLanguages.resolveSystem(listOf("xx-YY")))
        assertEquals("en", AppLanguages.resolveSystem(emptyList()))
    }

    @Test fun everyLanguageHasANativeNameAndAUniqueTag() {
        assertTrue(AppLanguages.all.all { it.nativeName.isNotBlank() })
        assertEquals(AppLanguages.all.size, AppLanguages.all.map { it.tag }.toSet().size)
        assertEquals(21, AppLanguages.all.size)
    }

    @Test fun rightToLeftLanguagesExist() {
        assertTrue(AppLanguages.rightToLeft.all { tag -> AppLanguages.all.any { it.tag == tag } })
    }
}
