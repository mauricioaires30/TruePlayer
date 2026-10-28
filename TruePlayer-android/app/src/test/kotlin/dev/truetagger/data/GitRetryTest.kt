package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class GitRetryTest {
    @Test fun serverAndNetworkFailuresAreTransient() {
        listOf(
            "https://github.com/x/y: 500 Internal Server Error",
            "502 Bad Gateway", "503 Service Unavailable", "504 Gateway Timeout",
            "Read timed out", "Connection reset", "Unexpected end of stream",
        ).forEach { assertTrue(it, GitRetry.isTransient(IOException(it))) }
    }

    @Test fun theCauseInsideAnotherExceptionCounts() {
        assertTrue(GitRetry.isTransient(RuntimeException("falhou", IOException("500 Internal Server Error"))))
    }

    @Test fun realErrorsAreNeverRetried() {
        listOf(
            "401 Unauthorized", "not authorized", "a branch do GitHub tem outro histórico",
            "o GitHub recusou o envio (confira se algum arquivo passa de 100 MB)", "Cancelled", "Operation cancelled",
        ).forEach { assertFalse(it, GitRetry.isTransient(IOException(it))) }
    }

    @Test fun retriesWithTheGivenDelaysAndThenSucceeds() {
        var calls = 0
        val slept = ArrayList<Long>()
        val retried = ArrayList<Int>()
        val result = GitRetry.run(
            delaysMs = listOf(5L, 20L, 60L),
            onRetry = { attempt, _, _ -> retried += attempt },
            sleep = { slept += it },
        ) {
            calls++
            if (calls < 3) throw IOException("500 Internal Server Error")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(5L, 20L), slept)
        assertEquals(listOf(1, 2), retried)
    }

    @Test fun givesUpAfterTheLastDelayAndRethrowsTheError() {
        var calls = 0
        try {
            GitRetry.run(delaysMs = listOf(1L, 1L), sleep = { }) {
                calls++
                throw IOException("503 Service Unavailable")
            }
            fail("tinha que falhar")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("503"))
        }
        assertEquals(3, calls) // a primeira + 2 repetições
    }

    @Test fun nonTransientErrorsFailAtOnce() {
        var calls = 0
        try {
            GitRetry.run(delaysMs = listOf(1L, 1L, 1L), sleep = { }) {
                calls++
                throw IllegalStateException("a branch do GitHub tem outro histórico")
            }
            fail("tinha que falhar")
        } catch (expected: IllegalStateException) {
        }
        assertEquals(1, calls)
    }

    @Test fun cancellingStopsTheRetries() {
        var calls = 0
        try {
            GitRetry.run(delaysMs = listOf(1L, 1L), shouldRetry = { false }, sleep = { }) {
                calls++
                throw IOException("500 Internal Server Error")
            }
            fail("tinha que falhar")
        } catch (expected: IOException) {
        }
        assertEquals(1, calls)
    }

    @Test fun briefNamesTheProblem() {
        assertEquals("erro 500", GitRetry.brief(IOException("500 Internal Server Error")))
        assertEquals("tempo esgotado", GitRetry.brief(IOException("Read timed out")))
        assertEquals("falha de conexão", GitRetry.brief(IOException("Connection reset")))
    }
}
