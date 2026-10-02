#!/usr/bin/env python3
"""Build / query the vanilla zh_tw glossary from the game's own language files (offline).

Both sources are in the Fabric Loom cache that Gradle already filled:
  zh_tw : %USERPROFILE%/.gradle/caches/fabric-loom/assets/indexes/<mc>-<n>.json -> "minecraft/lang/zh_tw.json" hash
          -> assets/objects/<first 2 hex>/<hash>
  en_us : NOT in the asset index (it ships inside the game jar):
          %USERPROFILE%/.gradle/caches/fabric-loom/<mc>/minecraft-client.jar -> assets/minecraft/lang/en_us.json

  python hub_vanilla_glossary.py --mc 1.21.1 --out vanilla.json          # dump {key: {en, zh_tw}}
  python hub_vanilla_glossary.py --mc 1.21.1 find "render distance" chunk mipmap    # who uses these words?
"""
import argparse, glob, json, os, sys, zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def load(mc, lang):
    root = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom")
    if lang == "en_us":
        jar = os.path.join(root, mc, "minecraft-client.jar")
        if not os.path.isfile(jar):
            sys.exit(f"{jar} not found (run a Loom build for {mc} once)")
        with zipfile.ZipFile(jar) as z:
            return json.loads(z.read("assets/minecraft/lang/en_us.json").decode("utf-8"))
    base = os.path.join(root, "assets")
    idx = sorted(glob.glob(os.path.join(base, "indexes", mc + "-*.json")))
    if not idx:
        sys.exit(f"no asset index for {mc} under {base}/indexes (run a Loom build for that version once)")
    objects = json.load(open(idx[-1], encoding="utf-8"))["objects"]
    h = objects[f"minecraft/lang/{lang}.json"]["hash"]
    return json.load(open(os.path.join(base, "objects", h[:2], h), encoding="utf-8"))


ap = argparse.ArgumentParser()
ap.add_argument("--mc", default="1.21.1")
ap.add_argument("--out")
ap.add_argument("find", nargs="*", help="'find' followed by lower-case English words to look up")
a = ap.parse_args()
en, zh = load(a.mc, "en_us"), load(a.mc, "zh_tw")
pairs = {k: {"en": en[k], "zh_tw": zh.get(k)} for k in en}
if a.out:
    json.dump(pairs, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(len(pairs), "keys written to", a.out)
if a.find and a.find[0] == "find":
    for word in a.find[1:]:
        hits = [(k, v) for k, v in pairs.items() if word.lower() in v["en"].lower() and v["en"].lower().count(" ") <= 3]
        print(f"== {word}: {len(hits)} short vanilla strings contain it")
        for k, v in hits[:12]:
            print(f"   {v['en']!r} -> {v['zh_tw']!r}   [{k}]")
