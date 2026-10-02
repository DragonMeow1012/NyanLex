#!/usr/bin/env python3
"""Consistency check for translation-hub/ data, computed from GIT BLOBS (LF bytes).

Why blobs: on Windows core.autocrlf=true turns every LF in a checked-out data file into CRLF,
so sha256/size of the working-tree file differ from index.json (which records the LF form that
raw.githubusercontent.com serves).  Always hash `git cat-file blob`, never the checked-out file.

Usage (run from anywhere; --repo is the git work tree that contains translation-hub/):
  python hub_check.py --repo <repo> [--rev HEAD] [--prefix translation-hub]
                      [--compare-dir <scratch translation-hub dir>]   # regenerated output vs blobs
                      [--originals <dir of translation-team input json>]  # leak check (optional)
                      [--raw https://raw.githubusercontent.com/<owner>/<repo>/main/translation-hub]
                          # after pushing: every published file must hash to what index.json says

Exit code 0 = every check passed, 1 = at least one FAIL.
"""
import argparse, hashlib, json, os, re, subprocess, sys

try:  # the Windows console may be cp932/cp1252; values contain CJK and the marker brackets
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

HEX64 = re.compile(r"^[0-9a-f]{64}$")
CJK = re.compile(r"[一-鿿]")
ALLOWED_TOP = {"index.json", "LICENSE", "README.md"}
fails = []


