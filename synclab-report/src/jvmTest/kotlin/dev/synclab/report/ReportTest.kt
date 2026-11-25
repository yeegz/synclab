package dev.synclab.report

import dev.synclab.NetworkConditions
import dev.synclab.SimConfig
import dev.synclab.SimulationFailure
import dev.synclab.simulate
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    private fun failure(): SimulationFailure = assertFailsWith {
        simulate(config = SimConfig { network = NetworkConditions.Flaky }) {
            var count = 0
            node("server") { serve<String, Unit> { _, _ -> count++ } }
            val c = node("client") { rpc<String, Unit>("server") }
            note("payload with </script><b>tags</b>")
            always("at most one") { count <= 1 }
            repeat(30) { runCatching { c.perform("send") { invoke("x") } } }
        }
    }

    @Test
    fun jsonRoundTrips() {
        val f = failure()
        val summary = dev.synclab.RunSummary("T.t", 1, false, 1, 1, 1, f.report.seed, f.report)
        val decoded = SyncLabJson.decode(SyncLabJson.encodeLine(summary))
        assertEquals(1, decoded.schema)
        assertEquals("at most one", decoded.failure!!.name)
        assertEquals(f.report.replayToken, decoded.failure.replayToken)
        assertEquals(f.report.trace.size, decoded.failure.trace.size)
    }

    @Test
    fun htmlIsSelfContainedAndEscapesTraceContent() {
        val html = HtmlReport.render(failure().report, "my.pkg.LedgerTest.converges")
        assertTrue(html.startsWith("<!doctype html>"))
        assertFalse(Regex("""<(script|link)[^>]+(src|href)=""").containsMatchIn(html), "no external assets")
        // A trace containing </script> must not break out of the embedded data block.
        assertEquals(2, Regex("</script>").findAll(html).count())
        assertTrue("prefers-color-scheme: dark" in html)
    }

    @Test
    fun fileReporterIsDiscoveredAndWritesFiles() {
        val dir = Files.createTempDirectory("synclab").toFile()
        System.setProperty("synclab.reportDir", dir.path)
        try {
            failure()
        } finally {
            System.clearProperty("synclab.reportDir")
        }
        val lines = File(dir, "runs.jsonl").readLines()
        assertTrue(lines.isNotEmpty())
        val htmls = File(dir, "failures").listFiles()!!.filter { it.extension == "html" }
        assertEquals(1, htmls.size, "discovered via ServiceLoader without any registration code")
        assertTrue(htmls.single().name.startsWith("dev.synclab.report.ReportTest."), htmls.single().name)
    }
}
