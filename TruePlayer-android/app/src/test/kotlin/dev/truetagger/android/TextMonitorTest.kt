package dev.truetagger.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMonitorTest {
    private val pt = object : GitManager.Labels {
        override fun phase(phase: GitManager.Phase, original: String) = when (phase) {
            GitManager.Phase.COUNTING -> "Contando arquivos"
            GitManager.Phase.COMPRESSING -> "Compactando"
            GitManager.Phase.SENDING -> "Enviando"
            else -> original
        }

        override fun progress(label: String, done: Int, total: Int, percent: Int) = "$label: $done de $total ($percent%)"
        override fun waiting() = "Aguardando o GitHub confirmar…"
    }

    private fun monitor(fractions: MutableList<Float?>, texts: MutableList<String>) =
        GitManager.TextMonitor(onFraction = { fractions += it }, labels = pt) { texts += it }

    @Test fun eachPhaseOfASendTakesItsOwnSliceOfTheStep() {
        val f = ArrayList<Float?>()
        val m = monitor(f, ArrayList())
        m.beginTask("Counting objects", 100); m.update(50)
        assertEquals(0.05f, f.last()!!, 0.001f)
        m.beginTask("Compressing objects", 100); m.update(100)
        assertEquals(0.30f, f.last()!!, 0.001f)
        m.beginTask("Writing objects", 200); m.update(100)
        assertEquals(0.65f, f.last()!!, 0.001f)
        m.update(100)
        assertEquals(1.0f, f.last()!!, 0.001f)
    }

    @Test fun progressNeverGoesBackWhenPhasesChange() {
        val f = ArrayList<Float?>()
        val m = monitor(f, ArrayList())
        listOf("Counting objects", "Compressing objects", "Writing objects").forEach {
            m.beginTask(it, 10)
            (1..10).forEach { m.update(1) }
        }
        val values = f.filterNotNull()
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - 0.0001f })
    }

    @Test fun partsOfASplitSendCountTowardTheWholeStep() {
        val f = ArrayList<Float?>()
        val m = monitor(f, ArrayList())
        m.partCount = 4
        m.partIndex = 2
        m.beginTask("Writing objects", 100); m.update(100)
        assertEquals(0.75f, f.last()!!, 0.001f) // 2 partes prontas + a 3ª completa = 3 de 4
    }

    @Test fun waitingForGithubDoesNotShowAHundredPercent() {
        val f = ArrayList<Float?>()
        val t = ArrayList<String>()
        val m = monitor(f, t)
        m.beginTask("Writing objects", 10); m.update(10); m.endTask()
        assertTrue(f.last()!! < 1f)
        assertTrue(t.last().contains("Aguardando o GitHub"))
    }

    @Test fun textShowsCountAndPercent() {
        val t = ArrayList<String>()
        val m = monitor(ArrayList(), t)
        m.beginTask("Writing objects", 200); m.update(50)
        assertEquals("Enviando: 50 de 200 (25%)", t.last())
    }
}
