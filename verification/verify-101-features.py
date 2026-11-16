"""Final-artifact check for the NyanLex 1.0.1 feature set across the 62 release JARs.

The 1.0.1 features (glyph borrowing, overlay dialogue, transient chat log, show-original
language switch, translation manager, Claude wiring, chat composer changes) are ported tree by
tree. Most of the new Mixins use require = 0, so a missing port or a missing registration does
not fail a build; the feature silently disappears from that version. This script reads only the
finished JARs and answers: is each feature really compiled in and wired up?

Everything is derived from JAR bytes (metadata, mixin configs, class files, language files).
No source tree is read, nothing is built and nothing is written (except --json output).

Target categories (see CATEGORY below):
  legacy     Fabric 1.14.4 / 1.15.2 / 1.16.5, Forge 1.12.2 / 1.13.2. Only the chat composer
             changes exist there; any other 1.0.1 feature class is a mis-sync.
  reference  Fabric 1.21.1 and Fabric 26.1.2. Fully featured and game-tested.
  modern     Every other target; must match the reference feature set.

Checks (ids used in the output):
  C01 version        metadata and file name say 1.0.1
  C02 core-diff      core class list equals the baseline JAR (modern)
  C03 core-classes   required 1.0.1 core classes exist (modern)
  C04 platform       required platform-layer classes exist (modern)
  C05 mixin-reg      Mixin registration is complete and consistent, no class registered twice
                     (modern; the generic part, including duplicates, runs for all targets)
  C06 mixin-hooks    registered feature Mixins really carry injectors/accessors (modern)
  C07 wiring         feature classes are called from the entry point / Mixins (modern)
  C08 claude         Claude screen note string and AnthropicTransport wiring (modern)
  C09 lang           four UI languages: key sets equal baseline and each other (modern)
  C10 debug-capture  capture classes only in the allowed targets (all)
  C11 composer       chat composer 1.0.1 behaviour (all)
  C12 legacy-forbid  no 1.0.1 modern feature class in the five legacy-engine JARs
  C13 class-refs     every class the mod references inside its own package exists (all)
  C14 call-sites     entry point feature methods exist; call-site counts into the 1.0.1 core
                     API are not below the baseline JAR (modern)
  C15 forge-mixins   NeoForge 1.20.1 only: manifest MixinConfigs, a refmap per mixin config and
                     pack.mcmeta (modern, listed targets only)

A target is PASS when no check reports FAIL. Items that cannot be proven from bytecode are
reported as UNVERIFIED notes (they never turn into PASS evidence and never fail a target).

Exit status: 0 when nothing failed and nothing is missing, 1 otherwise, 2 for usage errors.
"""
import argparse
import fnmatch
import hashlib
import json
import re
import struct
import sys
import tomllib
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from zipfile import BadZipFile, ZipFile

from release_matrix import VERSION as MATRIX_VERSION, targets, version_key

EXPECTED_VERSION = "1.0.1"
EXPECTED_TARGET_COUNT = 62
PREFIX = "com/dragonmeow/nyanlex/"
CORE_PACKAGES = ("cache", "config", "hub", "service", "style", "translate", "warmup")

# ----------------------------------------------------------------------------------------
# Explicit tables. Every exception to the generic rules lives here, with its reason.
# ----------------------------------------------------------------------------------------

# Targets whose engine predates the modern code base. They only received the chat composer
# improvements, so the 1.0.1 modern features must NOT be present in them.
LEGACY_KEYS = frozenset({"fabric/1.14.4", "fabric/1.15.2", "fabric/1.16.5",
                         "forge/1.12.2", "forge/1.13.2"})

# Fully featured, game-tested targets. Used as the behavioural baseline.
REFERENCE_KEYS = ("fabric/1.21.1", "fabric/26.1.2")

# The debug capture (ComponentCapture / JsonlCaptureSink / OverlayCaptureMixin) exists only in
# the Fabric 26.1 line. Anywhere else its presence means a mis-sync.
CAPTURE_ALLOWED_KEYS = frozenset({"fabric/26.1", "fabric/26.1.1", "fabric/26.1.2"})
CAPTURE_CLASS_NAMES = ("ComponentCapture", "JsonlCaptureSink", "OverlayCaptureMixin")

# Known, deliberate core differences. sync-core.ps1 does not mirror these two translator
# files into fabric1171 (Java 16 / bundled-Gson compatibility needs hand-adapted copies), so
# the class set of the Fabric 1.17 family may differ from the baseline inside these classes
# (including nested/anonymous classes). Differences anywhere else are still failures.
KNOWN_CORE_DIFFS = {
    "fabric/1.17": ("translate/GoogleResponseParser", "translate/OpenAiTranslator"),
    "fabric/1.17.1": ("translate/GoogleResponseParser", "translate/OpenAiTranslator"),
}

# Extra core classes that the baseline JAR does not have, per target. The NeoForge 1.20.1 build
# runs through ForgeGradle on a JDK 17 toolchain; that javac emits a synthetic "$SwitchMap$..."
# holder class for each enum switch, while the JDK 21 javac used for the other trees does not.
# They are harmless compiler artifacts, so only these exact names are accepted, and only when the
# class really is such a holder (synthetic, every field named $SwitchMap$...).
KNOWN_EXTRA_SWITCHMAP_CLASSES = {
    "neoforge/1.20.1": ("config/DisplayMode$1", "config/QuickSetupPanel$1", "hub/HubSource$1",
                        "translate/ChatDeliveryMode$1", "translate/ChatSegmenter$1"),
}

# Minecraft 1.19.4 added Display entities. TextDisplayMixin is required from there on and
# must be absent before (vanilla has no such entity to hook).
TEXT_DISPLAY_FIRST_MC = "1.19.4"

# Core classes every modern JAR must contain (path below PREFIX).
REQUIRED_CORE_CLASSES = (
    "translate/OverlayDialogue", "translate/OverlayDialogueSession",
    "translate/DecorStyleMatcher", "translate/GlyphBorrowPolicy",
    "translate/TransientChatLog", "translate/ManualTextCodec",
    "cache/ManualTranslations", "config/TranslationManagerPanel",
    "service/TranslationManager", "service/OriginalLanguageSwitch",
    "translate/AnthropicTransport",
)

# Platform-layer classes (package is fabric / fabric26 / neoforge / neoforge26, found from the
# JAR). A translation manager screen class must also exist (name ends with ManagerScreen).
REQUIRED_PLATFORM_CLASSES = ("BorrowedGlyphs", "OriginalLanguage",
                             "OverlayDialogueView", "TransientChatLogger")

# Mixins that must be registered, present and hooked in every modern JAR.
REQUIRED_MIXINS = ("FontSetBorrowMixin", "LanguageInstanceMixin", "LanguageReloadMixin")
# LanguageLookupMixin is optional (some 26.x I18n classes lack the hook point); its state is
# reported per target for human review, but a half-present state still fails.
OPTIONAL_MIXINS = ("LanguageLookupMixin",)
# At least one glyph accessor group must be complete.
GLYPH_ACCESSOR_GROUPS = (("BakedGlyphAccessor",),
                         ("BakedSheetGlyphAccessor", "GlyphStitcherAccessor"))
# Fabric 1.17 - 1.18.2 borrow glyphs at the provider level (RawGlyph wrappers), a layer that
# needs no accessor into the baked glyph. Their trees contain no glyph accessor Mixin by
# design, so the "one complete accessor group" rule is waived for exactly these targets
# (a note is printed). From 1.19 on the baked-glyph approach is used and the rule applies.
ACCESSOR_FREE_KEYS = frozenset({"fabric/1.17", "fabric/1.17.1", "fabric/1.18",
                                "fabric/1.18.1", "fabric/1.18.2"})

