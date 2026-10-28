package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitRemoteTest {
    @Test fun sshAddressesBecomeHttps() {
        assertEquals("https://github.com/user/repo.git", GitRemote.toHttps("git@github.com:user/repo.git"))
        assertEquals("https://github.com/user/repo.git", GitRemote.toHttps("ssh://git@github.com/user/repo.git"))
        assertEquals("https://github.com/user/repo", GitRemote.toHttps("github.com:user/repo"))
    }

    @Test fun httpsAddressesStayTheSame() {
        assertEquals("https://github.com/user/repo.git", GitRemote.toHttps("https://github.com/user/repo.git"))
    }

    @Test fun localPathsAreNotTouched() {
        assertEquals("/tmp/remoto.git", GitRemote.toHttps("/tmp/remoto.git"))
        assertEquals("C:\\pasta\\remoto.git", GitRemote.toHttps("C:\\pasta\\remoto.git"))
        assertEquals("file:///tmp/remoto.git", GitRemote.toHttps("file:///tmp/remoto.git"))
    }

    @Test fun credentialsAreStrippedFromWhatIsShownOrUsed() {
        assertEquals("https://github.com/user/repo.git", GitRemote.toHttps("https://user:ghp_segredo@github.com/user/repo.git"))
        assertEquals("https://github.com/user/repo.git", GitRemote.stripCredentials("https://ghp_token@github.com/user/repo.git"))
    }

    @Test fun readsEmbeddedCredentials() {
        assertEquals("user" to "ghp_segredo", GitRemote.embeddedCredentials("https://user:ghp_segredo@github.com/user/repo.git"))
        assertNull(GitRemote.embeddedCredentials("https://github.com/user/repo.git"))
        assertNull(GitRemote.embeddedCredentials("https://ghp_token@github.com/user/repo.git"))
    }

    @Test fun ownerAndAuthUser() {
        assertEquals("mauricioaires30", GitRemote.owner("git@github.com:mauricioaires30/musicas.git"))
        assertEquals("mauricioaires30", GitRemote.authUser("https://github.com/mauricioaires30/musicas.git"))
        assertEquals("quem", GitRemote.authUser("https://quem:tok@github.com/outro/musicas.git"))
        assertEquals("x-access-token", GitRemote.authUser("/tmp/remoto.git"))
    }
}
