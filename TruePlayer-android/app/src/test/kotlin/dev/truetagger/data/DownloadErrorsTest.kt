package dev.truetagger.data

import dev.truetagger.data.DownloadErrors.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadErrorsTest {
    @Test fun noInternetIsRecognized() {
        assertEquals(Reason.NETWORK, DownloadErrors.reasonOf("ERROR: Unable to download webpage: <urlopen error [Errno -3] Temporary failure in name resolution>"))
        assertEquals(Reason.NETWORK, DownloadErrors.reasonOf("java.net.UnknownHostException: Unable to resolve host \"www.youtube.com\""))
        assertEquals(Reason.NETWORK, DownloadErrors.reasonOf("Read timed out"))
    }

    @Test fun unavailableVideosAreRecognized() {
        assertEquals(Reason.UNAVAILABLE, DownloadErrors.reasonOf("ERROR: [youtube] abc: Video unavailable"))
        assertEquals(Reason.UNAVAILABLE, DownloadErrors.reasonOf("ERROR: Private video. Sign in if you've been granted access"))
        assertEquals(Reason.UNAVAILABLE, DownloadErrors.reasonOf("Sign in to confirm your age. This video may be inappropriate"))
        assertEquals(Reason.UNAVAILABLE, DownloadErrors.reasonOf("The uploader has not made this video available in your country"))
    }

    @Test fun anythingElseIsOther() {
        assertEquals(Reason.OTHER, DownloadErrors.reasonOf("ERROR: Unsupported URL"))
        assertEquals(Reason.OTHER, DownloadErrors.reasonOf(""))
    }

    @Test fun lookupFollowsTheCauseChain() {
        val error = RuntimeException("falhou", java.io.IOException("Network is unreachable"))
        assertEquals(Reason.NETWORK, DownloadErrors.reasonOf(error))
    }

    @Test fun unavailableWinsWhenBothAppear() {
        assertEquals(Reason.UNAVAILABLE, DownloadErrors.reasonOf("Video unavailable (timed out earlier)"))
    }
}
