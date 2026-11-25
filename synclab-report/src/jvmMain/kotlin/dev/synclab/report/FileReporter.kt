package dev.synclab.report

import dev.synclab.RunSummary
import dev.synclab.SimulationListener
import java.io.File

/**
 * Writes reports to disk. Discovered automatically (via `ServiceLoader`) when `synclab-report` is on
 * the test classpath, so adding the dependency is all the setup there is.
 *
 * - `<dir>/runs.jsonl`: one JSON line per simulate/explore call (passing and failing), for CI dashboards.
 * - `<dir>/failures/<test>-seed<seed>.html` and `.json`: full trace of each failure.
 * - When `GITHUB_STEP_SUMMARY` is set (GitHub Actions), failures are added to the job summary.
 *
 * `<dir>` is `-Dsynclab.reportDir` / `SYNCLAB_REPORT_DIR`, default `build/synclab-reports`
 * (relative to the Gradle module, which is the test working directory).
 */
public class FileReporter : SimulationListener {
    private val dir: File
        get() = File(System.getProperty("synclab.reportDir") ?: System.getenv("SYNCLAB_REPORT_DIR") ?: "build/synclab-reports")

    override fun onFinished(summary: RunSummary) {
        if (System.getProperty("synclab.reports")?.equals("off", true) == true || System.getenv("SYNCLAB_REPORTS")?.equals("off", true) == true) return
        synchronized(FileReporter::class.java) {
            val dir = dir
            dir.mkdirs()
            File(dir, "runs.jsonl").appendText(SyncLabJson.encodeLine(summary) + "\n")
            val failure = summary.failure ?: return
            val failures = File(dir, "failures").apply { mkdirs() }
            val base = safeName(summary.testName ?: "simulation") + "-seed" + failure.seed
            val html = File(failures, "$base.html")
            html.writeText(HtmlReport.render(failure, summary.testName))
            File(failures, "$base.json").writeText(SyncLabJson.encodePretty(summary))
            System.err.println("SyncLab report: ${html.absoluteFile.toURI()}")
            System.getenv("GITHUB_STEP_SUMMARY")?.let { path ->
                runCatching {
                    File(path).appendText(
                        "### SyncLab: ${failure.violation.name}\n\n" +
                            "`${summary.testName ?: "simulation"}`, seed ${failure.seed}, faults: " +
                            (failure.faultsInvolved.joinToString { it.id }.ifEmpty { "none" }) + "\n\n" +
                            "```\nSYNCLAB_REPLAY=${failure.replayToken} ./gradlew test\n```\n\n",
                    )
                }
            }
        }
    }

    private fun safeName(s: String) = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(120)
}
