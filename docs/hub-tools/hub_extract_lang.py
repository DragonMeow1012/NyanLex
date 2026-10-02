#!/usr/bin/env python3
"""Step 3 helper: read a mod jar / shader-pack zip and write the "to translate" skeleton.

  python hub_extract_lang.py <file.jar|file.zip> --slug <modrinth slug> --license "<Modrinth licence id>"
         --version <version> --mc <mc version> [--kind mod|shaderpack] [--pack-name "<name>"]
         [--loader fabric] --out <scratch dir>

What it does (and the translation rule it enforces: ONLY translate what the mod itself lacks):
  * mod id: fabric.mod.json "id", else META-INF/neoforge.mods.toml / mods.toml modId, else mcmod.info modid
  * reads every assets/<ns>/lang/en_us.{json,lang} and zh_tw.{json,lang} (file names are matched
    case-insensitively and '-' == '_': en_US.lang, zh-TW.json ...); a jar may hold several namespaces
  * shader pack: shaders/lang/en_us.lang and zh_tw.lang (key=value, '#' starts a comment)
  * a key is "missing" when zh_tw lacks it, or when the zh_tw value is just the English text again
    (an English placeholder: marked "existing":"placeholder")
  * existingZhTw: none (no zh_tw keys) | partial (some English keys missing) | full (nothing missing -> no file)
  * keys starting with "_comment" / "//" and empty English values are dropped
Output: <out>/<modId or slug>.json in the translation-team intermediate format, with "zh_tw":"" left for the
translators.  Nothing is ever written into the repository.
"""
import argparse, json, os, re, sys, zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

LANG_NAME = re.compile(r"^(en_us|zh_tw)\.(json|lang)$")


def canon(name):
    return name.lower().replace("-", "_")


def parse_lang(raw, ext):
    text = raw.decode("utf-8-sig", "replace")
    if ext == "json":
        data = json.loads(text)
        return {k: v for k, v in data.items() if isinstance(v, str)}
    out = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.replace("\\n", "\n")
    return out


def mod_id(z):
    names = set(z.namelist())
    if "fabric.mod.json" in names:
        return json.loads(z.read("fabric.mod.json").decode("utf-8-sig")).get("id")
    for toml in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
        if toml in names:
            m = re.search(r'modId\s*=\s*"([^"]+)"', z.read(toml).decode("utf-8", "replace"))
            if m:
                return m.group(1)
    if "mcmod.info" in names:
        try:
            info = json.loads(z.read("mcmod.info").decode("utf-8-sig"))
            info = info.get("modList", info) if isinstance(info, dict) else info
            return info[0].get("modid")
        except Exception:
            pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("archive")
    ap.add_argument("--slug", required=True)
    ap.add_argument("--license", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--mc", required=True)
    ap.add_argument("--kind", default="mod", choices=["mod", "shaderpack"])
    ap.add_argument("--pack-name")
    ap.add_argument("--loader", default="fabric")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    z = zipfile.ZipFile(a.archive)
    en, zh = {}, {}  # (ns, key) -> value
    for name in z.namelist():
        parts = name.split("/")
        if a.kind == "mod" and len(parts) == 4 and parts[0] == "assets" and parts[2] == "lang":
            ns, fname = parts[1], parts[3]
        elif a.kind == "shaderpack" and len(parts) == 3 and parts[0] == "shaders" and parts[1] == "lang":
            ns, fname = "shader", parts[2]
        else:
            continue
        m = LANG_NAME.match(canon(fname))
        if not m:
            continue
        target = en if m.group(1) == "en_us" else zh
        for k, v in parse_lang(z.read(name), m.group(2)).items():
            target[(ns, k)] = v

    ident = None if a.kind == "shaderpack" else mod_id(z)
    if a.kind == "mod" and not ident:
        sys.exit("could not read the mod id (fabric.mod.json / mods.toml / mcmod.info)")
    entries, total, have = [], 0, 0
    for (ns, k), v in en.items():
        if k.startswith("_comment") or k.startswith("//") or not v.strip():
            continue
        total += 1
        z_val = zh.get((ns, k))
        if z_val is None:
            entries.append({"ns": ns, "key": k, "en": v, "zh_tw": ""})
        elif z_val == v and re.search(r"[A-Za-z]{3}", v):
            entries.append({"ns": ns, "key": k, "en": v, "zh_tw": "", "existing": "placeholder"})
        else:
            have += 1
    state = "none" if not zh else ("full" if not entries else "partial")
    print(f"id={ident or '-'} english keys={total} already translated={have} to translate={len(entries)} existingZhTw={state}")
    if not entries:
        print("nothing to translate: record it in the REPORT (existingZhTw=full / no lang file) and skip")
        return
    out = {"kind": a.kind, "modId": ident, "slug": a.slug, "version": a.version, "mcVersion": a.mc,
           "loader": a.loader, "license": a.license, "existingZhTw": state, "entries": entries}
    if a.kind == "shaderpack":
        out["packName"] = a.pack_name or a.slug
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, (ident or a.slug) + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("wrote", path)


if __name__ == "__main__":
    main()
