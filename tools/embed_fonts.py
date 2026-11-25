#!/usr/bin/env python3
"""Regenerates synclab-report/.../ReportFonts.kt from two WOFF2 files.
Usage: python3 tools/embed_fonts.py <AtkinsonHyperlegibleNext.woff2> <AtkinsonHyperlegibleMono.woff2>
Download the latin subsets from Google Fonts (css2?family=Atkinson+Hyperlegible+Next&family=Atkinson+Hyperlegible+Mono)."""
import base64, pathlib, sys
root = pathlib.Path(__file__).resolve().parent.parent
out = root / "synclab-report/src/commonMain/kotlin/dev/synclab/report/ReportFonts.kt"
def chunks(path):
    b = base64.b64encode(pathlib.Path(path).read_bytes()).decode()
    return " +\n        ".join(f'"{b[i:i + 16000]}"' for i in range(0, len(b), 16000))
text = out.read_text()
head = text[: text.index("internal object ReportFonts")]
out.write_text(head + f"internal object ReportFonts {{\n    val sans: String =\n        {chunks(sys.argv[1])}\n\n    val mono: String =\n        {chunks(sys.argv[2])}\n}}\n")