def fail(msg):
    fails.append(msg)
    print("FAIL", msg)


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, check=True).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--prefix", default="translation-hub")
    ap.add_argument("--compare-dir")
    ap.add_argument("--originals")
    ap.add_argument("--raw")
    a = ap.parse_args()

    files = git(a.repo, "ls-tree", "-r", "--name-only", a.rev, a.prefix).decode().split()
    rel = [f[len(a.prefix) + 1:] for f in files]
    index = json.loads(git(a.repo, "show", f"{a.rev}:{a.prefix}/index.json").decode("utf-8"))

    # 1. nothing but data in the repository folder
    data_files = []
    for r in rel:
        parts = r.split("/")
        if r in ALLOWED_TOP:
            continue
        if len(parts) == 3 and parts[0] in ("servers", "modpacks", "mods") and parts[2].endswith(".json"):
            data_files.append(r)
        else:
            fail(f"unexpected file in {a.prefix}/: {r}")

    # 2. every data file: schema 2, hashed keys, index agrees (rows / bytes / sha256 of the blob)
    seen = set()
    values = []
    for r in data_files:
        kind, ident, fname = r.split("/")
        lang = fname[:-5]
        blob = git(a.repo, "show", f"{a.rev}:{a.prefix}/{r}")
        d = json.loads(blob.decode("utf-8"))
        entries = d.get("entries", {})
        if d.get("schema") != 2 or d.get("format") != "hub-hash-v1" or d.get("hash") != "sha256":
            fail(f"{r}: not schema 2 / hub-hash-v1 / sha256")
        if d.get("language") != lang:
            fail(f"{r}: language field {d.get('language')!r} != file name {lang!r}")
        if d.get("rows") != len(entries):
            fail(f"{r}: rows field {d.get('rows')} != {len(entries)} entries")
        bad = [k for k in entries if not HEX64.match(k)]
        if bad:
            fail(f"{r}: {len(bad)} keys are not 64 lowercase hex")
        empty = [k for k, v in entries.items() if not isinstance(v, str) or not v or len(v) > 16384]
        if empty:
            fail(f"{r}: {len(empty)} empty / non-string / over-long values")
        if set(d) - {"schema", "format", "hash", "language", "rows", "entries"}:
            fail(f"{r}: unexpected top-level fields {sorted(set(d) - {'schema','format','hash','language','rows','entries'})}")
        section = {"servers": "servers", "modpacks": "modpacks", "mods": "mods"}[kind]
        st = index.get(section, {}).get(ident, {}).get(lang)
        seen.add((section, ident, lang))
        if st is None:
            fail(f"{r}: not listed in index.json")
        else:
            if st["rows"] != len(entries):
                fail(f"{r}: index rows {st['rows']} != {len(entries)}")
            if st["bytes"] != len(blob):
                fail(f"{r}: index bytes {st['bytes']} != blob {len(blob)}")
            if st["sha256"] != hashlib.sha256(blob).hexdigest():
                fail(f"{r}: index sha256 != sha256(blob)")
        no_cjk = sum(1 for v in entries.values() if not CJK.search(v))
        print(f"{r}: rows={len(entries)} bytes={len(blob)} no-CJK-values={no_cjk}")
        values.extend(entries.values())

    # 3. index lists nothing that has no file
    for section in ("servers", "modpacks", "mods"):
        for ident, langs in index.get(section, {}).items():
            for lang in langs:
                if (section, ident, lang) not in seen:
                    fail(f"index.json lists {section}/{ident}/{lang} but the file is not in the repo")

    # 4. optional: regenerated scratch output must equal the blobs byte for byte
    if a.compare_dir:
        for r in data_files:
            p = os.path.join(a.compare_dir, *r.split("/"))
            if not os.path.isfile(p):
                print(f"compare: {r} not regenerated (skipped)")
                continue
            scratch = open(p, "rb").read().replace(b"\r\n", b"\n")  # normalise a checked-out copy
            blob = git(a.repo, "show", f"{a.rev}:{a.prefix}/{r}")
            print(f"compare {r}: {'IDENTICAL' if scratch == blob else 'DIFFERENT'}")
            if scratch != blob:
                fail(f"{r}: regenerated output differs from the committed blob")

    # 5. optional leak check: a source (English) string of the input files must not sit verbatim in a value
    if a.originals:
        needles = set()
        for root, _, names in os.walk(a.originals):
            for n in names:
                if not n.endswith(".json"):
                    continue
                try:
                    obj = json.load(open(os.path.join(root, n), encoding="utf-8"))
                except Exception:
                    continue
                for e in obj.get("entries", []) if isinstance(obj, dict) else []:
                    en = (e.get("en") or "").strip()
                    if len(en) >= 16 and re.search(r"[A-Za-z]{4}", en):
                        needles.add(en)
        hits = [(n, v) for v in values for n in needles if n in v]
        # FAIL: the value IS the source text, or contains a 6+ word source sentence.
        # Everything else (a product / event / item name left in English inside a Chinese sentence) is REVIEW.
        severe = [h for h in hits if h[1].strip() == h[0] or len(h[0].split()) >= 6]
        print(f"leak check: {len(needles)} source strings (>=16 chars), {len(hits)} verbatim hits, "
              f"{len(severe)} severe")
        for n, v in hits[:10]:
            print("   REVIEW (proper noun left in English?):", n[:50], "|", v[:50])
        for n, v in severe[:10]:
            print("   SEVERE:", n[:80], "|", v[:80])
        if severe:
            fail(f"{len(severe)} values contain the source text itself or a 6+ word source sentence")

    # 6. optional: what GitHub actually serves (raw.githubusercontent.com caches for ~5 minutes)
    if a.raw:
        import time, urllib.request
        def fetch(rel):
            time.sleep(0.3)
            req = urllib.request.Request(a.raw.rstrip("/") + "/" + rel, headers={"User-Agent": "hub-check"})
            return urllib.request.urlopen(req, timeout=120).read()
        got = fetch("index.json")
        want = git(a.repo, "show", f"{a.rev}:{a.prefix}/index.json")
        print("raw index.json:", "same as the blob" if got == want else "DIFFERENT from the blob (cache lag?)")
        if got != want:
            fail("raw index.json differs from the committed blob")
        raw_index = json.loads(got.decode("utf-8"))
        for section in ("servers", "modpacks", "mods"):
            for ident, langs in raw_index.get(section, {}).items():
                for lang, st in langs.items():
                    data = fetch(f"{section}/{ident}/{lang}.json")
                    ok = len(data) == st["bytes"] and hashlib.sha256(data).hexdigest() == st["sha256"]
                    print(f"raw {section}/{ident}/{lang}.json: {'OK' if ok else 'MISMATCH'}")
                    if not ok:
                        fail(f"raw {section}/{ident}/{lang}.json does not match index.json")

    print("RESULT", "PASS" if not fails else f"FAIL ({len(fails)})")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
