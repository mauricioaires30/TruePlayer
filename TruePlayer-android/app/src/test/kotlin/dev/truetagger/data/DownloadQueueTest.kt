package dev.truetagger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store(name: String = "queue.json") = DownloadQueueStore(File(tmp.root, name))

    @Test fun itemsSurviveANewInstance() {
        val first = store()
        first.add("https://youtu.be/a")
        first.add("https://soundcloud.com/x/sets/y", expand = true)
        val reloaded = store().load()
        assertEquals(listOf("https://youtu.be/a", "https://soundcloud.com/x/sets/y"), reloaded.map { it.url })
        assertTrue(reloaded[1].expand)
    }

    @Test fun removedItemsAreGone() {
        val s = store()
        val a = s.add("a")
        s.add("b")
        s.remove(a.id)
        assertEquals(listOf("b"), store().load().map { it.url })
    }

    @Test fun idsNeverRepeat() {
        val s = store()
        val a = s.add("a")
        s.remove(a.id)
        assertTrue(s.add("b").id > a.id)
    }

    @Test fun playlistItemsKeepTheirOrderAndPosition() {
        val s = store()
        (1..3).forEach { s.add("u$it", playlist = "Treino", total = 3, position = it) }
        assertEquals(listOf(1, 2, 3), store().load().map { it.position })
        assertEquals(2, s.pendingFor("Treino", exceptId = 1))
    }

    @Test fun jobCountsAreKeptAndThenCleared() {
        val s = store()
        s.record("Treino", true)
        s.record("Treino", true)
        s.record("Treino", false)
        val counts = store().finishJob("Treino")
        assertEquals(2, counts.done)
        assertEquals(1, counts.failed)
        assertEquals(QueueJobCounts(), s.finishJob("Treino"))
    }

    @Test fun corruptOrMissingFileMeansEmptyQueue() {
        assertTrue(store("missing.json").load().isEmpty())
        File(tmp.root, "bad.json").writeText("{ isso nao e json")
        assertTrue(store("bad.json").load().isEmpty())
    }

    @Test fun newerSchemaIsNotTouched() {
        File(tmp.root, "future.json").writeText("""{"schemaVersion":99,"items":[{"id":1,"url":"x"}]}""")
        assertTrue(store("future.json").load().isEmpty())
    }
}
