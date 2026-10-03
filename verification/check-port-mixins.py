"""Validate vanilla Mixin targets against each port's actual named game classpath.

This is a static API check, not a replacement for launching Minecraft. It catches
missing target classes/methods/descriptors even when an injector uses require=0.
Optional third-party (@Pseudo) mixins are outside the vanilla classpath check.
"""
import argparse
import fnmatch
import json
from pathlib import Path
import re
from zipfile import ZipFile

from release_matrix import targets


class ClassReader:
    def __init__(self, data):
        self.data, self.offset = data, 0

    def number(self, count):
        value = int.from_bytes(self.data[self.offset:self.offset + count], "big")
        self.offset += count
        return value

    def read(self, count):
        value = self.data[self.offset:self.offset + count]
        self.offset += count
        return value

    def parse(self):
        if self.number(4) != 0xCAFEBABE:
            raise ValueError("Not a JVM class file")
        self.read(4)
        pool = [None] * self.number(2)
        index = 1
        while index < len(pool):
            tag = self.number(1)
            if tag == 1:
                pool[index] = self.read(self.number(2)).decode("utf-8", "replace")
            elif tag in (3, 4):
                pool[index] = self.number(4)
            elif tag in (5, 6):
                self.read(8)
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                pool[index] = self.number(2)
            elif tag in (9, 10, 11, 12, 17, 18):
                pool[index] = (tag, self.number(2), self.number(2))
            elif tag == 15:
                self.read(3)
            else:
                raise ValueError(f"Unknown constant pool tag {tag}")
            index += 1
        self.pool = pool
        access = self.number(2)
        name = pool[pool[self.number(2)]]
        super_index = self.number(2)
        parent = pool[pool[super_index]] if super_index else None
        interfaces = [pool[pool[self.number(2)]] for _ in range(self.number(2))]
        fields = self.members()
        methods = self.members()
        return dict(name=name, parent=parent, access=access, interfaces=interfaces, fields=fields,
                    methods=methods, annotations=self.attributes()["annotations"])

    def element(self):
        tag = chr(self.number(1))
        if tag == "[":
            return [self.element() for _ in range(self.number(2))]
        if tag == "@":
            return self.annotation()
        if tag == "e":
            return (self.pool[self.number(2)], self.pool[self.number(2)])
        return self.pool[self.number(2)]

    def annotation(self):
        name = self.pool[self.number(2)]
        values = {}
        for _ in range(self.number(2)):
            key = self.pool[self.number(2)]
            values[key] = self.element()
        return name, values

    def attributes(self):
        annotations = {}
        calls = []
        for _ in range(self.number(2)):
            name = self.pool[self.number(2)]
            length = self.number(4)
            end = self.offset + length
            if name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                for _ in range(self.number(2)):
                    kind, values = self.annotation()
                    annotations[kind] = values
            elif name == "Code":
                self.read(4)
                calls = self.invocations(self.read(self.number(4)))
            self.offset = end
        return dict(annotations=annotations, calls=calls)

    def invocations(self, code):
        """Decode instruction boundaries, retaining concrete field/method references."""
        refs, offset = [], 0
        one = {0x10, 0x12, *range(0x15, 0x1A), *range(0x36, 0x3B), 0xA9, 0xBC}
        two = {0x11, 0x13, 0x14, 0x84, *range(0x99, 0xA9), *range(0xB2, 0xB9),
               0xBB, 0xBD, 0xC0, 0xC1, 0xC6, 0xC7}
        while offset < len(code):
            opcode = code[offset]
            offset += 1
            if 0xB2 <= opcode <= 0xB9:
                ref = self.pool[int.from_bytes(code[offset:offset + 2], "big")]
                _, owner_index, member_index = ref
                owner = self.pool[self.pool[owner_index]]
                _, name_index, descriptor_index = self.pool[member_index]
                name, descriptor = self.pool[name_index], self.pool[descriptor_index]
                refs.append(f"L{owner};{name}" + (":" if opcode <= 0xB5 else "") + descriptor)
            if opcode == 0xAA:  # tableswitch: offsets are signed 32-bit values.
                offset += (-offset) % 4
                low = int.from_bytes(code[offset + 4:offset + 8], "big", signed=True)
                high = int.from_bytes(code[offset + 8:offset + 12], "big", signed=True)
                offset += 12 + (high - low + 1) * 4
            elif opcode == 0xAB:  # lookupswitch
                offset += (-offset) % 4
                pairs = int.from_bytes(code[offset + 4:offset + 8], "big", signed=True)
                offset += 8 + pairs * 8
            elif opcode == 0xC4:  # wide
                offset += 5 if code[offset] == 0x84 else 3
            else:
                offset += (1 if opcode in one else 2 if opcode in two else 3 if opcode == 0xC5
                           else 4 if opcode in (0xB9, 0xBA, 0xC8, 0xC9) else 0)
        if offset != len(code):
            raise ValueError("Malformed instruction boundaries")
        return refs

    def members(self):
        result = []
        for _ in range(self.number(2)):
            access = self.number(2)
            name = self.pool[self.number(2)]
            descriptor = self.pool[self.number(2)]
            attributes = self.attributes()
            result.append(dict(access=access, name=name, descriptor=descriptor, **attributes))
        return result