# Mixins that must call into a platform class (proof the Mixin is connected to the feature).
MIXIN_CALLS_PLATFORM = {"FontSetBorrowMixin": "BorrowedGlyphs",
                        "LanguageInstanceMixin": "OriginalLanguage",
                        "LanguageReloadMixin": "OriginalLanguage",
                        "LanguageLookupMixin": "OriginalLanguage"}

# Annotation descriptors that make a Mixin method a real hook.
HOOK_ANNOTATION_PREFIXES = ("Lorg/spongepowered/asm/mixin/injection/",
                            "Lorg/spongepowered/asm/mixin/gen/",
                            "Lcom/llamalad7/mixinextras/injector/")
HOOK_ANNOTATIONS = ("Lorg/spongepowered/asm/mixin/Overwrite;",)
MIXIN_ANNOTATION = "Lorg/spongepowered/asm/mixin/Mixin;"

# Targets that run on Forge 47 (Minecraft 1.20.1). That FML does not read the [[mixins]] tables
# of mods.toml, so its Mixin configs load only from the JAR manifest attribute MixinConfigs. The
# JAR is also reobfuscated to SRG names, which Mixin resolves through a refmap, and Forge shows a
# resource-pack error screen on every start when pack.mcmeta is missing. NeoForge 1.20.2 and later
# register configs through mods.toml and must NOT be added here.
FORGE_MANIFEST_MIXIN_KEYS = frozenset({"neoforge/1.20.1"})

# Methods the entry point class must define (show-original switch) and the call that must sit
# inside startHudScan (the full re-translation must also recompute name tags).
MAIN_REQUIRED_METHODS = ("flipShowOriginal", "rebuildScreenAfterLanguageFlip", "startHudScan")
HUD_SCAN_METHOD = "startHudScan"
HUD_SCAN_MUST_CALL = "clearRenderMemo"
# Call-site fingerprint. The platform layer calls into the 1.0.1 core at a fixed set of places
# (every text rebuild exit calls withDecor, every HUD/title path calls the dialogue session,
# ...). A port that forgot some of them still compiles and loads. The counts are compared with
# the baseline JAR: fewer call sites is a failure. The debug capture classes are excluded so
# the Fabric 26.1 line (which has them) does not inflate the baseline.
CALL_SITE_CORE_CLASSES = ("OverlayDialogueSession", "TransientChatLog", "OriginalLanguageSwitch",
                          "GlyphBorrowPolicy", "DecorStyleMatcher", "ManualTranslations")
CALL_SITE_METHODS = ("withDecor", "clearRenderMemo")

LANGS = ("en_us", "zh_tw", "zh_cn", "ja_jp")
REQUIRED_LANG_KEYS = ("nyanlex.settings.chat_log", "nyanlex.settings.chat_log.tip",
                      "screen.nyanlex.ai.claude.note")
CLAUDE_NOTE_KEY = "screen.nyanlex.ai.claude.note"

# Chat composer 1.0.1 behaviour. The composer class must no longer bail out on a chat box that
# starts with "/", and "fill" must insert at the cursor instead of replacing the whole text.
# Method names differ per mapping set (mojmap, intermediary, SRG), so both sides are listed.
# Rule: old name present and no new name = FAIL; new name present = verified; neither = UNVERIFIED.
FILL_OLD_NAMES = frozenset({"setValue", "method_1852", "m_94144_", "setText", "func_146180_a"})
FILL_NEW_NAMES = frozenset({"insertText", "method_1867", "m_94164_", "writeText", "func_146191_b"})

# 1.0.1 feature classes (base names, nested classes included) that must not exist in the
# legacy-engine JARs. The first three come from the release rules; the rest are the other
# modern-only 1.0.1 classes, which would only appear there through a mis-sync.
LEGACY_FORBIDDEN = (
    "TranslationManagerPanel", "OverlayDialogueView", "TextDisplayMixin",
    "OverlayDialogue", "OverlayDialogueSession", "DecorStyleMatcher", "GlyphBorrowPolicy",
    "TransientChatLog", "ManualTextCodec", "ManualTranslations", "TranslationManager",
    "OriginalLanguageSwitch", "BorrowedGlyphs", "OriginalLanguage", "TransientChatLogger",
    "FontSetBorrowMixin", "LanguageInstanceMixin", "LanguageLookupMixin", "LanguageReloadMixin",
    "BakedGlyphAccessor", "BakedSheetGlyphAccessor", "GlyphStitcherAccessor",
)


def category_of(key):
    if key in LEGACY_KEYS:
        return "legacy"
    if key in REFERENCE_KEYS:
        return "reference"
    return "modern"


# ----------------------------------------------------------------------------------------
# Minimal class-file reader (constant pool, annotations, field constants, method code)
# ----------------------------------------------------------------------------------------

_OPERAND_BYTES = {16: 1, 17: 2, 18: 1, 19: 2, 20: 2, 132: 2, 178: 2, 179: 2, 180: 2, 181: 2,
                  182: 2, 183: 2, 184: 2, 185: 4, 186: 4, 187: 2, 188: 1, 189: 2, 192: 2,
                  193: 2, 197: 3, 198: 2, 199: 2, 200: 4, 201: 4}
for _op in list(range(21, 26)) + list(range(54, 59)) + [169]:
    _OPERAND_BYTES[_op] = 1
for _op in range(153, 169):
    _OPERAND_BYTES[_op] = 2


