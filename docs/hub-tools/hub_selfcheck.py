#!/usr/bin/env python3
"""Step 6 self-check for translation-team intermediate JSON files (every entry: en vs zh_tw).

  python hub_selfcheck.py <file.json | dir> [...]       exit 1 when any problem is found

Checks per entry (all must pass before the file goes to the converter):
  * zh_tw is not empty and differs from en unless it is a deliberate proper noun (reported as INFO "same as en")
  * format tokens  %s %d %f %1$s %.1f %%  : same tokens; unnumbered ones in the SAME order
                   (to reorder in Chinese use numbered %1$s %2$s)
  * {placeholders}  and  &&  : same multiset
  * section-sign colour codes (§ + one char): same sequence
  * newline count and leading / trailing whitespace identical
  * no emoji, no URL that the English text does not have
  * duplicate (ns, key) inside one file
"""
import json, os, re, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

FMT = re.compile(r"%(?:(\d+)\$)?([\d.]*)([dfsS%])")
BRACE = re.compile(r"\{[A-Za-z0-9_]+\}|&&")
EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF\uFE0F]")
URL = re.compile(r"https?://|www\.", re.I)


def tokens(s):
    numbered = sorted(m.group(0) for m in FMT.finditer(s) if m.group(1))
    plain = [m.group(0) for m in FMT.finditer(s) if not m.group(1)]
    return numbered, plain


def check(path):
    problems, infos = [], []
    obj = json.load(open(path, encoding="utf-8"))
    seen = set()
    for e in obj.get("entries", []):
        k = (e.get("ns"), e.get("key"))
        en, zh = e.get("en") or "", e.get("zh_tw") or ""
        tag = f"{os.path.basename(path)}:{e.get('key')}"
        if k in seen:
            problems.append(f"{tag}: duplicate key")
        seen.add(k)
        if not zh.strip():
            problems.append(f"{tag}: empty zh_tw")
            continue
        if zh == en:
            infos.append(f"{tag}: same as en (ok only for a proper noun)")
        if tokens(en) != tokens(zh):
            problems.append(f"{tag}: format tokens differ {tokens(en)} vs {tokens(zh)}")
        if sorted(BRACE.findall(en)) != sorted(BRACE.findall(zh)):
            problems.append(f"{tag}: {{placeholder}}/&& differ")
        if re.findall(r"§.", en) != re.findall(r"§.", zh):
            problems.append(f"{tag}: colour code sequence differs")
        if en.count("\n") != zh.count("\n"):
            problems.append(f"{tag}: newline count {en.count(chr(10))} vs {zh.count(chr(10))}")
        if (en[:1].isspace(), en[-1:].isspace()) != (zh[:1].isspace(), zh[-1:].isspace()) or \
                en[:len(en) - len(en.lstrip())] != zh[:len(zh) - len(zh.lstrip())] or \
                en[len(en.rstrip()):] != zh[len(zh.rstrip()):]:
            problems.append(f"{tag}: leading/trailing whitespace differs")
        if EMOJI.search(zh) and not EMOJI.search(en):
            problems.append(f"{tag}: emoji in zh_tw")
        if URL.search(zh) and not URL.search(en):
            problems.append(f"{tag}: URL only in zh_tw")
    return len(obj.get("entries", [])), problems, infos


def main():
    files = []
    for arg in sys.argv[1:]:
        if os.path.isdir(arg):
            for r, _, ns in os.walk(arg):
                files += [os.path.join(r, n) for n in sorted(ns) if n.endswith(".json")]
        else:
            files.append(arg)
    bad = 0
    for f in files:
        try:
            n, problems, infos = check(f)
        except Exception as ex:
            print(f"{f}: not an intermediate file ({ex})")
            continue
        print(f"{f}: {n} entries, {len(problems)} problems, {len(infos)} info")
        for p in problems[:30]:
            print("   PROBLEM", p)
        bad += len(problems)
    print("RESULT", "PASS" if not bad else f"FAIL ({bad})")
    sys.exit(1 if bad else 0)


main()
