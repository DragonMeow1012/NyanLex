#!/usr/bin/env python3
"""Scan release jars for mixin classes the Mixin framework would refuse to apply.

  python verification/check-mixin-rules.py <jar-or-dir> [<jar-or-dir> ...]
  python verification/check-mixin-rules.py mods-jar/1.0.0          # every *.jar under a folder

A mixin aimed at another mod's class is only applied when that class is first loaded, so an ordinary
start-up (and most smoke tests) never notices a broken one - the game then dies later with
"InvalidMixinException: ... contains non-private static field". This checks, per jar and without
loading anything, every class listed by every mixin config the jar registers:

  1. no non-private static field (unless @Shadow or compiler-synthetic); interface fields count as static;
  2. no non-private static method (unless @Unique or compiler-synthetic);
  3. every class a config lists exists in the jar;
  4. a @Pseudo mixin (aimed at classes of other mods) is only listed in a config declared
     "required": false with injectors.defaultRequire = 0, so a failed apply is a log line, not a crash;
  5. every *.mixins.json in the jar is registered by fabric.mod.json / neoforge.mods.toml / mods.toml.

Jars with no mixin config at all (the Forge 1.12.2 / 1.13.2 bytecode-patched ports) are reported as such.
Exit status 0 = every jar clean, 1 = a violation, 2 = nothing to scan.
"""
import glob
import json
import os
import re
import struct
import sys
import zipfile

ACC_PRIVATE = 0x0002
ACC_STATIC = 0x0008
ACC_INTERFACE = 0x0200
ACC_SYNTHETIC = 0x1000
SHADOW = 'Lorg/spongepowered/asm/mixin/Shadow;'
UNIQUE = 'Lorg/spongepowered/asm/mixin/Unique;'
PSEUDO = 'Lorg/spongepowered/asm/mixin/Pseudo;'


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def u2(self):
        v = struct.unpack_from('>H', self.data, self.pos)[0]
        self.pos += 2
        return v

    def u4(self):
        v = struct.unpack_from('>I', self.data, self.pos)[0]
        self.pos += 4
        return v

    def skip(self, n):
        self.pos += n


def parse_class(data):
    """Return (access, class_annotations, fields, methods); a member is (name, access, annotations)."""
    r = Reader(data)
    r.u4()
    r.u2()
    r.u2()
    count = r.u2()
    utf8 = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1:
            n = r.u2()
            utf8[i] = bytes(data[r.pos:r.pos + n]).decode('utf-8', 'replace')
            r.skip(n)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            r.skip(4)
        elif tag in (5, 6):
            r.skip(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            r.skip(2)
        elif tag == 15:
            r.skip(3)
        else:
            raise ValueError('unknown constant pool tag %d' % tag)
        i += 1
    access = r.u2()
    r.u2()
    r.u2()
    r.skip(2 * r.u2())

    def element_value():
        tag = r.u1()
        if tag == ord('e'):
            r.skip(4)
        elif tag == ord('@'):
            annotation()
        elif tag == ord('['):
            for _ in range(r.u2()):
                element_value()
        else:
            r.skip(2)

    def annotation():
        type_desc = utf8[r.u2()]
        for _ in range(r.u2()):
            r.u2()
            element_value()
        return type_desc

    def attributes():
        found = set()
        for _ in range(r.u2()):
            name = utf8[r.u2()]
            length = r.u4()
            if name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
                for _ in range(r.u2()):
                    found.add(annotation())
            else:
                r.skip(length)
        return found

    def members():
        out = []
        for _ in range(r.u2()):
            acc = r.u2()
            name = utf8[r.u2()]
            r.u2()
            out.append((name, acc, attributes()))
        return out

    fields = members()
    methods = members()
    return access, attributes(), fields, methods


def read_text(zf, name):
    try:
        return zf.read(name).decode('utf-8-sig')
    except KeyError:
        return None


def registered_configs(zf):
    """Config names a loader descriptor in the jar registers."""
    names = set()
    fmj = read_text(zf, 'fabric.mod.json')
    if fmj is not None:
        for entry in json.loads(fmj).get('mixins', []):
            names.add(entry if isinstance(entry, str) else entry.get('config'))
    for toml in ('META-INF/neoforge.mods.toml', 'META-INF/mods.toml'):
        text = read_text(zf, toml)
        if text is not None:
            names.update(re.findall(r'^\s*config\s*=\s*"([^"]+\.json)"', text, re.M))
    return names


def scan_jar(path):
    problems = []
    with zipfile.ZipFile(path) as zf:
        in_jar = {n for n in zf.namelist() if n.endswith('.mixins.json')}
        registered = registered_configs(zf)
        if not in_jar and not registered:
            return problems, 'no mixin config (bytecode-patched port)'
        for config in sorted(in_jar - registered):
            problems.append('%s is in the jar but no loader descriptor registers it' % config)
        classes = 0
        for config in sorted(registered):
            text = read_text(zf, config)
            if text is None:
                problems.append('%s is registered but missing from the jar' % config)
                continue
            cfg = json.loads(text)
            required = cfg.get('required', False)
            default_require = cfg.get('injectors', {}).get('defaultRequire')
            package = cfg['package']
            listed = [package + '.' + n for key in ('mixins', 'client', 'server') for n in cfg.get(key, [])]
            for cls in listed:
                entry = cls.replace('.', '/') + '.class'
                try:
                    data = zf.read(entry)
                except KeyError:
                    problems.append('%s lists %s but the class is missing' % (config, cls))
                    continue
                classes += 1
                access, class_annotations, fields, methods = parse_class(data)
                is_interface = bool(access & ACC_INTERFACE)
                for name, acc, annotations in fields:
                    if ((is_interface or acc & ACC_STATIC) and not acc & ACC_PRIVATE
                            and not acc & ACC_SYNTHETIC and SHADOW not in annotations):
                        problems.append('%s: non-private static field %s' % (cls, name))
                for name, acc, annotations in methods:
                    if (acc & ACC_STATIC and not acc & ACC_PRIVATE and not acc & ACC_SYNTHETIC
                            and name != '<clinit>' and UNIQUE not in annotations):
                        problems.append('%s: non-private static method %s without @Unique' % (cls, name))
                if PSEUDO in class_annotations:
                    if required:
                        problems.append('%s is @Pseudo but %s is a required config' % (cls, config))
                    elif default_require != 0:
                        problems.append('%s is optional but lacks injectors.defaultRequire = 0' % config)
        if registered and classes == 0:
            problems.append('mixin configs registered but no mixin class was found')
    return problems, '%d mixin classes in %d config(s)' % (classes, len(registered))


def main(argv):
    jars = []
    for arg in argv:
        if os.path.isdir(arg):
            jars.extend(sorted(glob.glob(os.path.join(arg, '**', '*.jar'), recursive=True)))
        else:
            jars.append(arg)
    jars = [j for j in jars if not j.endswith(('-sources.jar', '-dev.jar'))]
    if not jars:
        print('no jars to scan', file=sys.stderr)
        return 2
    failed = 0
    for jar in jars:
        problems, summary = scan_jar(jar)
        if problems:
            failed += 1
            print('FAIL %s' % os.path.basename(jar))
            for p in problems:
                print('     - %s' % p)
        else:
            print('ok   %s  (%s)' % (os.path.basename(jar), summary))
    print('%d jar(s) scanned, %d with violations' % (len(jars), failed))
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
