# -*- coding: utf-8 -*-
"""One-off cleaner for mctranslator disk caches (mctranslator-ai-cache.json / mctranslator-cache.json).

Removes junk identified in the 2026-07-10 audit (cache-dup-analysis.md):
  1. duplicate appended lines (keeps the LAST occurrence per key, matching FileStore load order)
  2. discarded experimental-build residue: "Sent: N (AI N / GT N)..." debug-overlay lines,
     "[AI] x"-badge feedback loops, "__mctranslator_dynamic__" internal keys
  3. identity echoes (value == key)
  4. values with bare CS marker residue (translator ate the brackets)

Usage (CLOSE MINECRAFT FIRST — the mod appends to this file while running):
    python clean-ai-cache.py                      # cleans the AI cache at the default path
    python clean-ai-cache.py <path-to-cache.json> # cleans a specific cache file

A timestamped .bak copy is written next to the original before anything is touched.
"""
import json
import os
import re
import shutil
import sys
import time

DEFAULT_PATH = os.path.expandvars(
    r"%APPDATA%\.minecraft\config\mctranslator-ai-cache.json")

DEBUG_STATS = re.compile(r"^Sent: \d+ \(AI \d+ / GT \d+\)")
AI_BADGE = re.compile(r"\[AI\] ×")
DYNAMIC_KEY = "__mctranslator_dynamic__"
CS_WELLFORMED = re.compile(r"⟦\s*/?\s*CS\s*\d+\s*⟧")
CS_RESIDUE = re.compile(r"⟦?\s*/?\s*CS\s*\d+\s*⟧?")


def is_junk(k, v):
    if DEBUG_STATS.match(k) or DEBUG_STATS.match(v):
        return "debug-stats"
    if AI_BADGE.search(k):
        return "ai-badge"
    if DYNAMIC_KEY in k:
        return "dynamic-key"
    if k == v:
        return "identity-echo"
    if CS_RESIDUE.search(CS_WELLFORMED.sub("", v)):
        return "cs-residue"
    return None


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PATH
    if not os.path.isfile(path):
        print(f"not found: {path}")
        sys.exit(1)

    backup = f"{path}.bak-{time.strftime('%Y%m%d-%H%M%S')}"
    shutil.copy2(path, backup)
    print(f"backup: {backup}")

    entries = {}   # key -> value, last occurrence wins (same as FileStore load)
    dropped = {}
    total = bad = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            total += 1
            try:
                obj = json.loads(line)
                k, v = obj["k"], obj["v"]
            except (ValueError, KeyError):
                bad += 1
                continue
            reason = is_junk(k, v)
            if reason:
                dropped[reason] = dropped.get(reason, 0) + 1
                entries.pop(k, None)
            else:
                entries[k] = v

    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        for k, v in entries.items():
            f.write(json.dumps({"k": k, "v": v}, ensure_ascii=False,
                               separators=(",", ":")) + "\n")
    os.replace(tmp, path)

    print(f"lines in : {total} (unparseable skipped: {bad})")
    print(f"lines out: {len(entries)}")
    for reason, n in sorted(dropped.items(), key=lambda x: -x[1]):
        print(f"  dropped {reason}: {n}")


if __name__ == "__main__":
    main()
