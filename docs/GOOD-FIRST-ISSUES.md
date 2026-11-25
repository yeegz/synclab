# Good first issues (ready to file on GitHub)

Each is scoped, has a clear finish line, and touches one area.

1. **`Random.nextBoolean(p)` naming**: add `Random.oneIn(n)` to Gen.kt with tests. Area: `synclab-core/Gen.kt`.
2. **Report: keyboard shortcut** `/` focuses the search field in the HTML viewer. Area: `synclab-report/HtmlReport.kt`; test in `ReportTest`.
3. **Report: show `droppedEvents`** in the story when the trace was truncated. Area: `HtmlReport.kt`.
4. **`NetworkConditions.Mobile3G` preset** with documented latency and loss, sourced from a public measurement. Area: `Config.kt`, docs.
5. **Retrofit adapter example**: an `examples/retrofit-sync` module using OkHttp's `Interceptor` to route calls through `rpc`. Area: new example.
6. **Trace export to Chrome trace format** (`chrome://tracing` / Perfetto JSON) from `synclab-report`. Area: new `PerfettoExport.kt`.
7. **`converges` for more than equality**: overload taking a comparator (e.g. ignore timestamps). Area: `Simulation.kt`.
8. **Docs: troubleshooting page** collecting every violation kind with causes and fixes. Area: `docs/`.