class Classpath:
    def __init__(self, entries):
        self.archives = [ZipFile(path) for path in entries if Path(path).is_file() and str(path).endswith(".jar")]
        self.cache = {}

    def close(self):
        for archive in self.archives:
            archive.close()

    def get(self, name):
        if name not in self.cache:
            self.cache[name] = None
            for archive in self.archives:
                try:
                    self.cache[name] = ClassReader(archive.read(name + ".class")).parse()
                    break
                except KeyError:
                    continue
        return self.cache[name]

    def members(self, name, kind, inherited=True):
        result, seen = [], set()
        while name and name not in seen:
            seen.add(name)
            data = self.get(name)
            if not data:
                break
            result += data[kind]
            name = data["parent"] if inherited else None
        return result


def arguments(descriptor):
    return re.findall(r"\[*L[^;]+;|\[*[BCDFIJSZ]", descriptor[1:descriptor.index(")")])


def matches(selector, member):
    selector = re.sub(r"^L[^;]+;", "", selector)
    if "(" in selector:
        name, tail = selector.split("(", 1)
        return fnmatch.fnmatchcase(member["name"], name) and member["descriptor"] == "(" + tail
    return fnmatch.fnmatchcase(member["name"], selector)


MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"
PSEUDO = "Lorg/spongepowered/asm/mixin/Pseudo;"
SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;"
ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;"
INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;"
UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;"


def check_target(target):
    manifest_path = target.build_dir / "port-verification.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest["minecraft"] != target.minecraft or manifest["loader"].lower() != target.loader:
        raise ValueError(f"Wrong verification target in {manifest_path}")
    classpath = Classpath(manifest["compileClasspath"])
    classes = Path(manifest["classesDir"])
    errors, skipped = [], []
    count = 0
    try:
        with ZipFile(target.jar) as jar:
            configs = [json.loads(jar.read(name)) for name in jar.namelist() if name.endswith(".mixins.json")]
        for config in configs:
            for short_name in config.get("client", []) + config.get("mixins", []):
                name = config["package"].replace(".", "/") + "/" + short_name
                mixin = ClassReader((classes / (name + ".class")).read_bytes()).parse()
                annotation = mixin["annotations"].get(MIXIN, {})
                if PSEUDO in mixin["annotations"]:
                    skipped.append(name)
                    continue
                owners = [s[1:-1] for s in annotation.get("value", [])]
                owners += [s.replace(".", "/") for s in annotation.get("targets", [])]
                if not owners:
                    errors.append(f"{short_name}: no @Mixin target")
                for owner in owners:
                    if not classpath.get(owner):
                        errors.append(f"{short_name}: missing target class {owner}")
                        continue
                    fields = classpath.members(owner, "fields")
                    # Injectors apply to declared methods, not inherited methods.
                    methods = classpath.members(owner, "methods", inherited=False)
                    if short_name == "ChatComposerMixin":
                        # The composer implements its Host directly to avoid anonymous
                        # Mixin-generated classes during NeoForge frame computation.
                        # Public @Unique interface methods must not collide with vanilla.
                        inherited_methods = classpath.members(owner, "methods")
                        for method in mixin["methods"]:
                            if method["access"] & 1 and UNIQUE in method["annotations"] and any(
                                    m["name"] == method["name"] and m["descriptor"] == method["descriptor"]
                                    for m in inherited_methods):
                                errors.append(f"{short_name}: public @Unique Host method collides with vanilla: "
                                              f"{method['name']}{method['descriptor']}")
                    for field in mixin["fields"]:
                        if SHADOW in field["annotations"] and not any(
                                f["name"] == field["name"] and f["descriptor"] == field["descriptor"] for f in fields):
                            errors.append(f"{short_name}: missing @Shadow {field['name']}:{field['descriptor']}")
                    for method in mixin["methods"]:
                        for kind, data in method["annotations"].items():
                            if kind == ACCESSOR and "value" in data:
                                if not any(field["name"] == data["value"] for field in fields):
                                    errors.append(f"{short_name}: missing accessor field {data['value']}")
                            if not kind.startswith("Lorg/spongepowered/asm/mixin/injection/") or "method" not in data:
                                continue
                            selectors = data["method"]
                            selectors = selectors if isinstance(selectors, list) else [selectors]
                            matched = [member for member in methods if any(matches(selector, member) for selector in selectors)]
                            count += 1
                            if not matched:
                                errors.append(f"{short_name}.{method['name']}: missing {selectors} in {owner}")
                                continue
                            points = data.get("at", [])
                            if isinstance(points, tuple):
                                points = [points]
                            for _, point in points:
                                callee = point.get("target")
                                if callee and point.get("value") in ("INVOKE", "INVOKE_STRING", "FIELD"):
                                    if not any(callee in member["calls"] for member in matched):
                                        errors.append(f"{short_name}.{method['name']}: @At call {callee} "
                                                      f"not found inside {selectors}")
                            if kind == INJECT:
                                args = arguments(method["descriptor"])
                                callback = next((i for i, value in enumerate(args) if value.startswith(
                                    "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo")), None)
                                if callback is not None and callback > 0:
                                    captured = args[:callback]
                                    if not any(captured == arguments(member["descriptor"]) for member in matched):
                                        errors.append(f"{short_name}.{method['name']}: callback arguments {captured} "
                                                      f"do not match {[m['descriptor'] for m in matched]}")
    finally:
        classpath.close()
    return dict(target=target.key, injectors_checked=count, optional_mod_mixins_skipped=skipped, errors=errors,
                runtime_tested=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", nargs="*", help="loader/version keys; default all new ports")
    args = parser.parse_args()
    selected = [target for target in targets() if target.expanded and (not args.target or target.key in args.target)]
    if args.target and set(args.target) != {target.key for target in selected}:
        parser.error("Unknown port target")
    failed = False
    for target in selected:
        result = check_target(target)
        print(json.dumps(result, ensure_ascii=False))
        failed |= bool(result["errors"])
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
