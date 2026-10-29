package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {
    @Test fun everySettingSurvivesAWriteAndRead() {
        val original = AppConfigFile(
            autoRefreshLibrary = false, embedYoutubeThumbnail = false, autoUpdateDownloader = false,
            autoTag = false, autoCheckUpdates = false,
            volumeNormalization = false, volumeOutputLevel = 0.7f,
            librarySort = "ARTIST", libraryAscending = false, albumSort = "YEAR", albumAscending = false,
            themeMode = "dark", language = "pt",
        )
        assertEquals(original, AppConfigCodec.decode(AppConfigCodec.encode(original)))
    }

    @Test fun theSameConfigAlwaysProducesTheSameText() {
        // o app só regrava o arquivo quando o texto muda, para não gerar mudança à toa no Git
        assertEquals(AppConfigCodec.encode(AppConfigFile()), AppConfigCodec.encode(AppConfigFile()))
    }

    @Test fun anOldFileWithoutTheNewFieldsStillLoads() {
        val old = """{"schemaVersion":1,"autoRefreshLibrary":false,"embedYoutubeThumbnail":true,"autoUpdateDownloader":true}"""
        val read = AppConfigCodec.decode(old)
        assertNotNull(read)
        assertFalse(read!!.autoRefreshLibrary)
        assertTrue(read.autoTag)
        assertEquals(1f, read.volumeOutputLevel, 0f)
    }

    @Test fun aFileFromANewerAppOrGarbageIsIgnored() {
        assertNull(AppConfigCodec.decode("""{"schemaVersion":99}"""))
        assertNull(AppConfigCodec.decode("isso nao e json"))
    }

    @Test fun secretsAndDeviceDataHaveNoFieldHere() {
        val text = AppConfigCodec.encode(AppConfigFile())
        listOf("token", "password", "download_tree", "uri").forEach { assertFalse(it, text.contains(it, ignoreCase = true)) }
    }
}
