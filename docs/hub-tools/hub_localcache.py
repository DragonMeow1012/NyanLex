#!/usr/bin/env python3
"""Offline stand-in for "detect + download": turn repository files into the player's LOCAL hub cache.

The game keeps downloaded rows in <config dir>/nyanlex-hub-cache-<lang>.json:
  {"schema":2,"language":"zh-tw","rows":{"<64 hex>":{"v":"<translation>","s":"mod:<modId>"}, ...}}
(first source wins for a repeated hash; the real downloader merges server, then modpack, then mod files).
Use it in a THROWAWAY dev run folder to test lookups with every translation request switched off.

  python hub_localcache.py --hub <translation-hub dir> --out <run/config> [--lang zh-tw] mod:<modId> [mod:<modId> | server:<host> ...]
"""
import argparse, json, os, sys

ap = argparse.ArgumentParser()
ap.add_argument("--hub", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--lang", default="zh-tw")
ap.add_argument("sources", nargs="+")
a = ap.parse_args()
folder = {"server": "servers", "modpack": "modpacks", "mod": "mods"}
rows = {}
for src in a.sources:
    kind, _, ident = src.partition(":")
    path = os.path.join(a.hub, folder[kind], ident, a.lang + ".json")
    data = json.load(open(path, encoding="utf-8"))
    assert data["schema"] == 2 and data["language"] == a.lang, path
    for h, v in data["entries"].items():
        rows.setdefault(h, {"v": v, "s": src})
os.makedirs(a.out, exist_ok=True)
target = os.path.join(a.out, f"nyanlex-hub-cache-{a.lang}.json")
with open(target, "w", encoding="utf-8", newline="\n") as f:
    json.dump({"schema": 2, "language": a.lang, "rows": rows}, f, ensure_ascii=False)
print(f"{len(rows)} rows -> {target}")
