package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateModelTest {
    @Test fun parsesVersionsWithOrWithoutPrefix() {
        assertEquals(AppVersion(listOf(1, 3, 0)), AppVersion.parse("v1.3.0"))
        assertEquals(AppVersion(listOf(1, 3, 0)), AppVersion.parse("1.3.0"))
        assertEquals(AppVersion(listOf(2, 0)), AppVersion.parse("V2.0"))
        assertNull(AppVersion.parse("sem numero"))
    }

    @Test fun comparesNumericallyNotAsText() {
        assertTrue(AppVersion.parse("1.10.0")!! > AppVersion.parse("1.9.0")!!)
        assertTrue(AppVersion.parse("1.3.1")!! > AppVersion.parse("1.3.0")!!)
        assertTrue(AppVersion.parse("2.0")!! > AppVersion.parse("1.99.99")!!)
        assertEquals(0, AppVersion.parse("1.3")!!.compareTo(AppVersion.parse("1.3.0")!!))
    }

    private fun release(tag: String = "v1.4.0", extra: String = "") = """
        {"tag_name":"$tag","name":"TruePlayer 1.4.0","body":"- novidade A\n- novidade B","draft":false,"prerelease":false,
         "assets":[
           {"name":"notas.txt","browser_download_url":"https://x/notas.txt","size":10},
           {"name":"TruePlayer-1.4.0.apk","browser_download_url":"https://github.com/u/r/releases/download/v1.4.0/TruePlayer-1.4.0.apk","size":70123456$extra}
         ]}
    """.trimIndent()

    @Test fun readsReleaseAndPicksTheApk() {
        val info = ReleaseParser.parse(release())
        assertNotNull(info)
        assertEquals(AppVersion(listOf(1, 4, 0)), info!!.version)
        assertEquals("TruePlayer-1.4.0.apk", info.apkName)
        assertEquals(70123456L, info.apkSizeBytes)
        assertTrue(info.notes.contains("novidade A"))
        assertNull(info.sha256)
    }

    @Test fun readsSha256WhenGithubProvidesIt() {
        val digest = "a".repeat(64)
        val info = ReleaseParser.parse(release(extra = ""","digest":"sha256:$digest""""))
        assertEquals(digest, info!!.sha256)
    }

    @Test fun ignoresDraftsPrereleasesAndReleasesWithoutApk() {
        assertNull(ReleaseParser.parse(release().replace("\"draft\":false", "\"draft\":true")))
        assertNull(ReleaseParser.parse(release().replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(ReleaseParser.parse("""{"tag_name":"v1.0.0","assets":[{"name":"x.zip","browser_download_url":"https://x"}]}"""))
        assertNull(ReleaseParser.parse("não é json"))
        assertNull(ReleaseParser.parse("""{"tag_name":"sem versao","assets":[]}"""))
    }

    @Test fun onlyStrictlyNewerReleasesAreOffered() {
        val info = ReleaseParser.parse(release())!!
        assertTrue(ReleaseParser.isNewer(info, "1.3.0"))
        assertFalse(ReleaseParser.isNewer(info, "1.4.0"))
        assertFalse(ReleaseParser.isNewer(info, "1.5.0"))
        assertFalse(ReleaseParser.isNewer(info, "indefinido"))
    }
}
