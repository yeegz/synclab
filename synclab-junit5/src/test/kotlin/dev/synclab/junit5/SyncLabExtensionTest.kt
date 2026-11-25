package dev.synclab.junit5

import dev.synclab.NetworkConditions
import dev.synclab.RunSummary
import dev.synclab.SimConfig
import dev.synclab.SimulationFailure
import dev.synclab.SimulationListener
import dev.synclab.SyncLab
import dev.synclab.simulate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncLabExtensionTest {
    @SyncLabTest
    fun `names with spaces are reported exactly`() {
        var name: String? = null
        val listener = object : SimulationListener { override fun onFinished(summary: RunSummary) { name = summary.testName } }
        SyncLab.addListener(listener)
        try {
            simulate(config = SimConfig { network = NetworkConditions.Perfect }) { node("a") { } }
        } finally {
            SyncLab.removeListener(listener)
        }
        assertEquals("dev.synclab.junit5.SyncLabExtensionTest.names with spaces are reported exactly", name)
    }

    @SyncLabTest
    fun `replay command names the test class`() {
        val failure = assertFailsWith<SimulationFailure> {
            simulate(config = SimConfig { network = NetworkConditions.Perfect }) { error("boom") }
        }
        assertTrue("--tests \"dev.synclab.junit5.SyncLabExtensionTest\"" in failure.message!!, failure.message)
    }
}
