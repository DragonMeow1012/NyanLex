#!/usr/bin/env python3
"""Subset comparison of ONE hub data file between two git revisions.

Typical use: after regenerating a server file WITHOUT chat (the default), every new key must already
exist in the older file that was exported from the same cache (new keys not in old = 0), and no
translation text may have changed behind the same key.  Compare git blobs, never working-tree files.

  python hub_subset.py --repo <repo> --path translation-hub/servers/<host>/zh-tw.json <old-rev> [<new-rev>=HEAD]
"""
import argparse, json, subprocess, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def load(repo, rev, path):
    raw = subprocess.run(["git", "-C", repo, "show", f"{rev}:{path}"], capture_output=True, check=True).stdout
    data = json.loads(raw.decode("utf-8"))
    entries = data.get("entries")
    if not isinstance(entries, dict):  # schema 1 / unknown layout: fall back to the biggest object field
        entries = max((v for v in data.values() if isinstance(v, dict)), key=len, default=data)
    return data, entries


ap = argparse.ArgumentParser()
ap.add_argument("--repo", required=True)
ap.add_argument("--path", required=True)
ap.add_argument("old")
ap.add_argument("new", nargs="?", default="HEAD")
a = ap.parse_args()

old_meta, old = load(a.repo, a.old, a.path)
new_meta, new = load(a.repo, a.new, a.path)
print("schema old/new:", old_meta.get("schema"), new_meta.get("schema"))
print("rows old/new:", len(old), len(new))
extra = set(new) - set(old)
print("new keys not in old (must be 0 when the same cache was re-exported without chat):", len(extra))
print("removed (old keys missing in new):", len(set(old) - set(new)))
changed = sum(1 for k in new if k in old and new[k] != old[k])
print("same key, different translation:", changed)
sys.exit(1 if extra or changed else 0)
