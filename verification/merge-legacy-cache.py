#!/usr/bin/env python3
"""
Manually merge a legacy-named NyanLex cache file into the current-named one.

Why: the first start after the rename (nyanslate / mctranslator -> nyanlex) copies a
cache only when the new name does not exist yet. If a new build had already created a
small cache, the old, large one was left unused. The mod now merges them itself on the
next start (once); this script does the same by hand, with the same rules, for a player
who wants to do it explicitly or to inspect the result first.

    python merge-legacy-cache.py LEGACY NEW [-o OUTPUT] [--dry-run]

    LEGACY   the old file, e.g.  .minecraft/config/nyanslate-ai-cache-zh-tw.json
    NEW      the current file, e.g. .minecraft/config/nyanlex-ai-cache-zh-tw.json
    -o OUT   write the merged file here instead of replacing NEW
    --dry-run  only report what would change; write nothing, back nothing up

Rules (identical to the mod's own migration):
  * Rows already in NEW win: same key -> NEW keeps its translation and its provisional flag.
  * LEGACY only adds keys NEW lacks (a key whose latest legacy row is a deletion is not added).
  * Output order: legacy-only rows first (oldest, evicted first when the cache is over its
    cap), then NEW's rows.
  * Before anything is written, BOTH original files are copied to <name>.bak-<YYYYmmdd-HHMMSS>
    next to them. LEGACY itself is never modified. The result replaces NEW atomically
    (temporary file, then rename). A file that cannot be understood, or any error, leaves
    everything exactly as it was.
  * Damaged rows (not JSON, no key, no translation) are skipped and counted.

Kinds of file (detected from the content): the append-only journals (AI cache, machine
translation cache, failure ledger; schema 2/3/4), the hub cache (schema 2, "rows"), and
the hub download ledger (schema 1, "sha"). If you have both the nyanslate- and the
mctranslator- generation of a file, merge nyanslate first, then mctranslator.

Close Minecraft first: the game keeps the cache in memory and appends to the file.
Python 3.8+, standard library only.
"""

import argparse
import json
import os
import sys
import time

JOURNAL, HUB_CACHE, HUB_STATE = "journal", "hub-cache", "hub-state"
BAD = object()


class Unreadable(Exception):
    pass


def _open(path):
    # Undecodable bytes become U+FFFD instead of failing, exactly like the mod's own reader.
    return open(path, "r", encoding="utf-8", errors="replace", newline=None)


def detect(path):
    """Return the kind of a cache file, or raise Unreadable."""
    with _open(path) as handle:
        first = handle.readline()
    if first == "":
        return JOURNAL  # a zero-byte file holds no rows
    try:
        header = json.loads(first)
    except ValueError:
        raise Unreadable("%s: the first line is not JSON" % path)
    if not isinstance(header, dict):
        raise Unreadable("%s: the first line is not a JSON object" % path)
    schema = header.get("schema")
    if "rows" in header and schema == 2:
        return HUB_CACHE
    if "sha" in header and schema == 1:
        return HUB_STATE
    if schema in (2, 3, 4):
        return JOURNAL
    raise Unreadable("%s: unsupported schema %r" % (path, schema))


# ----------------------------------------------------------------------------- journals

def _rows(path):
    """Yield the logical rows (text lines) after the header, as the mod's reader does."""
    with _open(path) as handle:
        first = handle.readline()
        if first == "":
            return
        header = json.loads(first)
        if header.get("schema") == 2:
            for entry in header.get("entries") or []:
                if (isinstance(entry, dict) and entry.get("key") is not None
                        and entry.get("translation") is not None):
                    row = {"key": entry["key"], "translation": entry["translation"]}
                    if "provisional" in entry:
                        row["provisional"] = entry["provisional"]
                    yield json.dumps(row, ensure_ascii=False, separators=(",", ":"))
                else:
                    yield "\0damaged"
            return
        for line in handle:
            yield line.rstrip("\r\n")


def _parse(line):
    """Return (key, deleted) for a row, or BAD."""
    if not line.strip():
        return None
    try:
        row = json.loads(line)
    except ValueError:
        return BAD
    if not isinstance(row, dict) or row.get("key") is None:
        return BAD
    key = row["key"]
    if isinstance(key, bool) or not isinstance(key, (str, int, float)):
        return BAD
    key = str(key)
    if row.get("deleted") is True:
        return key, True
    translation = row.get("translation")
    if not isinstance(translation, (str, int, float)) or isinstance(translation, bool):
        return BAD
    return key, False


def scan_journal(path, exclude=None):
    """First pass: (live row numbers in file order, live keys, number of damaged rows)."""
    last = {}
    damaged = 0
    for number, line in enumerate(_rows(path)):
        parsed = _parse(line)
        if parsed is None:
            continue
        if parsed is BAD:
            damaged += 1
            continue
        key, deleted = parsed
        if exclude is not None and key in exclude:
            continue
        if deleted:
            last.pop(key, None)
        else:
            last[key] = number
    return sorted(last.values()), set(last), damaged


