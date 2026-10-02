#!/usr/bin/env python3
"""Takedown helper: delete data files and delist them from index.json (compact form, sorted).

  python hub_remove.py --hub <repo>/translation-hub mods/<modId>/zh-tw.json [servers/<host>/zh-tw.json ...]
  python hub_remove.py --hub <repo>/translation-hub mods/<modId>          # every language of that source

Then review `git status` / `git diff --stat`, and commit (see the guide: "hub: remove <what> (takedown request #<issue>)").
The index file is rewritten only after every named file was found.
"""
import argparse, json, os, shutil, sys

ap = argparse.ArgumentParser()
ap.add_argument("--hub", required=True)
ap.add_argument("targets", nargs="+")
a = ap.parse_args()
index_path = os.path.join(a.hub, "index.json")
text = open(index_path, encoding="utf-8").read()
index = json.loads(text)
if json.dumps(index, ensure_ascii=False, separators=(",", ":")) != text:
    sys.exit("refusing: index.json is not in the compact form the tools write")
plan = []
for t in a.targets:
    parts = t.strip("/").split("/")
    if parts[0] not in ("servers", "modpacks", "mods") or len(parts) not in (2, 3):
        sys.exit(f"not a source path: {t}")
    section, ident = parts[0], parts[1]
    langs = index.get(section, {}).get(ident)
    if langs is None:
        sys.exit(f"{t}: not listed in index.json")
    wanted = [parts[2][:-5]] if len(parts) == 3 else list(langs)
    for lang in wanted:
        if lang not in langs:
            sys.exit(f"{t}: language {lang} not listed")
        plan.append((section, ident, lang))
for section, ident, lang in plan:
    path = os.path.join(a.hub, section, ident, lang + ".json")
    if os.path.isfile(path):
        os.remove(path)
    del index[section][ident][lang]
    if not index[section][ident]:
        del index[section][ident]
        folder = os.path.join(a.hub, section, ident)
        if os.path.isdir(folder) and not os.listdir(folder):
            shutil.rmtree(folder)
    print("removed", f"{section}/{ident}/{lang}.json")
with open(index_path, "w", encoding="utf-8", newline="") as f:
    f.write(json.dumps(index, ensure_ascii=False, separators=(",", ":")))
