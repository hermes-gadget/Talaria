#!/usr/bin/env python3
"""H2 gate: `runCatching` must not wrap suspend I/O in Talaria production code.

`runCatching` is inline, so suspend calls compile inside it — and it swallows
CancellationException, turning an orderly cancel into a phantom failure. The
migration to core.util.suspendResult is complete; this gate flags NEW sites
where a runCatching block wraps a known suspend surface (REST/DAO/WS calls,
withContext, flows). CPU-only sites (JSON decode, URI parse, base64, media
player calls) are legitimate and stay silent.

Limitation: a suspend call nested deeper than the block's next 2 lines is not
seen; code review plus the focused tests remain the backstop.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent  # repo root
MAIN = ROOT / "app" / "src" / "main" / "java"

# Tokens that indicate the runCatching block wraps suspend-capable I/O.
SUSPEND_SURFACE = re.compile(
    r"\b(api|repository|repo|clientFactory|wsAuthHelper|database|db|hermesRepository|"
    r"chatRepository|workManager|WorkManager|withContext|requestRpc|collect|"
    r"authQueryParam|wsTicket|getSessions|getStatus|transcribeAudio|syncWorker)\b",
)

failures = []
for path in sorted(MAIN.rglob("*.kt")):
    rel = str(path.relative_to(ROOT))
    lines = path.read_text(encoding="utf-8").splitlines()
    for idx, line in enumerate(lines):
        if not re.search(r"\brunCatching\s*\{", line):
            continue
        # Look at the block's first lines (same line + next 2) for a suspend surface.
        window = "\n".join(lines[idx : idx + 3])
        if SUSPEND_SURFACE.search(window):
            failures.append(f"{rel}:{idx + 1}: {line.strip()}")

if failures:
    print("H2 regression: runCatching wraps a suspend-capable call. Use")
    print("core.util.suspendResult so cancellation propagates. Found:")
    for f in failures:
        print("  " + f)
    sys.exit(1)

print("OK: no runCatching sites wrap suspend I/O.")
