#!/usr/bin/env python3
"""Copy ONLY the changed data files (and only their index.json entries) from a scratch regeneration
into the repository working tree.

Why: the converters always rewrite index.json's "updatedAt" for every source they touch, even when the
data file came out byte-identical.  Writing straight into the repo would put timestamp noise on every
regenerated source (and a stray hubExport run could overwrite an unrelated server file).  So regenerate
into a scratch copy, then use this to promote just what really changed.

  python hub_apply.py --repo <repo> --hub <repo>/translation-hub --scratch <scratch translation-hub dir>
                      [--rev HEAD] [--write]

Without --write it only reports NEW / CHANGED / SAME per file.  With --write it copies NEW and CHANGED data
files (LF bytes) into --hub and replaces/inserts exactly those sources' entries in --hub/index.json
(compact JSON like the tools write it, sections sorted by key).  SAME files and their index entries
are left alone, so an unchanged source (the existing server file included) can never be touched.
"""
import argparse, hashlib, json, os, subprocess, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def git_blob(repo, rev, path):
    r = subprocess.run(["git", "-C", repo, "show", f"{rev}:{path}"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--hub", required=True)
    ap.add_argument("--scratch", required=True)
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--prefix", default="translation-hub")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    scratch_index = json.load(open(os.path.join(a.scratch, "index.json"), encoding="utf-8"))
    index_path = os.path.join(a.hub, "index.json")
    index_text = open(index_path, encoding="utf-8").read()
    index = json.loads(index_text)
    if dump(index) != index_text:
        sys.exit("refusing to edit: index.json is not in the compact form this script reproduces exactly")

    todo = []
    for section in ("servers", "modpacks", "mods"):
        for ident, langs in scratch_index.get(section, {}).items():
            for lang, st in langs.items():
                rel = f"{section}/{ident}/{lang}.json"
                p = os.path.join(a.scratch, *rel.split("/"))
                if not os.path.isfile(p):
                    continue
                data = open(p, "rb").read().replace(b"\r\n", b"\n")
                if hashlib.sha256(data).hexdigest() != st["sha256"] or len(data) != st["bytes"]:
                    sys.exit(f"{rel}: scratch file does not match its own index entry; regenerate it")
                old = git_blob(a.repo, a.rev, f"{a.prefix}/{rel}")
                state = "NEW" if old is None else ("SAME" if old == data else "CHANGED")
                print(f"{state:8} {rel}  rows={st['rows']} bytes={st['bytes']}")
                if state != "SAME":
                    todo.append((section, ident, lang, rel, data, st))
    if not todo:
        print("nothing to apply: every regenerated file is byte-identical to the committed blob")
        return
    if not a.write:
        print(f"dry run: {len(todo)} file(s) would be written (add --write)")
        return
    for section, ident, lang, rel, data, st in todo:
        target = os.path.join(a.hub, *rel.split("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as f:
            f.write(data)
        index.setdefault(section, {}).setdefault(ident, {})[lang] = st
    for section in ("servers", "modpacks", "mods"):
        index[section] = {k: dict(sorted(v.items())) for k, v in sorted(index.get(section, {}).items())}
    with open(index_path, "w", encoding="utf-8", newline="") as f:
        f.write(dump(index))
    print(f"wrote {len(todo)} file(s) and their index entries")


if __name__ == "__main__":
    main()