class ClassFile:
    def __init__(self, data):
        self.data = data
        self.pos = 0
        self._parse()

    def _u1(self):
        value = self.data[self.pos]
        self.pos += 1
        return value

    def _u2(self):
        value = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return value

    def _u4(self):
        value = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return value

    def _parse(self):
        if self._u4() != 0xCAFEBABE:
            raise ValueError("not a class file")
        self.pos += 4  # minor, major
        count = self._u2()
        pool = [None] * count
        index = 1
        while index < count:
            tag = self._u1()
            if tag == 1:
                size = self._u2()
                pool[index] = ("utf8", self.data[self.pos:self.pos + size].decode("utf-8", "replace"))
                self.pos += size
            elif tag in (3, 4):
                pool[index] = ("num", self._u4())
            elif tag in (5, 6):
                self.pos += 8
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                pool[index] = (tag, self._u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                pool[index] = (tag, self._u2(), self._u2())
            elif tag == 15:
                self.pos += 3
            else:
                raise ValueError("unknown constant pool tag %d" % tag)
            index += 1
        self.pool = pool
        self.access = self._u2()
        self.name = self.class_name(self._u2())
        super_index = self._u2()
        self.super_name = self.class_name(super_index) if super_index else None
        self.interfaces = [self.class_name(self._u2()) for _ in range(self._u2())]
        self.fields = self._members()
        self.methods = self._members()
        self.annotations = self._attributes()["annotations"]

    def utf(self, index):
        return self.pool[index][1]

    def class_name(self, index):
        return self.utf(self.pool[index][1])

    def _members(self):
        members = []
        for _ in range(self._u2()):
            access = self._u2()
            name = self.utf(self._u2())
            desc = self.utf(self._u2())
            attrs = self._attributes()
            members.append({"access": access, "name": name, "desc": desc,
                            "annotations": attrs["annotations"], "const": attrs["const"],
                            "code": attrs["code"]})
        return members

    def _attributes(self):
        result = {"annotations": [], "const": None, "code": None}
        for _ in range(self._u2()):
            name = self.utf(self._u2())
            length = self._u4()
            end = self.pos + length
            if name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                result["annotations"] += [self._annotation() for _ in range(self._u2())]
            elif name == "ConstantValue":
                entry = self.pool[self._u2()]
                result["const"] = entry[1] if entry else None
            elif name == "Code":
                self.pos += 4  # max_stack, max_locals
                size = self._u4()
                result["code"] = self.data[self.pos:self.pos + size]
            self.pos = end
        return result

    def _annotation(self):
        desc = self.utf(self._u2())
        values = {}
        for _ in range(self._u2()):
            key = self.utf(self._u2())
            values[key] = self._element()
        return desc, values

    def _element(self):
        tag = chr(self._u1())
        if tag == "[":
            return [self._element() for _ in range(self._u2())]
        if tag == "@":
            return self._annotation()
        if tag == "e":
            return (self.utf(self._u2()), self.utf(self._u2()))
        entry = self.pool[self._u2()]
        return entry[1] if entry and entry[0] == "utf8" else entry

    # Derived views -------------------------------------------------------------------
    def utf8_values(self):
        return [entry[1] for entry in self.pool if entry and entry[0] == "utf8"]

    def string_constants(self):
        return {self.utf(entry[1]) for entry in self.pool if entry and entry[0] == 8}

    def referenced_classes(self):
        """Internal names from class constants and from every L...; in descriptors."""
        found = set()
        for entry in self.pool:
            if entry and entry[0] == 7:
                name = self.utf(entry[1])
                if not name.startswith("["):
                    found.add(name)
        for value in self.utf8_values():
            for match in re.finditer(r"L([A-Za-z0-9_/$]+);", value):
                found.add(match.group(1))
        return found

    def invoked_methods(self, method):
        """(owner, name, descriptor) for each invoke instruction of one method."""
        code = method["code"]
        if code is None:
            return []
        out = []
        i = 0
        while i < len(code):
            op = code[i]
            if op in (182, 183, 184, 185):
                entry = self.pool[(code[i + 1] << 8) | code[i + 2]]
                nat = self.pool[entry[2]]
                out.append((self.class_name(entry[1]), self.utf(nat[1]), self.utf(nat[2])))
                i += 5 if op == 185 else 3
            elif op == 170:  # tableswitch
                p = (i + 4) & ~3
                low = int.from_bytes(code[p + 4:p + 8], "big", signed=True)
                high = int.from_bytes(code[p + 8:p + 12], "big", signed=True)
                i = p + 12 + 4 * (high - low + 1)
            elif op == 171:  # lookupswitch
                p = (i + 4) & ~3
                i = p + 8 + 8 * int.from_bytes(code[p + 4:p + 8], "big")
            elif op == 196:  # wide
                i += 6 if code[i + 1] == 132 else 4
            else:
                i += 1 + _OPERAND_BYTES.get(op, 0)
        return out

    def mixin_info(self):
        """(is_mixin, target class names, list of hook annotation descriptors)."""
        targets_found = []
        is_mixin = False
        for desc, values in self.annotations:
            if desc == MIXIN_ANNOTATION:
                is_mixin = True
                value = values.get("value")
                if isinstance(value, list):
                    targets_found += [v for v in value if isinstance(v, str)]
                elif isinstance(value, str):
                    targets_found.append(value)
        hooks = []
        for method in self.methods:
            for desc, _values in method["annotations"]:
                if desc.startswith(HOOK_ANNOTATION_PREFIXES) or desc in HOOK_ANNOTATIONS:
                    hooks.append(desc)
        return is_mixin, targets_found, hooks


# ----------------------------------------------------------------------------------------
# JAR access
# ----------------------------------------------------------------------------------------

class Jar:
    def __init__(self, path):
        self.path = Path(path)
        self.zip = ZipFile(self.path)
        bad = self.zip.testzip()
        if bad is not None:
            raise BadZipFile("CRC error in entry " + bad)
        self.names = set(self.zip.namelist())
        self._classes = {}
        self.unparseable = []

    def read(self, name):
        return self.zip.read(name)

    def text(self, name):
        return self.zip.read(name).decode("utf-8-sig")

    def has(self, name):
        return name in self.names

    def cls(self, name):
        if name not in self._classes:
            try:
                self._classes[name] = ClassFile(self.zip.read(name))
            except Exception as exc:  # unparseable bytes are reported by C13
                self._classes[name] = None
                self.unparseable.append((name, repr(exc)))
        return self._classes[name]

    def own_classes(self):
        return sorted(n for n in self.names if n.startswith(PREFIX) and n.endswith(".class"))

    def core_classes(self):
        return {n[len(PREFIX):] for n in self.own_classes() if n.split("/")[3] in CORE_PACKAGES}

    def close(self):
        self.zip.close()


def base_name(path):
    """Top-level simple name of a class path (nested suffix and extension removed)."""
    return path.rsplit("/", 1)[-1].split("$")[0].removesuffix(".class")


def parse_manifest(text):
    attrs = {}
    last = None
    for line in text.splitlines():
        if line.startswith(" ") and last:
            attrs[last] += line[1:]
        elif ":" in line:
            key, value = line.split(":", 1)
            last = key.strip()
            attrs[last] = value.strip()
    return attrs


def load_lang_keys(jar):
    """{lang: set(keys)} for the four UI languages, or an error string per language."""
    result = {}
    for lang in LANGS:
        name = "assets/nyanlex/lang/%s.json" % lang
        if not jar.has(name):
            result[lang] = "missing " + name
            continue
        try:
            data = json.loads(jar.text(name))
            result[lang] = set(data.keys())
        except Exception as exc:
            result[lang] = "unreadable %s (%s)" % (name, exc)
    return result


# ----------------------------------------------------------------------------------------
# Findings
# ----------------------------------------------------------------------------------------

class Report:
    def __init__(self):
        self.items = []  # (status, check id, message)
        self.checked = []  # check ids that ran
        self.info = {}

    def fail(self, check, message):
        self.items.append(("FAIL", check, message))

    def note(self, check, message):
        self.items.append(("NOTE", check, message))

    def unverified(self, check, message):
        self.items.append(("UNVERIFIED", check, message))

    def failures(self):
        return [i for i in self.items if i[0] == "FAIL"]


@dataclass
class Baseline:
    key: str
    core: set
    lang: dict
    calls: dict = field(default_factory=dict)
    note: str = ""


@dataclass
class Ctx:
    target: object
    category: str
    jar: Jar
    baseline: object
    main: str = None            # internal name of the entry point class
    platform: str = None        # internal package path of the platform layer
    entries: list = field(default_factory=list)  # registered mixin entries
    _refs: dict = field(default_factory=dict)

    def refs(self, class_path):
        if class_path not in self._refs:
            cf = self.jar.cls(class_path)
            self._refs[class_path] = cf.referenced_classes() if cf else set()
        return self._refs[class_path]

    def references(self, class_path, token):
        """Does class_path reference the class `token` (internal name) or one of its nested classes?"""
        return any(r == token or r.startswith(token + "$") for r in self.refs(class_path))


@dataclass
class MixinEntry:
    simple: str
    path: str
    config: str


# ----------------------------------------------------------------------------------------
# Discovery helpers
# ----------------------------------------------------------------------------------------

def find_main_class(jar):
    """Entry point class path: fabric.mod.json client entrypoint, else the @Mod class."""
    if jar.has("fabric.mod.json"):
        data = json.loads(jar.text("fabric.mod.json"))
        entrypoints = (data.get("entrypoints") or {}).get("client") or []
        if entrypoints:
            first = entrypoints[0]
            value = first.get("value") if isinstance(first, dict) else first
            return value.replace(".", "/") + ".class"
        return None
    for name in jar.own_classes():
        cf = jar.cls(name)
        if cf and any(desc.endswith("/fml/common/Mod;") for desc, _ in cf.annotations):
            return name
    return None


def registered_mixin_configs(jar):
    """Config file names registered by loader metadata (fabric.mod.json, mods.toml, manifest)."""
    names = []
    if jar.has("fabric.mod.json"):
        for item in json.loads(jar.text("fabric.mod.json")).get("mixins", []):
            names.append(item.get("config") if isinstance(item, dict) else item)
    for toml_name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
        if jar.has(toml_name):
            for item in tomllib.loads(jar.text(toml_name)).get("mixins", []):
                names.append(item.get("config"))
    if jar.has("META-INF/MANIFEST.MF"):
        attrs = parse_manifest(jar.text("META-INF/MANIFEST.MF"))
        names += [n.strip() for n in attrs.get("MixinConfigs", "").split(",") if n.strip()]
    # The same config named by two sources (mods.toml and the manifest, as on Forge 47) is loaded once.
    return list(dict.fromkeys(n for n in names if n))


def collect_mixin_entries(jar, rep):
    """MixinEntry list from registered configs; reports registration inconsistencies."""
    registered = registered_mixin_configs(jar)
    entries = []
    for config in registered:
        if not jar.has(config):
            rep.fail("C05", "mixin config %s is registered in metadata but missing from the JAR" % config)
            continue
        data = json.loads(jar.text(config))
        package = data.get("package", "").replace(".", "/")
        for key in ("mixins", "client", "server"):
            for simple in data.get(key, []):
                path = (package + "/" if package else "") + simple.replace(".", "/") + ".class"
                entries.append(MixinEntry(simple.rsplit(".", 1)[-1], path, config))
    for name in sorted(jar.names):
        if name.endswith(".mixins.json") and "/" not in name and name not in registered:
            rep.fail("C05", "mixin config %s is in the JAR but not registered in metadata "
                            "(its Mixins would never load)" % name)
    return entries


# ----------------------------------------------------------------------------------------
# Checks
# ----------------------------------------------------------------------------------------

def check_version(ctx, rep):
    jar, target = ctx.jar, ctx.target
    rep.checked.append("C01")
    found = []  # (source, value, authoritative)
    manifest = {}
    if jar.has("META-INF/MANIFEST.MF"):
        manifest = parse_manifest(jar.text("META-INF/MANIFEST.MF"))
    manifest_version = manifest.get("Implementation-Version")
    if manifest_version:
        found.append(("MANIFEST Implementation-Version", manifest_version, False))
    if jar.has("fabric.mod.json"):
        found.append(("fabric.mod.json", json.loads(jar.text("fabric.mod.json")).get("version"), True))
    for toml_name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
        if jar.has(toml_name):
            for index, mod in enumerate(tomllib.loads(jar.text(toml_name)).get("mods", [])):
                found.append(("%s mods[%d]" % (toml_name, index), mod.get("version"), True))
    if jar.has("mcmod.info"):
        data = json.loads(jar.text("mcmod.info"))
        if isinstance(data, dict):
            data = data.get("modList", [])
        for index, mod in enumerate(data):
            found.append(("mcmod.info[%d]" % index, mod.get("version"), True))
    for name in jar.own_classes():  # Forge 1.12.2 declares the version on its @Mod annotation
        cf = jar.cls(name)
        if cf:
            for desc, values in cf.annotations:
                if desc.endswith("/fml/common/Mod;") and "version" in values:
                    found.append(("@Mod on " + name[len(PREFIX):], values["version"], True))
    resolved = []
    for source, value, authoritative in found:
        if isinstance(value, str) and value.startswith("${"):
            if manifest_version:
                value = manifest_version  # the loader expands the placeholder from the manifest
            else:
                rep.fail("C01", "%s holds unresolved placeholder %s and the manifest has no "
                                "Implementation-Version" % (source, value))
                continue
        resolved.append((source, value, authoritative))
    if not any(authoritative for _s, _v, authoritative in resolved):
        rep.fail("C01", "no loader metadata with a version found")
    for source, value, _a in resolved:
        if value != EXPECTED_VERSION:
            rep.fail("C01", "%s says version %r, expected %s" % (source, value, EXPECTED_VERSION))
    expected_name = "NyanLex-Translator-%s-%s-%s.jar" % (EXPECTED_VERSION, target.label, target.minecraft)
    if ctx.jar.path.name != expected_name:
        rep.fail("C01", "file name is %s, expected %s" % (ctx.jar.path.name, expected_name))


def check_core_diff(ctx, rep):
    rep.checked.append("C02")
    if ctx.baseline is None:
        rep.fail("C02", "no usable baseline JAR, core class list cannot be compared")
        return
    if ctx.baseline.key == ctx.target.key:
        return
    core = ctx.jar.core_classes()
    missing = sorted(ctx.baseline.core - core)
    extra = sorted(core - ctx.baseline.core)
    allowed = KNOWN_CORE_DIFFS.get(ctx.target.key, ())

    def is_allowed(name):
        stem = name.removesuffix(".class")
        return any(stem == p or stem.startswith(p + "$") for p in allowed)

    switch_maps = set()
    for stem in KNOWN_EXTRA_SWITCHMAP_CLASSES.get(ctx.target.key, ()):
        name = stem + ".class"
        cf = ctx.jar.cls(PREFIX + name) if name in extra else None
        if cf is not None and (cf.access & 0x1000) and cf.fields and                 all(f["name"].startswith("$SwitchMap$") for f in cf.fields):
            switch_maps.add(name)
    if switch_maps:
        rep.note("C02", "KNOWN_DIFF allowed (javac switch-map holders of the JDK 17 build, %s): %s" % (
            ctx.target.key, ", ".join(sorted(switch_maps))))
        extra = [n for n in extra if n not in switch_maps]
    for label, names in (("missing vs baseline", missing), ("extra vs baseline", extra)):
        allowed_names = [n for n in names if is_allowed(n)]
        blocked = [n for n in names if not is_allowed(n)]
        if allowed_names:
            rep.note("C02", "KNOWN_DIFF allowed (%s compat table) %s: %s" % (
                ctx.target.key, label, ", ".join(allowed_names)))
        if blocked:
            rep.fail("C02", "core classes %s (%d): %s" % (label, len(blocked), shorten(blocked)))


def check_core_classes(ctx, rep):
    rep.checked.append("C03")
    missing = [c for c in REQUIRED_CORE_CLASSES if not ctx.jar.has(PREFIX + c + ".class")]
    if missing:
        rep.fail("C03", "missing 1.0.1 core classes: " + ", ".join(missing))


def check_platform_classes(ctx, rep):
    rep.checked.append("C04")
    if ctx.platform is None:
        rep.fail("C04", "platform package could not be determined (no entry point class found)")
        return
    missing = [c for c in REQUIRED_PLATFORM_CLASSES
               if not ctx.jar.has("%s/%s.class" % (ctx.platform, c))]
    if missing:
        rep.fail("C04", "missing platform classes in %s: %s" % (ctx.platform.rsplit("/", 1)[-1], ", ".join(missing)))
    screens = [n for n in ctx.jar.names if n.startswith(ctx.platform + "/") and "$" not in n
               and n.count("/") == ctx.platform.count("/") + 1 and n.endswith("ManagerScreen.class")]
    if not screens:
        rep.fail("C04", "no translation manager screen class (*ManagerScreen) in " + ctx.platform.rsplit("/", 1)[-1])
    ctx_screens = sorted(base_name(s) for s in screens)
    rep.info["manager_screen"] = ctx_screens


def mixin_state(ctx, simple):
    """(registered entries, class present) for one Mixin simple name."""
    entries = [e for e in ctx.entries if e.simple == simple]
    present = [e for e in entries if ctx.jar.has(e.path)]
    return entries, present


def check_mixin_registration(ctx, rep):
    jar = ctx.jar
    rep.checked.append("C05")
    for entry in ctx.entries:
        if not jar.has(entry.path):
            rep.fail("C05", "Mixin %s registered in %s but class file %s is not in the JAR" % (
                entry.simple, entry.config, entry.path))
    # A Mixin class listed twice (in two configs, or twice in one) is applied twice and the
    # game stops at start-up with a conflicting-registration error; no build step notices.
    by_path = {}
    for entry in ctx.entries:
        by_path.setdefault(entry.path, []).append(entry.config)
    for path, configs in sorted(by_path.items()):
        if len(configs) > 1:
            rep.fail("C05", "Mixin %s is registered %d times (%s)" % (
                base_name(path), len(configs), ", ".join(configs)))
    # A refmap field that names a file the JAR lacks is only a start-up warning where the game
    # runs with the names the code was compiled against (NeoForge 1.20.2+), so it is a note;
    # where a refmap is required (C15) its absence is a failure there.
    for config in sorted({e.config for e in ctx.entries}):
        try:
            refmap = json.loads(jar.text(config)).get("refmap")
        except Exception:
            continue
        if refmap and not jar.has(refmap):
            rep.note("C05", "%s names refmap %s which is not in the JAR (Mixin only logs a warning)" % (config, refmap))
    registered_paths = {e.path for e in ctx.entries}
    orphans = sorted(n for n in jar.own_classes()
                     if "/mixin/" in n and "$" not in n and n not in registered_paths)
    if orphans:
        rep.fail("C05", "Mixin classes in the JAR that no config registers (dead code): " +
                 ", ".join(base_name(n) for n in orphans))
    if ctx.category == "legacy":
        return
    for simple in REQUIRED_MIXINS:
        entries, present = mixin_state(ctx, simple)
        if not entries:
            rep.fail("C05", "%s is not registered in any mixin config" % simple)
        # duplicates and missing class files are reported by the generic checks above
    entries, present = mixin_state(ctx, "TextDisplayMixin")
    has_display = version_key(ctx.target.minecraft) >= version_key(TEXT_DISPLAY_FIRST_MC)
    class_files = [n for n in jar.names if base_name(n) == "TextDisplayMixin" and n.endswith(".class")]
    if has_display:
        if not entries:
            rep.fail("C05", "TextDisplayMixin is not registered (required from Minecraft %s)" % TEXT_DISPLAY_FIRST_MC)
    elif entries or class_files:
        rep.fail("C05", "TextDisplayMixin must not exist before Minecraft %s (no Display entity): %s" % (
            TEXT_DISPLAY_FIRST_MC, ", ".join(sorted(class_files)) or "registered only"))
    for simple in OPTIONAL_MIXINS:
        entries, present = mixin_state(ctx, simple)
        has_class = [n for n in jar.names if base_name(n) == simple and n.endswith(".class")]
        if entries and present:
            rep.info[simple] = "yes"
        elif not entries and not has_class:
            rep.info[simple] = "no"
        else:
            rep.info[simple] = "partial"
            rep.fail("C05", "%s is half present (registered=%s, class=%s)" % (simple, bool(entries), bool(has_class)))
    complete = [group for group in GLYPH_ACCESSOR_GROUPS
                if all(mixin_state(ctx, name)[1] for name in group)]
    if not complete:
        if ctx.target.key in ACCESSOR_FREE_KEYS:
            rep.note("C05", "KNOWN_EXCEPTION no glyph accessor (provider-level glyph borrowing in this Fabric 1.17-1.18.2 line)")
        else:
            rep.fail("C05", "no complete glyph accessor group registered with its class (need %s)" % (
                " or ".join("+".join(g) for g in GLYPH_ACCESSOR_GROUPS)))
    rep.info["glyph_accessors"] = ["+".join(g) for g in complete]


def check_mixin_hooks(ctx, rep):
    rep.checked.append("C06")
    wanted = list(REQUIRED_MIXINS)
    if version_key(ctx.target.minecraft) >= version_key(TEXT_DISPLAY_FIRST_MC):
        wanted.append("TextDisplayMixin")
    wanted += [n for n in OPTIONAL_MIXINS if rep.info.get(n) == "yes"]
    complete = [g for g in GLYPH_ACCESSOR_GROUPS if all(mixin_state(ctx, n)[1] for n in g)]
    for group in complete:
        wanted += list(group)
    targets_info = {}
    for simple in wanted:
        _entries, present = mixin_state(ctx, simple)
        if not present:
            continue  # absence was reported by C05
        cf = ctx.jar.cls(present[0].path)
        if cf is None:
            rep.fail("C06", "%s class file cannot be parsed" % simple)
            continue
        is_mixin, mixin_targets, hooks = cf.mixin_info()
        if not is_mixin:
            rep.fail("C06", "%s has no @Mixin annotation" % simple)
        elif not hooks:
            rep.fail("C06", "%s is a Mixin without any injector/accessor/invoker method" % simple)
        targets_info[simple] = mixin_targets
    rep.info["mixin_targets"] = targets_info


def check_wiring(ctx, rep):
    rep.checked.append("C07")
    if ctx.main is None or ctx.platform is None:
        rep.fail("C07", "entry point class not found, wiring cannot be checked")
        return
    jar, platform = ctx.jar, ctx.platform
    # The HUD call sites (action bar drawing, title/subtitle) can only be hooks inside a
    # registered Mixin, so a reference from the entry point alone is not accepted as wiring.
    hooked = []
    for entry in ctx.entries:
        cf = jar.cls(entry.path) if jar.has(entry.path) else None
        if cf is not None and cf.mixin_info()[2]:
            hooked.append(entry.path)
    for simple in ("OverlayDialogueView", "TransientChatLogger"):
        token = "%s/%s" % (platform, simple)
        if not jar.has(token + ".class"):
            continue  # absence was reported by C04
        if not any(ctx.references(c, token) for c in hooked):
            rep.fail("C07", "%s exists but no registered Mixin with a hook method references it "
                            "(orphan class, no call site%s)" % (
                                simple, "; the entry point does reference it" if ctx.references(ctx.main, token) else ""))
    original = "%s/OriginalLanguage" % platform
    if jar.has(original + ".class") and not ctx.references(ctx.main, original):
        rep.fail("C07", "entry point does not reference OriginalLanguage (show-original switch unreachable)")
    main_cf = jar.cls(ctx.main)
    if main_cf is not None:
        manager = PREFIX + "service/TranslationManager"
        if not (ctx.references(ctx.main, manager) or "setManualTranslations" in main_cf.utf8_values()):
            rep.fail("C07", "entry point references neither TranslationManager nor setManualTranslations")
    for screen in rep.info.get("manager_screen", []):
        token = "%s/%s" % (platform, screen)
        users = [n for n in jar.own_classes()
                 if n.startswith(platform + "/") and base_name(n) != screen and ctx.references(n, token)]
        if not users:
            rep.fail("C07", "%s is never opened (no other platform class references it)" % screen)
    for simple, wanted in MIXIN_CALLS_PLATFORM.items():
        _entries, present = mixin_state(ctx, simple)
        if present and not ctx.references(present[0].path, "%s/%s" % (platform, wanted)):
            rep.fail("C07", "%s does not call %s" % (simple, wanted))


def check_forge_mixin_loading(ctx, rep):
    rep.checked.append("C15")
    jar = ctx.jar
    manifest = parse_manifest(jar.text("META-INF/MANIFEST.MF")) if jar.has("META-INF/MANIFEST.MF") else {}
    listed = [n for n in re.split(r"[,\s]+", manifest.get("MixinConfigs", "")) if n]
    in_jar = sorted(n for n in jar.names if n.endswith(".mixins.json") and "/" not in n)
    if not listed:
        rep.fail("C15", "manifest has no MixinConfigs attribute (Forge 47 ignores [[mixins]] in mods.toml, "
                        "so no Mixin would be applied)")
    else:
        missing = sorted(set(in_jar) - set(listed))
        unknown = sorted(set(listed) - set(in_jar))
        if missing:
            rep.fail("C15", "mixin configs in the JAR but not listed in MixinConfigs: " + ", ".join(missing))
        if unknown:
            rep.fail("C15", "MixinConfigs lists files that are not in the JAR: " + ", ".join(unknown))
    if not in_jar:
        rep.fail("C15", "no mixin config at the JAR root")
    for name in in_jar:
        try:
            refmap = json.loads(jar.text(name)).get("refmap")
        except Exception as exc:
            rep.fail("C15", "%s cannot be read: %r" % (name, exc))
            continue
        if not refmap:
            rep.fail("C15", "%s has no refmap field (the SRG-named game would not resolve its targets)" % name)
        elif not jar.has(refmap):
            rep.fail("C15", "%s names refmap %s, which is not in the JAR" % (name, refmap))
    if not jar.has("pack.mcmeta"):
        rep.fail("C15", "pack.mcmeta is missing at the JAR root (Forge shows a resource-pack error screen on start)")


def call_site_fingerprint(jar):
    """Counts of invoke instructions from platform classes into the 1.0.1 core API."""
    counts = {}
    for name in jar.own_classes():
        if name.split("/")[3] in CORE_PACKAGES or base_name(name) in CAPTURE_CLASS_NAMES:
            continue
        cf = jar.cls(name)
        if cf is None:
            continue
        for method in cf.methods:
            for owner, called, _desc in cf.invoked_methods(method):
                simple = base_name(owner)
                if owner.startswith(PREFIX) and simple in CALL_SITE_CORE_CLASSES:
                    counts["core:" + simple] = counts.get("core:" + simple, 0) + 1
                if called in CALL_SITE_METHODS:
                    counts["method:" + called] = counts.get("method:" + called, 0) + 1
    return counts


def check_call_sites(ctx, rep):
    rep.checked.append("C14")
    jar = ctx.jar
    main_cf = jar.cls(ctx.main) if ctx.main else None
    if main_cf is None:
        rep.fail("C14", "entry point class cannot be read")
        return
    defined = {m["name"]: m for m in main_cf.methods}
    absent = [n for n in MAIN_REQUIRED_METHODS if n not in defined]
    if absent:
        rep.fail("C14", "entry point lacks methods: " + ", ".join(absent))
    if HUD_SCAN_METHOD in defined:
        called = {name for _o, name, _d in main_cf.invoked_methods(defined[HUD_SCAN_METHOD])}
        if HUD_SCAN_MUST_CALL not in called:
            rep.fail("C14", "%s() does not call %s() (full re-translation would keep stale name tags)" % (
                HUD_SCAN_METHOD, HUD_SCAN_MUST_CALL))
    text_styles = [n for n in jar.own_classes() if ctx.platform and n.startswith(ctx.platform + "/")
                   and "$" not in n and base_name(n).endswith("TextStyle")]
    if not any(any(m["name"] == "withDecor" for m in jar.cls(n).methods) for n in text_styles if jar.cls(n)):
        rep.fail("C14", "no platform TextStyle class defines withDecor (icon/decor styles are lost on rebuild)")
    if ctx.baseline is None or ctx.baseline.key == ctx.target.key:
        return
    try:
        counts = call_site_fingerprint(jar)
    except Exception as exc:
        rep.unverified("C14", "call-site counting failed (%r); not compared with the baseline" % (exc,))
        return
    rep.info["call_sites"] = counts
    fewer = ["%s: %d < %d" % (k, counts.get(k, 0), v) for k, v in sorted(ctx.baseline.calls.items())
             if counts.get(k, 0) < v]
    more = ["%s: %d > %d" % (k, counts.get(k, 0), v) for k, v in sorted(ctx.baseline.calls.items())
            if counts.get(k, 0) > v]
    if fewer:
        rep.fail("C14", "fewer call sites than baseline %s (possible lost hook): %s" % (ctx.baseline.key, ", ".join(fewer)))
    if more:
        rep.note("C14", "more call sites than baseline %s: %s" % (ctx.baseline.key, ", ".join(more)))


def check_claude(ctx, rep):
    rep.checked.append("C08")
    jar = ctx.jar
    if ctx.main is not None and not ctx.references(ctx.main, PREFIX + "translate/AnthropicTransport"):
        rep.fail("C08", "entry point does not reference translate/AnthropicTransport")
    screens = sorted(n for n in jar.own_classes() if "$" not in n and
                     n.endswith(("AiConfigScreen.class", "AiScreen.class")))
    if not screens:
        rep.fail("C08", "no AI settings screen class found")
    for name in screens:
        cf = jar.cls(name)
        if cf is None or CLAUDE_NOTE_KEY not in cf.string_constants():
            rep.fail("C08", "%s does not contain the string %s" % (base_name(name), CLAUDE_NOTE_KEY))


def check_lang(ctx, rep):
    rep.checked.append("C09")
    langs = load_lang_keys(ctx.jar)
    good = {}
    for lang, keys in langs.items():
        if isinstance(keys, str):
            rep.fail("C09", "%s: %s" % (lang, keys))
        else:
            good[lang] = keys
    for lang, keys in good.items():
        absent = [k for k in REQUIRED_LANG_KEYS if k not in keys]
        if absent:
            rep.fail("C09", "%s lacks required keys: %s" % (lang, ", ".join(absent)))
        if ctx.baseline is not None and ctx.baseline.key != ctx.target.key:
            ref = ctx.baseline.lang.get(lang)
            if isinstance(ref, set) and keys != ref:
                rep.fail("C09", "%s key set differs from baseline %s: missing %s, extra %s" % (
                    lang, ctx.baseline.key, shorten(sorted(ref - keys)), shorten(sorted(keys - ref))))
        elif ctx.baseline is None:
            rep.fail("C09", "no usable baseline JAR, %s key set cannot be compared" % lang)
    if len(good) == len(LANGS):
        union = set().union(*good.values())
        for lang, keys in good.items():
            if keys != union:
                rep.fail("C09", "%s lacks keys that other languages have: %s" % (lang, shorten(sorted(union - keys))))


def check_capture(ctx, rep):
    rep.checked.append("C10")
    jar = ctx.jar
    present = sorted(n for n in jar.names if n.endswith(".class") and base_name(n) in CAPTURE_CLASS_NAMES)
    registered = [e.simple for e in ctx.entries if e.simple in CAPTURE_CLASS_NAMES]
    if ctx.target.key in CAPTURE_ALLOWED_KEYS:
        have = {base_name(n) for n in present}
        referenced_missing = set()
        for name in jar.own_classes():
            for ref in ctx.refs(name):
                if base_name(ref) in CAPTURE_CLASS_NAMES and base_name(ref) not in have:
                    referenced_missing.add(base_name(ref))
        if referenced_missing:
            rep.fail("C10", "capture classes referenced but not in the JAR: " + ", ".join(sorted(referenced_missing)))
        return
    if present:
        rep.fail("C10", "debug capture classes present (only allowed in %s): %s" % (
            ", ".join(sorted(CAPTURE_ALLOWED_KEYS)), shorten(n.rsplit("/", 1)[-1] for n in present)))
    if registered:
        rep.fail("C10", "debug capture Mixin registered: " + ", ".join(registered))
    callers = sorted({base_name(n) for n in jar.own_classes() for ref in ctx.refs(n)
                      if base_name(ref) in CAPTURE_CLASS_NAMES and base_name(n) not in CAPTURE_CLASS_NAMES})
    if callers:
        rep.fail("C10", "classes still reference the debug capture: " + shorten(callers))


def composer_classes(ctx):
    """(class paths of the chat composer glue, description). Forge legacy uses ForgeChatComposer."""
    jar = ctx.jar
    found = [e.path for e in ctx.entries if e.simple == "ChatComposerMixin" and jar.has(e.path)]
    if found:
        return found
    return sorted(n for n in jar.own_classes() if base_name(n) == "ForgeChatComposer")


def check_composer(ctx, rep):
    rep.checked.append("C11")
    jar = ctx.jar
    panel_path = PREFIX + "translate/ChatComposerPanel.class"
    if not jar.has(panel_path):
        rep.fail("C11", "translate/ChatComposerPanel.class is missing")
    else:
        panel = jar.cls(panel_path)
        if panel is None:
            rep.fail("C11", "ChatComposerPanel cannot be parsed")
        else:
            fields = {f["name"]: f for f in panel.fields}
            limit = fields.get("CHAT_MAX_LENGTH")
            if limit is None:
                rep.fail("C11", "ChatComposerPanel has no CHAT_MAX_LENGTH field (1.0.1 length guard missing)")
            elif limit["const"] != 256:
                rep.fail("C11", "CHAT_MAX_LENGTH is %r, expected 256" % (limit["const"],))
            if "lastChat" in fields:
                rep.fail("C11", "ChatComposerPanel still tracks lastChat (pre-1.0.1 behaviour: a chat box edit cancels the translation)")
    classes = composer_classes(ctx)
    if not classes:
        rep.fail("C11", "no chat composer glue class found (ChatComposerMixin registration or ForgeChatComposer)")
        return
    skips_slash = []
    for name in classes:
        cf = jar.cls(name)
        if cf is None:
            rep.fail("C11", "%s cannot be parsed" % base_name(name))
        elif "/" in cf.string_constants():
            skips_slash.append(name)
    if skips_slash:
        rep.fail("C11", "%s still contains the string \"/\" (composer skips chat text that starts with a slash)" % (
            ", ".join(base_name(n) for n in skips_slash)))
    # The "fill" implementation may sit in a nested class (Forge uses an anonymous Host).
    scan = list(classes)
    for name in classes:
        scan += [n for n in jar.own_classes() if n.startswith(name.removesuffix(".class") + "$")]
    old, new = set(), set()
    seen_fill = False
    for name in scan:
        cf = jar.cls(name)
        if cf is None:
            continue
        for method in cf.methods:
            if method["name"] == "fill" and method["desc"] == "(Ljava/lang/String;)V":
                seen_fill = True
                for _owner, called, _desc in cf.invoked_methods(method):
                    if called in FILL_OLD_NAMES:
                        old.add(called)
                    if called in FILL_NEW_NAMES:
                        new.add(called)
    if not seen_fill:
        rep.unverified("C11", "no fill(String) method found in the composer glue; insert-at-cursor not checked")
    elif new:
        pass  # verified: fill inserts instead of replacing
    elif old:
        rep.fail("C11", "composer fill() still replaces the whole chat text (%s) instead of inserting at the cursor" % ", ".join(sorted(old)))
    else:
        rep.unverified("C11", "fill() calls neither a known replace nor a known insert method (mapping not in the table); insert-at-cursor not checked")


def check_legacy_forbidden(ctx, rep):
    rep.checked.append("C12")
    hits = sorted(n for n in ctx.jar.names if n.endswith(".class") and base_name(n) in LEGACY_FORBIDDEN)
    if hits:
        rep.fail("C12", "1.0.1 modern-only classes in a legacy-engine JAR (mis-sync): " + shorten(hits))


def check_class_refs(ctx, rep):
    rep.checked.append("C13")
    jar = ctx.jar
    dangling = {}
    for name in jar.own_classes():
        for ref in ctx.refs(name):
            if ref.startswith(PREFIX) and (ref + ".class") not in jar.names:
                dangling.setdefault(ref[len(PREFIX):], base_name(name))
    if dangling:
        rep.fail("C13", "references to classes that are not in the JAR (runtime NoClassDefFoundError), %d: %s" % (
            len(dangling), shorten(["%s (from %s)" % (k, v) for k, v in sorted(dangling.items())])))
    for name, error in jar.unparseable:
        rep.fail("C13", "class file %s cannot be parsed: %s" % (name, error))


def shorten(items, limit=6):
    items = list(items)
    text = ", ".join(items[:limit])
    return text + (" ... (+%d more)" % (len(items) - limit) if len(items) > limit else "")


# ----------------------------------------------------------------------------------------
# Target runner
# ----------------------------------------------------------------------------------------

def guarded(rep, check_id, function, ctx):
    try:
        function(ctx, rep)
    except Exception as exc:  # a crashing check must never look like a pass
        rep.fail(check_id, "check crashed: %r (%s)" % (exc, traceback.format_exc().strip().splitlines()[-2].strip()))


def evaluate_jar(target, path, baseline):
    """Run every applicable check on one JAR. Returns (Report, Ctx or None)."""
    rep = Report()
    category = category_of(target.key)
    try:
        jar = Jar(path)
    except (BadZipFile, OSError, ValueError) as exc:
        rep.fail("C00", "JAR is not readable (an unfinished build?): %s" % exc)
        return rep, None
    try:
        ctx = Ctx(target=target, category=category, jar=jar, baseline=baseline)
        try:
            ctx.main = find_main_class(jar)
        except Exception as exc:
            rep.fail("C00", "entry point lookup failed: %r" % (exc,))
        if ctx.main:
            ctx.platform = ctx.main.rsplit("/", 1)[0]
        try:
            ctx.entries = collect_mixin_entries(jar, rep)
        except Exception as exc:
            rep.fail("C05", "mixin config parsing failed: %r" % (exc,))
        steps = [("C01", check_version)]
        if category == "legacy":
            steps += [("C05", check_mixin_registration), ("C10", check_capture),
                      ("C11", check_composer), ("C12", check_legacy_forbidden),
                      ("C13", check_class_refs)]
        else:
            steps += [("C02", check_core_diff), ("C03", check_core_classes),
                      ("C04", check_platform_classes), ("C05", check_mixin_registration),
                      ("C06", check_mixin_hooks), ("C07", check_wiring), ("C08", check_claude),
                      ("C09", check_lang), ("C10", check_capture), ("C11", check_composer),
                      ("C13", check_class_refs), ("C14", check_call_sites)]
            if ctx.target.key in FORGE_MANIFEST_MIXIN_KEYS:
                steps.append(("C15", check_forge_mixin_loading))
        for check_id, function in steps:
            guarded(rep, check_id, function, ctx)
        return rep, ctx
    finally:
        jar.close()


def build_baseline(all_targets, forced_key):
    """First eligible reference JAR: has every required core class and the four language files
    with the required keys. A stale reference JAR is skipped loudly, never silently."""
    by_key = {t.key: t for t in all_targets}
    candidates = [forced_key] if forced_key else list(REFERENCE_KEYS)
    skipped = []
    for key in candidates:
        target = by_key[key]
        path = target.jar  # --jar overrides never replace the baseline JAR
        if not Path(path).is_file():
            skipped.append("%s: JAR missing" % key)
            continue
        try:
            jar = Jar(path)
        except Exception as exc:
            skipped.append("%s: unreadable (%s)" % (key, exc))
            continue
        try:
            core = jar.core_classes()
            missing = [c for c in REQUIRED_CORE_CLASSES if (c + ".class") not in core]
            langs = load_lang_keys(jar)
            problems = ["missing core classes " + ", ".join(missing)] if missing else []
            for lang, keys in langs.items():
                if isinstance(keys, str):
                    problems.append(keys)
                else:
                    absent = [k for k in REQUIRED_LANG_KEYS if k not in keys]
                    if absent:
                        problems.append("%s lacks keys %s" % (lang, ", ".join(absent)))
            if problems:
                skipped.append("%s: STALE, %s" % (key, "; ".join(problems)))
                continue
            note = "; ".join(skipped)
            return Baseline(key, core, langs, call_site_fingerprint(jar), note), skipped
        finally:
            jar.close()
    return None, skipped


def select_targets(all_targets, patterns):
    if not patterns:
        return list(all_targets)
    wanted = []
    for pattern in [p.strip() for chunk in patterns for p in chunk.split(",") if p.strip()]:
        hits = [t for t in all_targets if fnmatch.fnmatchcase(t.key, pattern)]
        if not hits:
            raise SystemExit("no target matches %r" % pattern)
        wanted += [t for t in hits if t not in wanted]
    return wanted


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def exception_table():
    lines = ["Explicit exceptions in effect:",
             "  legacy-engine targets (only chat composer changes expected): " + ", ".join(sorted(LEGACY_KEYS)),
             "  debug capture classes allowed only in: " + ", ".join(sorted(CAPTURE_ALLOWED_KEYS)),
             "  TextDisplayMixin required from Minecraft %s, forbidden before" % TEXT_DISPLAY_FIRST_MC,
             "  LanguageLookupMixin optional (state printed per target)",
             "  extra javac switch-map classes allowed: " + "; ".join(
                 "%s: %s" % (k, ", ".join(v)) for k, v in sorted(KNOWN_EXTRA_SWITCHMAP_CLASSES.items())),
             "  glyph accessor not required (provider-level borrowing): " + ", ".join(sorted(ACCESSOR_FREE_KEYS)),
             "  manifest MixinConfigs / refmap / pack.mcmeta required (Forge 47): " + ", ".join(sorted(FORGE_MANIFEST_MIXIN_KEYS))]
    for key, prefixes in KNOWN_CORE_DIFFS.items():
        lines.append("  core diff allowed for %s inside: %s" % (key, ", ".join(prefixes)))
    return "\n".join(lines)


def print_lookup_summary(results):
    """LanguageLookupMixin is optional, so its state is shown for human review: the list of
    targets without it, and Minecraft versions where the loaders disagree."""
    states = {}
    for entry in results:
        state = (entry.get("info") or {}).get("LanguageLookupMixin")
        if state:
            states[entry["target"]] = state
    if not states:
        return
    absent = sorted(k for k, v in states.items() if v != "yes")
    print("LOOKUP_MIXIN yes=%d no=%d without=%s" % (len(states) - len(absent), len(absent), ", ".join(absent) or "-"))
    by_version = {}
    for key, state in states.items():
        loader, minecraft = key.split("/", 1)
        by_version.setdefault(minecraft, {})[loader] = state
    for minecraft, loaders in sorted(by_version.items(), key=lambda item: version_key(item[0])):
        if len(set(loaders.values())) > 1:
            print("LOOKUP_MIXIN_DIFF mc=%s %s" % (minecraft, " ".join("%s=%s" % kv for kv in sorted(loaders.items()))))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--targets", action="append",
                        help="comma separated target keys or glob patterns (e.g. 'fabric/1.21.1,neoforge/26.*'); repeatable")
    parser.add_argument("--json", dest="json_path", help="write machine readable results to this path")
    parser.add_argument("--jar", action="append", default=[], metavar="KEY=PATH",
                        help="check another JAR as the given target (self-test, e.g. an old release)")
    parser.add_argument("--baseline-target", help="force the baseline target key instead of the automatic choice")
    parser.add_argument("--show-exceptions", action="store_true", help="print the explicit exception table first")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    if MATRIX_VERSION != EXPECTED_VERSION:
        print("release_matrix.VERSION is %s but this script verifies %s" % (MATRIX_VERSION, EXPECTED_VERSION), file=sys.stderr)
        return 2
    all_targets = targets()
    if len(all_targets) != EXPECTED_TARGET_COUNT:
        print("release matrix has %d targets, expected %d" % (len(all_targets), EXPECTED_TARGET_COUNT), file=sys.stderr)
        return 2
    keys = {t.key for t in all_targets}
    if not LEGACY_KEYS <= keys or not all(k in keys for k in set(REFERENCE_KEYS) | CAPTURE_ALLOWED_KEYS):
        print("exception tables name targets that are not in the release matrix", file=sys.stderr)
        return 2
    overrides = {}
    for item in args.jar:
        key, sep, path = item.partition("=")
        if not sep or key not in keys:
            parser.error("--jar expects KEY=PATH with a known target key, got %r" % item)
        overrides[key] = Path(path)
    if args.baseline_target and args.baseline_target not in keys:
        parser.error("unknown baseline target " + args.baseline_target)
    selected = select_targets(all_targets, args.targets)

    if args.show_exceptions:
        print(exception_table())
        print()
    baseline, skipped = build_baseline(all_targets, args.baseline_target)
    if baseline is None:
        print("BASELINE none usable (%s); C02 and C09 comparisons will fail" % "; ".join(skipped))
    elif skipped:
        print("BASELINE %s  (fallback; skipped: %s)" % (baseline.key, "; ".join(skipped)))
    else:
        print("BASELINE %s" % baseline.key)

    results = []
    counts = {"PASS": 0, "FAIL": 0, "MISSING_JAR": 0}
    for target in selected:
        path = Path(overrides.get(target.key, target.jar))
        entry = {"target": target.key, "category": category_of(target.key), "jar": str(path)}
        if not path.is_file():
            entry.update(status="MISSING_JAR", checks=[], findings=[])
            counts["MISSING_JAR"] += 1
            print("MISSING_JAR  %-16s %s" % (target.key, path))
            results.append(entry)
            continue
        rep, _ctx = evaluate_jar(target, path, baseline)
        failures = rep.failures()
        status = "FAIL" if failures else "PASS"
        counts[status] += 1
        stat = path.stat()
        entry.update(status=status, sha256=sha256(path), size=stat.st_size, mtime=int(stat.st_mtime),
                     checks=rep.checked, info=rep.info,
                     findings=[{"status": s, "check": c, "message": m} for s, c, m in rep.items])
        flags = ""
        if entry["category"] != "legacy":
            flags = "  lookup=%s" % rep.info.get("LanguageLookupMixin", "?")
        print("%-12s %-16s %s%s" % (status, target.key, path.name, flags))
        for item_status, check, message in rep.items:
            if item_status == "FAIL" or item_status == "UNVERIFIED" or item_status == "NOTE":
                print("    [%s %s] %s" % (item_status, check, message))
        results.append(entry)

    print_lookup_summary(results)
    summary = "VERIFY_101 passed=%d failed=%d missing=%d expected=%d" % (
        counts["PASS"], counts["FAIL"], counts["MISSING_JAR"], EXPECTED_TARGET_COUNT)
    if args.json_path:
        payload = {"version": EXPECTED_VERSION, "baseline": baseline.key if baseline else None,
                   "baselineSkipped": skipped,
                   "summary": {"passed": counts["PASS"], "failed": counts["FAIL"],
                               "missing": counts["MISSING_JAR"], "expected": EXPECTED_TARGET_COUNT},
                   "results": results}
        Path(args.json_path).write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(summary)
    complete_run = len(selected) == len(all_targets)
    ok = counts["FAIL"] == 0 and counts["MISSING_JAR"] == 0 and (not complete_run or counts["PASS"] == EXPECTED_TARGET_COUNT)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
