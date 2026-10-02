#!/usr/bin/env python3
"""Add a third-party name to ThirdPartyModFilter (hash only; the name itself never enters the repo).

The Java side normalises a name to lower-case ASCII letters+digits (everything else removed) and
stores sha256(normalised).  A name only matches text made of 1-3 words, so a name of more than 3
words is useless, and a name with no ASCII letters/digits normalises to "" (refuse it).

  python hub_hashname.py --java <repo>/src/main/java/com/dragonmeow/nyanlex/hub/tool/ThirdPartyModFilter.java
                         [--bracket] "Some Name" "Other Name"

  - no names given: only re-verifies NAME_HASHES against NAME_HASHES_CHECKSUM
  - --bracket: the names only count when written inside square brackets, e.g. "[abc]"
    (use it for short abbreviations that would otherwise collide with ordinary words)
Prints the new sorted array body and the new checksum; paste them into the Java file by hand,
then update ThirdPartyModFilterTest.realHashListIsUnchanged (the expected count and the checksum literal).
"""
import argparse, hashlib, re, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def norm(name):
    return "".join(c for c in name.lower() if c in "abcdefghijklmnopqrstuvwxyz0123456789")


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def block(src, array):
    m = re.search(r"static final String\[\] " + array + r" = \{(.*?)\};", src, re.S)
    return re.findall(r'"([0-9a-f]{64})"', m.group(1))


ap = argparse.ArgumentParser()
ap.add_argument("--java", required=True)
ap.add_argument("--bracket", action="store_true")
ap.add_argument("names", nargs="*")
a = ap.parse_args()
src = open(a.java, encoding="utf-8").read()
names = block(src, "NAME_HASHES")
brackets = block(src, "BRACKET_HASHES")
declared = re.search(r'NAME_HASHES_CHECKSUM\s*=\s*"([0-9a-f]{64})"', src).group(1)
current = sha("\n".join(sorted(set(names))))
print(f"NAME_HASHES: {len(names)} entries; checksum {'OK' if current == declared else 'MISMATCH'} ({declared[:12]}...)")
print(f"BRACKET_HASHES: {len(brackets)} entries")
target = set(brackets if a.bracket else names)
for n in a.names:
    k = norm(n)
    if not k:
        sys.exit(f"refused: {n!r} has no ASCII letters or digits")
    words = len(re.findall(r"[A-Za-z0-9]+", n))
    if words > 3 and not a.bracket:
        print(f"warning: {n!r} is {words} words; only 1-3 word sequences are ever matched")
    h = sha(k)
    print(f"{n!r} -> normalised {k!r} -> {h}  {'(already listed)' if h in target else '(new)'}")
    target.add(h)
if a.names:
    new = sorted(target)
    print("\nnew array body:")
    print(",\n".join(f'            "{h}"' for h in new))
    if not a.bracket:
        print("\nnew NAME_HASHES_CHECKSUM:", sha("\n".join(new)))
        print("new count (for the test):", len(new))