def merge_journal(legacy, new, out_path):
    new_rows, new_keys, new_bad = scan_journal(new)
    old_rows, old_keys, old_bad = scan_journal(legacy, exclude=new_keys)
    result = dict(kept=len(new_keys), added=len(old_rows), damaged=new_bad + old_bad)
    if not old_rows:
        return result, None

    def write(handle):
        handle.write('{"schema":4}' + os.linesep)
        for source, wanted in ((legacy, set(old_rows)), (new, set(new_rows))):
            for number, line in enumerate(_rows(source)):
                if number in wanted:
                    handle.write(line + os.linesep)

    return result, write


# ----------------------------------------------------------------------------- hub files

def _load_json(path):
    with _open(path) as handle:
        text = handle.read()
    if not text.strip():
        return {}
    try:
        data = json.loads(text)
    except ValueError:
        raise Unreadable("%s: not valid JSON" % path)
    if not isinstance(data, dict):
        raise Unreadable("%s: not a JSON object" % path)
    return data


def merge_hub(kind, legacy, new, out_path):
    schema = 2 if kind == HUB_CACHE else 1
    field = "rows" if kind == HUB_CACHE else "sha"
    old_data, new_data = _load_json(legacy), _load_json(new)
    for path, data in ((legacy, old_data), (new, new_data)):
        if data and data.get("schema") != schema:
            raise Unreadable("%s: unsupported schema %r" % (path, data.get("schema")))

    def items(data):
        body = data.get(field)
        return body if isinstance(body, dict) else {}

    old_items, new_items = items(old_data), items(new_data)
    merged = {key: value for key, value in old_items.items() if key not in new_items}
    result = dict(kept=len(new_items), added=len(merged), damaged=0)
    if not merged:
        return result, None
    merged.update(new_items)
    document = {"schema": schema}
    language = new_data.get("language") or old_data.get("language")
    if kind == HUB_CACHE and language:
        document["language"] = language
    document[field] = merged

    def write(handle):
        handle.write(json.dumps(document, ensure_ascii=False, separators=(",", ":")))

    return result, write


# ----------------------------------------------------------------------------- main

def backup(path, stamp):
    target = "%s.bak-%s" % (path, stamp)
    with open(path, "rb") as src, open(target, "xb") as dst:
        while True:
            block = src.read(1 << 20)
            if not block:
                break
            dst.write(block)
    if os.path.getsize(target) != os.path.getsize(path):
        raise OSError("backup size mismatch for " + path)
    return target


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Merge a legacy-named NyanLex cache file into the current one "
                    "(rows already in NEW win; both originals are backed up first).")
    parser.add_argument("legacy", help="the old file (nyanslate-... or mctranslator-...); never modified")
    parser.add_argument("new", help="the current file (nyanlex-...); replaced unless -o is given")
    parser.add_argument("-o", "--output", help="write the merged result here instead of replacing NEW")
    parser.add_argument("--dry-run", action="store_true", help="report only; write and back up nothing")
    args = parser.parse_args(argv)

    legacy, new = os.path.abspath(args.legacy), os.path.abspath(args.new)
    out_path = os.path.abspath(args.output) if args.output else new
    if out_path == legacy:
        print("error: the output must not be the legacy file", file=sys.stderr)
        return 2
    for path in (legacy, new):
        if not os.path.isfile(path):
            print("error: not a file: " + path, file=sys.stderr)
            return 2

    started = time.time()
    try:
        kind = detect(legacy)
        if os.path.getsize(new) > 0 and detect(new) != kind:
            raise Unreadable("the two files are not the same kind of cache")
        if kind == JOURNAL:
            result, write = merge_journal(legacy, new, out_path)
        else:
            result, write = merge_hub(kind, legacy, new, out_path)
    except Unreadable as problem:
        print("error: %s\nnothing was changed." % problem, file=sys.stderr)
        return 2
    except OSError as problem:
        print("error: %s\nnothing was changed." % problem, file=sys.stderr)
        return 1

    print("kind: %s" % kind)
    print("rows kept from NEW: %d" % result["kept"])
    print("rows added from LEGACY: %d" % result["added"])
    if result["damaged"]:
        print("damaged rows skipped: %d" % result["damaged"])
    if write is None:
        print("LEGACY adds nothing to NEW; nothing was written.")
        return 0
    if args.dry_run:
        print("dry run: nothing was written, no backup was made.")
        return 0

    stamp = time.strftime("%Y%m%d-%H%M%S")
    temporary = out_path + ".merge.tmp"
    try:
        backups = [backup(legacy, stamp)]
        if os.path.getsize(new) > 0 or out_path != new:
            backups.append(backup(new, stamp))
        with open(temporary, "w", encoding="utf-8", newline="") as handle:
            write(handle)
        os.replace(temporary, out_path)
    except OSError as problem:
        print("error: %s\nthe original files were not replaced." % problem, file=sys.stderr)
        try:
            os.remove(temporary)
        except OSError:
            pass
        return 1
    for path in backups:
        print("backup: " + path)
    print("written: %s (%.1f s)" % (out_path, time.time() - started))
    return 0


if __name__ == "__main__":
    sys.exit(main())
