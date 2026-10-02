#!/usr/bin/env python3
"""Extract player-visible strings from annotations when a mod ships NO lang file.

Some mods write their settings screen text into annotations, e.g.
    @SomeConfigOption(name = "Label", desc = "Longer description")
    public boolean someField;
Those strings sit in the class file (RuntimeVisibleAnnotations).  This reads them straight from the
jar with a small class-file parser (no javap, nothing is executed, no code is copied).

  python hub_annotations.py <mod.jar> --prefix <package/path/of/the/config/classes/> \
         [--annotations <SimpleName1>,<SimpleName2>] [--elements name,desc] [--out entries.json]

Run it first WITHOUT --annotations: it only prints (to stderr) every annotation type seen under the prefix with
its count, so you can pick the ones that carry player-visible text.

Output: a JSON list of {"ns","key","field","role","en"}; key = "class:<class name relative to the
prefix, dots>#<running index inside that class>", role = the annotation element the text came from.
Feed it to the translators as the `en` side; the final input file gets "zh_tw" added per entry.
Only text that is shown to the player belongs here: leave anything that merely looks like code,
command syntax, internal ids or debug switches out when you review the list.
Limits: only string-valued elements of the annotations you name are read (enum constants, dropdown
display names and strings that live in code constants are NOT captured); the jar's modified UTF-8 is
decoded as plain UTF-8, so a character outside the BMP (an emoji) may come out garbled: check such lines by hand.
"""
import argparse, json, struct, sys, zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def parse(b):
    """Return (fields, class_annotations); a field is (name, descriptor, [annotation...]) and an
    annotation is (type descriptor, {element: string | list of strings | None})."""
    if b[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    p = 8
    n = struct.unpack(">H", b[p:p + 2])[0]
    p += 2
    cp = [None] * n
    i = 1
    while i < n:
        tag = b[p]
        p += 1
        if tag == 1:
            ln = struct.unpack(">H", b[p:p + 2])[0]
            p += 2
            cp[i] = b[p:p + ln].decode("utf-8", "replace")  # modified UTF-8; fine for plain text
            p += ln
        elif tag in (3, 4):
            p += 4
        elif tag in (5, 6):
            p += 8
            i += 1  # long/double take two slots
        elif tag in (7, 8, 16, 19, 20):
            p += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            p += 4
        elif tag == 15:
            p += 3
        else:
            raise ValueError(f"unknown constant tag {tag}")
        i += 1
    utf = lambda idx: cp[idx]
    p += 6  # access_flags, this_class, super_class
    p += 2 + 2 * struct.unpack(">H", b[p:p + 2])[0]  # interfaces

    def attributes(p):
        out = {}
        count = struct.unpack(">H", b[p:p + 2])[0]
        p += 2
        for _ in range(count):
            name_idx, length = struct.unpack(">HI", b[p:p + 6])
            p += 6
            out.setdefault(utf(name_idx), []).append(b[p:p + length])
            p += length
        return out, p

    def element_value(d, q):
        tag = chr(d[q])
        q += 1
        if tag == "s":
            return utf(struct.unpack(">H", d[q:q + 2])[0]), q + 2
        if tag in "BCDFIJSZ":
            return None, q + 2
        if tag == "e":
            return None, q + 4
        if tag == "c":
            return None, q + 2
        if tag == "@":
            _, _, q = annotation(d, q)
            return None, q
        if tag == "[":
            count = struct.unpack(">H", d[q:q + 2])[0]
            q += 2
            items = []
            for _ in range(count):
                v, q = element_value(d, q)
                items.append(v)
            return items, q
        raise ValueError(f"unknown element tag {tag!r}")

    def annotation(d, q):
        type_desc = utf(struct.unpack(">H", d[q:q + 2])[0])
        count = struct.unpack(">H", d[q + 2:q + 4])[0]
        q += 4
        values = {}
        for _ in range(count):
            name = utf(struct.unpack(">H", d[q:q + 2])[0])
            v, q = element_value(d, q + 2)
            values[name] = v
        return type_desc, values, q

    def annotations(attrs):
        found = []
        for blob in attrs.get("RuntimeVisibleAnnotations", []):
            count = struct.unpack(">H", blob[:2])[0]
            q = 2
            for _ in range(count):
                t, v, q = annotation(blob, q)
                found.append((t, v))
        return found

    fields = []
    field_count = struct.unpack(">H", b[p:p + 2])[0]
    p += 2
    for _ in range(field_count):
        _access, name_idx, desc_idx = struct.unpack(">HHH", b[p:p + 6])
        p += 6
        attrs, p = attributes(p)
        fields.append((utf(name_idx), utf(desc_idx), annotations(attrs)))
    method_count = struct.unpack(">H", b[p:p + 2])[0]
    p += 2
    for _ in range(method_count):  # methods are not needed: skip their attributes
        p += 6
        _, p = attributes(p)
    class_attrs, p = attributes(p)
    return fields, annotations(class_attrs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("--prefix", required=True, help="class path prefix inside the jar, ending with '/'")
    ap.add_argument("--annotations", default="", help="comma separated annotation simple names (empty: only list the types)")
    ap.add_argument("--elements", default="name,desc")
    ap.add_argument("--ns", default="config")
    ap.add_argument("--out")
    a = ap.parse_args()
    wanted = {x.strip() for x in a.annotations.split(",") if x.strip()}
    roles = [x.strip() for x in a.elements.split(",") if x.strip()]
    entries, counters, seen_types = [], {}, {}
    with zipfile.ZipFile(a.jar) as z:
        for name in sorted(z.namelist()):
            if not name.startswith(a.prefix) or not name.endswith(".class"):
                continue
            try:
                fields, class_anns = parse(z.read(name))
            except Exception as e:  # keep going; report at the end
                print("skip", name, e, file=sys.stderr)
                continue
            cls = name[len(a.prefix):-6].replace("/", ".")
            for field, _desc, anns in [("<class>", "", class_anns)] + fields:
                for type_desc, values in anns:
                    simple = type_desc.rstrip(";").split("/")[-1].split("$")[-1]
                    seen_types[simple] = seen_types.get(simple, 0) + 1
                    if simple not in wanted:
                        continue
                    for role in roles:
                        text = values.get(role)
                        if not isinstance(text, str) or not text:
                            continue
                        idx = counters.get(cls, 0)
                        counters[cls] = idx + 1
                        entries.append({"ns": a.ns, "key": f"class:{cls}#{idx}", "field": field,
                                        "role": role, "en": text})
    print(f"{len(entries)} entries; annotation types seen under the prefix: {seen_types}", file=sys.stderr)
    text = json.dumps(entries, ensure_ascii=False, indent=1)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
