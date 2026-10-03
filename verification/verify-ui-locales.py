"""Offline UI locale validation and synchronization. Never makes network requests.

Translations are authored and linguistically reviewed separately. This tool checks
structural coverage and formatting, not grammar or the quality of a translation.
Use --sync to mirror canonical root translations into existing loader resources.
Loader-only keys and distinct version-specific English text are preserved.
"""
import argparse
import collections
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANG = ROOT / "src/main/resources/assets/nyanlex/lang"
UI_LOCALES = {"en_us", "ja_jp", "zh_tw", "zh_cn"}
TOKENS = re.compile(r"%(?:\d+\$)?[sdif]|%%|\{(?:R|P|G|S|GITHUB)\}")

def formats(value):
    if not isinstance(value, str):
        raise ValueError("Locale values must be strings")
    return collections.Counter(TOKENS.findall(value))

def load(path):
    text = path.read_text(encoding="utf-8-sig")
    if path.suffix == ".lang":
        pairs = [line.split("=", 1) for line in text.splitlines()
                 if "=" in line and not line.startswith("#")]
        if len(dict(pairs)) != len(pairs):
            raise ValueError(f"Duplicate locale key in {path}")
        return dict(pairs)
    def unique(pairs):
        data = {}
        for key, value in pairs:
            if key in data:
                raise ValueError(f"Duplicate locale key in {path}: {key}")
            data[key] = value
        return data
    return json.loads(text, object_pairs_hook=unique)

def write(path, data):
    if path.suffix == ".lang":
        text = "\n".join(key + "=" + value.replace("\n", "\\n") for key, value in data.items()) + "\n"
    else:
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sync", action="store_true")
    parser.add_argument("--strict", action="store_true", help="Require all four UI locales in every loader, with no missing keys or extra locales")
    args = parser.parse_args()
    english = load(LANG / "en_us.json")
    sources = {path.stem: load(path) for path in sorted(LANG.glob("*.json"))}
    report = {"total_locales": len(sources), "required_keys": len(english),
              "structurally_complete": [], "missing_key_counts": {},
              "format_errors": {}, "english_identical_counts": {},
              "note": "Structural coverage is not proof of grammatical or native-language review."}
    for locale, data in sources.items():
        missing = set(english) - set(data)
        if missing:
            report["missing_key_counts"][locale] = len(missing)
        else:
            report["structurally_complete"].append(locale)
        wrong = [key for key in english if key in data
                 and formats(data[key]) != formats(english[key])]
        if wrong:
            report["format_errors"][locale] = wrong
        if not locale.startswith("en_"):
            report["english_identical_counts"][locale] = sum(
                value == english.get(key) and bool(re.search(r"[A-Za-z]{4}", value))
                for key, value in data.items())
    write(ROOT / "verification/ui-localization-report.json", report)
    if report["format_errors"]:
        raise SystemExit("Locale format errors: " + json.dumps(report["format_errors"]))
    synced = 0
    if args.sync:
        # Accept reviewed source wording changes only when a loader still has
        # the original shared wording. Distinct loader semantics stay untouched.
        original_english = json.loads(subprocess.check_output(
            ["git", "show", "HEAD:src/main/resources/assets/nyanlex/lang/en_us.json"],
            cwd=ROOT, encoding="utf-8"))
        shared_text_keys = {}
        for source in (english, original_english):
            for key, value in source.items():
                if value and key in english:
                    shared_text_keys.setdefault(value, key)
        for directory in sorted(ROOT.glob("*/src/main/resources/assets/nyanlex/lang")):
            base_path = directory / "en_us.json"
            if not base_path.exists():
                base_path = directory / "en_us.lang"
            if not base_path.exists():
                continue
            target_english = load(base_path)
            for path in sorted(directory.iterdir()):
                if path.suffix not in (".json", ".lang") or path.stem not in sources:
                    continue
                current = load(path)
                before = dict(current)
                for key, value in sources[path.stem].items():
                    if key in english and (key not in target_english or
                            target_english[key].replace("\\n", "\n") in (english[key], original_english.get(key))):
                        current[key] = value
                # Older screens sometimes use different keys for identical
                # shared text. Reuse only exact source matches, never infer a
                # translation for version-specific behavior or placeholders.
                for key, original in target_english.items():
                    if key in english:
                        continue
                    source_key = shared_text_keys.get(original.replace("\\n", "\n"))
                    translated = sources[path.stem].get(source_key)
                    if translated is not None and formats(translated) == formats(original):
                        current[key] = translated
                if current != before:
                    write(path, current)
                    synced += 1
    loader_errors = {}
    loader_missing = {}
    loader_locale_sets = {}
    for directory in sorted(ROOT.glob("*/src/main/resources/assets/nyanlex/lang")):
        base_path = directory / "en_us.json"
        if not base_path.exists():
            base_path = directory / "en_us.lang"
        if not base_path.exists():
            continue
        base = load(base_path)
        locale_set = {path.stem for path in directory.iterdir() if path.suffix in (".json", ".lang")}
        if locale_set != UI_LOCALES:
            loader_locale_sets[str(directory.relative_to(ROOT))] = {
                "missing": sorted(UI_LOCALES - locale_set), "unexpected": sorted(locale_set - UI_LOCALES)}
        for path in sorted(directory.iterdir()):
            if path.suffix not in (".json", ".lang"):
                continue
            data = load(path)
            name = str(path.relative_to(ROOT))
            missing = set(base) - set(data)
            if missing:
                loader_missing[name] = len(missing)
            wrong = [key for key in base if key in data and formats(data[key]) != formats(base[key])]
            if wrong:
                loader_errors[name] = wrong
    report["loader_missing_key_counts"] = loader_missing
    report["loader_format_errors"] = loader_errors
    report["loader_locale_set_errors"] = loader_locale_sets
    write(ROOT / "verification/ui-localization-report.json", report)
    if loader_errors:
        raise SystemExit("Loader locale format errors: " + json.dumps(loader_errors))
    if args.strict and (set(sources) != UI_LOCALES or report["missing_key_counts"] or
                        loader_missing or loader_locale_sets):
        raise SystemExit("Four-language UI is incomplete; see verification/ui-localization-report.json")
    print(f"{len(report['structurally_complete'])}/{len(sources)} structurally complete; "
          f"{len(english)} required keys; format arguments valid; {synced} loader files synchronized.")

if __name__ == "__main__":
    main()
