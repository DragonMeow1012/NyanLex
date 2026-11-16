"""Tests for verify-101-features.py using synthetic JARs (no build output needed).

A fully valid fake modern JAR is generated in memory, then mutated one feature at a time.
Each mutation must turn exactly the expected check into a FAIL, and the unmodified JAR must
pass. This guards against the checker degrading into one that accepts anything.

Run: python verification/test-verify-101-features.py
"""
import contextlib
import importlib.util
import io
import json
import struct
import sys
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
sys.path.insert(0, str(HERE))
_spec = importlib.util.spec_from_file_location("verify101", HERE / "verify-101-features.py")
v101 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v101)

PFX = "com/dragonmeow/nyanlex/"
PLAT = PFX + "fabric/"
MIX = PLAT + "mixin/"
INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;"
ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;"


class ClassBuilder:
    """Smallest writer that produces class files the checker's reader understands."""

    def __init__(self, name, access=0x21):
        self.name = name
        self.access = access
        self.pool = []
        self.cache = {}
        self.this = self.cls(name)
        self.super = self.cls("java/lang/Object")
        self.fields = []
        self.methods = []
        self.class_annotations = []

    def _add(self, key, entry):
        if key not in self.cache:
            self.pool.append(entry)
            self.cache[key] = len(self.pool)
        return self.cache[key]

    def utf8(self, text):
        data = text.encode("utf-8")
        return self._add(("u", text), b"\x01" + struct.pack(">H", len(data)) + data)

    def cls(self, name):
        return self._add(("c", name), b"\x07" + struct.pack(">H", self.utf8(name)))

    def string(self, text):
        return self._add(("s", text), b"\x08" + struct.pack(">H", self.utf8(text)))

    def integer(self, value):
        return self._add(("i", value), b"\x03" + struct.pack(">i", value))

    def methodref(self, owner, name, desc):
        nat = self._add(("n", name, desc), b"\x0c" + struct.pack(">HH", self.utf8(name), self.utf8(desc)))
        return self._add(("m", owner, name, desc), b"\x0a" + struct.pack(">HH", self.cls(owner), nat))

    def mixin(self, target="net/minecraft/Target"):
        self.class_annotations.append(("Lorg/spongepowered/asm/mixin/Mixin;", target))
        return self

    def method(self, name, desc="()V", annotations=(), invokes=()):
        self.methods.append((name, desc, list(annotations), list(invokes)))
        return self

    def field(self, name, desc="I", const=None):
        self.fields.append((name, desc, const))
        return self

    def refs(self, *names):
        for name in names:
            self.cls(name)
        return self

    def strings(self, *texts):
        for text in texts:
            self.string(text)
        return self

    def _annotation(self, desc, class_value=None):
        out = struct.pack(">H", self.utf8(desc))
        if class_value is None:
            return out + struct.pack(">H", 0)
        out += struct.pack(">H", 1) + struct.pack(">H", self.utf8("value"))
        out += b"[" + struct.pack(">H", 1) + b"c" + struct.pack(">H", self.utf8("L%s;" % class_value))
        return out

    def _attr(self, name, payload):
        return struct.pack(">HI", self.utf8(name), len(payload)) + payload

    def to_bytes(self):
        field_bytes = b""
        for name, desc, const in self.fields:
            attrs = b""
            count = 0
            if const is not None:
                attrs += self._attr("ConstantValue", struct.pack(">H", self.integer(const)))
                count = 1
            field_bytes += struct.pack(">HHHH", 0x19, self.utf8(name), self.utf8(desc), count) + attrs
        method_bytes = b""
        for name, desc, annotations, invokes in self.methods:
            attrs = b""
            count = 0
            if annotations:
                payload = struct.pack(">H", len(annotations)) + b"".join(self._annotation(a) for a in annotations)
                attrs += self._attr("RuntimeVisibleAnnotations", payload)
                count += 1
            if invokes:
                code = b"".join(b"\xb6" + struct.pack(">H", self.methodref(*call)) for call in invokes) + b"\xb1"
                payload = struct.pack(">HHI", 2, 2, len(code)) + code + struct.pack(">HH", 0, 0)
                attrs += self._attr("Code", payload)
                count += 1
            method_bytes += struct.pack(">HHHH", 1, self.utf8(name), self.utf8(desc), count) + attrs
        class_attrs = b""
        class_count = 0
        if self.class_annotations:
            payload = struct.pack(">H", len(self.class_annotations)) + b"".join(
                self._annotation(d, t) for d, t in self.class_annotations)
            class_attrs = self._attr("RuntimeInvisibleAnnotations", payload)
            class_count = 1
        body = (struct.pack(">HHH", self.access, self.this, self.super) + struct.pack(">H", 0)
                + struct.pack(">H", len(self.fields)) + field_bytes
                + struct.pack(">H", len(self.methods)) + method_bytes
                + struct.pack(">H", class_count) + class_attrs)
        header = struct.pack(">IHHH", 0xCAFEBABE, 0, 52, len(self.pool) + 1)
        return header + b"".join(self.pool) + body


def klass(path, access=0x21):
    return ClassBuilder(path, access)


KEYS = ["nyanlex.settings.master", "nyanlex.settings.chat_log", "nyanlex.settings.chat_log.tip",
        "screen.nyanlex.ai.claude.note"]


def main_class(methods=("flipShowOriginal", "rebuildScreenAfterLanguageFlip"), scan_calls=True, with_original=True):
    builder = klass(PLAT + "NyanLexFabric").refs(PLAT + "OverlayDialogueView", PFX + "translate/AnthropicTransport")
    builder.strings("setManualTranslations")
    if with_original:
        builder.refs(PLAT + "OriginalLanguage")
    for name in methods:
        builder.method(name)
    calls = [(PLAT + "FabricTextStyle", "clearRenderMemo", "()V")] if scan_calls else []
    return builder.method("startHudScan", invokes=calls)


def overlay_view(calls=2):
    session = (PFX + "translate/OverlayDialogueSession", "run", "()V")
    builder = klass(PLAT + "OverlayDialogueView")
    return builder.method("render", invokes=[session] * calls + [(PLAT + "FabricTextStyle", "withDecor", "()V")])


def good_files(version="1.0.1"):
    """name -> bytes of a fully valid modern Fabric JAR (Minecraft 1.20.x style)."""
    files = {}

    def put(builder):
        files[builder.name + ".class"] = builder.to_bytes()

    for core in v101.REQUIRED_CORE_CLASSES:
        put(klass(PFX + core))
    put(klass(PFX + "translate/ChatComposerPanel").field("CHAT_MAX_LENGTH", "I", 256))
    put(klass(PFX + "translate/OpenAiTranslator"))
    put(klass(PFX + "translate/OpenAiTranslator$1"))
    put(main_class())
    put(klass(PLAT + "FabricTextStyle").method("withDecor").method("clearRenderMemo"))
    put(klass(PLAT + "BorrowedGlyphs"))
    put(klass(PLAT + "OriginalLanguage"))
    put(overlay_view())
    put(klass(PLAT + "TransientChatLogger"))
    put(klass(PLAT + "TranslationManagerScreen"))
    put(klass(PLAT + "TranslationConfigScreen").refs(PLAT + "TranslationManagerScreen"))
    put(klass(PLAT + "AiConfigScreen").strings("screen.nyanlex.ai.claude.note"))
    put(klass(MIX + "FontSetBorrowMixin").mixin().method("hook", annotations=[INJECT]).refs(PLAT + "BorrowedGlyphs"))
    for name in ("LanguageInstanceMixin", "LanguageReloadMixin", "LanguageLookupMixin"):
        put(klass(MIX + name).mixin().method("hook", annotations=[INJECT]).refs(PLAT + "OriginalLanguage"))
    put(klass(MIX + "TextDisplayMixin").mixin().method("hook", annotations=[INJECT]))
    put(klass(MIX + "BakedGlyphAccessor").mixin().method("up", annotations=[ACCESSOR]))
    put(klass(MIX + "GuiScoreboardMixin").mixin().method("hook", annotations=[INJECT]).refs(
        PLAT + "OverlayDialogueView", PLAT + "TransientChatLogger"))
    put(klass(MIX + "ChatComposerMixin").mixin().method(
        "fill", "(Ljava/lang/String;)V", invokes=[("net/minecraft/class_342", "method_1867", "(Ljava/lang/String;)V")]))
    registered = ["FontSetBorrowMixin", "LanguageInstanceMixin", "LanguageReloadMixin", "LanguageLookupMixin",
                  "TextDisplayMixin", "BakedGlyphAccessor", "GuiScoreboardMixin", "ChatComposerMixin"]
    files["nyanlex.mixins.json"] = json.dumps({"package": "com.dragonmeow.nyanlex.fabric.mixin", "client": registered}).encode()
    files["fabric.mod.json"] = json.dumps({
        "id": "nyanlex", "version": version,
        "entrypoints": {"client": ["com.dragonmeow.nyanlex.fabric.NyanLexFabric"]},
        "mixins": [{"config": "nyanlex.mixins.json", "environment": "client"}]}).encode()
    for lang in v101.LANGS:
        files["assets/nyanlex/lang/%s.json" % lang] = json.dumps({k: lang for k in KEYS}).encode()
    return files


def forge_files():
    """good_files() plus what Forge 47 needs: manifest MixinConfigs, refmap, pack.mcmeta."""
    files = good_files()
    config = json.loads(files["nyanlex.mixins.json"])
    config["refmap"] = "nyanlex.refmap.json"
    files["nyanlex.mixins.json"] = json.dumps(config).encode()
    files["nyanlex.refmap.json"] = b'{"mappings": {}}'
    files["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\nMixinConfigs: nyanlex.mixins.json\r\n\r\n"
    files["pack.mcmeta"] = b'{"pack": {"description": "x", "pack_format": 15}}'
    return files


def legacy_files(version="1.0.1"):
    files = {}
    legacy_mixin = PFX + "legacy/mixin/"
    composer = klass(legacy_mixin + "ChatComposerMixin").mixin().method(
        "fill", "(Ljava/lang/String;)V", invokes=[("net/minecraft/class_342", "method_1867", "(Ljava/lang/String;)V")])
    files[composer.name + ".class"] = composer.to_bytes()
    panel = klass(PFX + "translate/ChatComposerPanel").field("CHAT_MAX_LENGTH", "I", 256)
    files[panel.name + ".class"] = panel.to_bytes()
    files["nyanlex.mixins.json"] = json.dumps({"package": "com.dragonmeow.nyanlex.legacy.mixin",
                                               "client": ["ChatComposerMixin"]}).encode()
    files["fabric.mod.json"] = json.dumps({"id": "nyanlex", "version": version,
                                           "mixins": [{"config": "nyanlex.mixins.json"}]}).encode()
    return files


def write_jar(path, files):
    with ZipFile(path, "w") as jar:
        for name, data in files.items():
            jar.writestr(name, data)
    return path


def target_for(key):
    for target in v101.targets():
        if target.key == key:
            return target
    raise KeyError(key)


class VerifierTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)
        base_path = write_jar(self.dir / "baseline.jar", good_files())
        base_jar = v101.Jar(base_path)
        self.baseline = v101.Baseline("fabric/26.1.2", base_jar.core_classes(), v101.load_lang_keys(base_jar),
                                      v101.call_site_fingerprint(base_jar))
        base_jar.close()

    def run_check(self, key, files, name=None):
        target = target_for(key)
        name = name or "NyanLex-Translator-1.0.1-%s-%s.jar" % (target.label, target.minecraft)
        path = write_jar(self.dir / name, files)
        rep, _ctx = v101.evaluate_jar(target, path, self.baseline)
        return rep

    def failed_checks(self, key, files):
        return sorted({check for _s, check, _m in self.run_check(key, files).failures()})

    def mutated(self, remove=(), replace=None, **kwargs):
        files = good_files(**kwargs)
        for name in remove:
            del files[name]
        files.update(replace or {})
        return files

    # -- the baseline case ---------------------------------------------------------------
    def test_valid_jar_passes(self):
        rep = self.run_check("fabric/1.21.4", good_files())
        self.assertEqual(rep.failures(), [])
        self.assertEqual(rep.info["LanguageLookupMixin"], "yes")

    # -- one mutation per check ----------------------------------------------------------
    def test_wrong_version(self):
        self.assertIn("C01", self.failed_checks("fabric/1.21.4", good_files(version="1.0.0")))

    def test_wrong_file_name(self):
        rep = self.run_check("fabric/1.21.4", good_files(), name="NyanLex-Translator-1.0.0-Fabric-1.21.4.jar")
        self.assertIn("C01", {c for _s, c, _m in rep.failures()})

    def test_missing_core_class(self):
        failed = self.failed_checks("fabric/1.21.4", self.mutated(remove=[PFX + "translate/DecorStyleMatcher.class"]))
        self.assertIn("C03", failed)
        self.assertIn("C02", failed)

    def test_extra_core_class_differs_from_baseline(self):
        files = good_files()
        files[PFX + "service/Stray.class"] = klass(PFX + "service/Stray").to_bytes()
        self.assertEqual(self.failed_checks("fabric/1.21.4", files), ["C02"])

    def test_known_core_diff_is_allowed_but_reported(self):
        files = good_files()
        files[PFX + "translate/OpenAiTranslator$2.class"] = klass(PFX + "translate/OpenAiTranslator$2").to_bytes()
        del files[PFX + "translate/OpenAiTranslator$1.class"]
        rep = self.run_check("fabric/1.17.1", files)
        self.assertNotIn("C02", {c for _s, c, _m in rep.failures()})
        self.assertTrue(any(s == "NOTE" and c == "C02" for s, c, _m in rep.items))
        # the same difference on a target without the exception is a failure
        self.assertIn("C02", self.failed_checks("fabric/1.21.4", files))

    def test_known_switch_map_holders_are_allowed_on_the_jdk17_target_only(self):
        files = good_files()
        name = PFX + "translate/ChatSegmenter$1"
        files[name + ".class"] = klass(name, 0x1020).field("$SwitchMap$a$b", "[I").to_bytes()
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertNotIn("C02", {c for _s, c, _m in rep.failures()})
        self.assertTrue(any(s == "NOTE" and c == "C02" and "ChatSegmenter$1" in m for s, c, m in rep.items))
        self.assertIn("C02", self.failed_checks("fabric/1.21.4", files))

    def test_switch_map_exception_needs_a_real_switch_map_holder(self):
        files = good_files()
        name = PFX + "translate/ChatSegmenter$1"
        files[name + ".class"] = klass(name).field("helper", "I").to_bytes()  # not synthetic, no $SwitchMap$ field
        self.assertIn("C02", self.failed_checks("neoforge/1.20.1", files))

    def test_other_extra_class_on_the_jdk17_target_still_fails(self):
        files = good_files()
        name = PFX + "translate/Surprise$1"
        files[name + ".class"] = klass(name, 0x1020).field("$SwitchMap$a$b", "[I").to_bytes()
        self.assertIn("C02", self.failed_checks("neoforge/1.20.1", files))

    def test_missing_platform_class(self):
        self.assertIn("C04", self.failed_checks("fabric/1.21.4", self.mutated(remove=[PLAT + "BorrowedGlyphs.class"])))

    def test_missing_manager_screen(self):
        files = self.mutated(remove=[PLAT + "TranslationManagerScreen.class"])
        self.assertIn("C04", self.failed_checks("fabric/1.21.4", files))

    def test_mixin_registered_but_class_missing(self):
        failed = self.failed_checks("fabric/1.21.4", self.mutated(remove=[MIX + "FontSetBorrowMixin.class"]))
        self.assertIn("C05", failed)

    def test_mixin_class_not_registered(self):
        config = {"package": "com.dragonmeow.nyanlex.fabric.mixin",
                  "client": ["LanguageInstanceMixin", "LanguageReloadMixin", "LanguageLookupMixin", "TextDisplayMixin",
                             "BakedGlyphAccessor", "GuiScoreboardMixin", "ChatComposerMixin"]}
        files = self.mutated(replace={"nyanlex.mixins.json": json.dumps(config).encode()})
        rep = self.run_check("fabric/1.21.4", files)
        messages = " ".join(m for s, c, m in rep.failures() if c == "C05")
        self.assertIn("FontSetBorrowMixin is not registered", messages)
        self.assertIn("no config registers", messages)

    def test_mixin_registered_in_two_configs(self):
        files = good_files()
        compat = {"package": "com.dragonmeow.nyanlex.fabric.mixin", "client": ["BakedGlyphAccessor"]}
        files["nyanlex.compat.mixins.json"] = json.dumps(compat).encode()
        fabric = json.loads(files["fabric.mod.json"])
        fabric["mixins"].append({"config": "nyanlex.compat.mixins.json"})
        files["fabric.mod.json"] = json.dumps(fabric).encode()
        rep = self.run_check("fabric/1.21.4", files)
        messages = " ".join(m for _s, c, m in rep.failures() if c == "C05")
        self.assertIn("BakedGlyphAccessor is registered 2 times", messages)
        self.assertIn("nyanlex.compat.mixins.json", messages)

    def test_mixin_listed_twice_in_one_config(self):
        files = good_files()
        config = json.loads(files["nyanlex.mixins.json"])
        config["client"].append("FontSetBorrowMixin")
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        self.assertIn("C05", self.failed_checks("fabric/1.21.4", files))

    def test_duplicate_registration_is_checked_on_legacy_targets_too(self):
        files = legacy_files()
        config = json.loads(files["nyanlex.mixins.json"])
        config["client"].append("ChatComposerMixin")
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        self.assertIn("C05", self.failed_checks("fabric/1.14.4", files))

    # -- Forge 47 (NeoForge 1.20.1) mixin loading ----------------------------------------
    def test_forge_target_valid_jar_passes_c15(self):
        rep = self.run_check("neoforge/1.20.1", forge_files())
        self.assertEqual(rep.failures(), [])
        self.assertIn("C15", rep.checked)

    def test_c15_is_not_applied_to_other_neoforge_versions(self):
        rep = self.run_check("neoforge/1.21.1", good_files())
        self.assertEqual(rep.failures(), [])
        self.assertNotIn("C15", rep.checked)

    def test_forge_target_without_manifest_attribute(self):
        files = forge_files()
        files["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\n\r\n"
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertIn("MixinConfigs", " ".join(m for _s, c, m in rep.failures() if c == "C15"))

    def test_forge_target_manifest_continuation_lines_are_joined(self):
        files = forge_files()
        files["nyanlex.compat.mixins.json"] = json.dumps(
            {"package": "com.dragonmeow.nyanlex.fabric.mixin", "client": [], "refmap": "nyanlex.refmap.json"}).encode()
        fabric = json.loads(files["fabric.mod.json"])
        fabric["mixins"].append({"config": "nyanlex.compat.mixins.json"})
        files["fabric.mod.json"] = json.dumps(fabric).encode()
        files["META-INF/MANIFEST.MF"] = (b"Manifest-Version: 1.0\r\nMixinConfigs: nyanlex.mixins.json,nyanlex.com\r\n"
                                         b" pat.mixins.json\r\n\r\n")
        self.assertEqual(self.run_check("neoforge/1.20.1", files).failures(), [])

    def test_forge_target_config_not_listed_in_manifest(self):
        files = forge_files()
        files["nyanlex.compat.mixins.json"] = json.dumps(
            {"package": "com.dragonmeow.nyanlex.fabric.mixin", "client": [], "refmap": "nyanlex.refmap.json"}).encode()
        fabric = json.loads(files["fabric.mod.json"])
        fabric["mixins"].append({"config": "nyanlex.compat.mixins.json"})
        files["fabric.mod.json"] = json.dumps(fabric).encode()
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertIn("not listed in MixinConfigs", " ".join(m for _s, c, m in rep.failures() if c == "C15"))

    def test_forge_target_manifest_lists_a_missing_file(self):
        files = forge_files()
        files["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\nMixinConfigs: nyanlex.mixins.json,gone.mixins.json\r\n\r\n"
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertIn("gone.mixins.json", " ".join(m for _s, c, m in rep.failures() if c == "C15"))

    def test_forge_target_config_without_refmap_field(self):
        files = forge_files()
        config = json.loads(files["nyanlex.mixins.json"])
        del config["refmap"]
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertIn("no refmap field", " ".join(m for _s, c, m in rep.failures() if c == "C15"))

    def test_forge_target_refmap_file_missing(self):
        files = forge_files()
        del files["nyanlex.refmap.json"]
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertIn("not in the JAR", " ".join(m for _s, c, m in rep.failures() if c == "C15"))

    def test_forge_target_without_pack_mcmeta(self):
        files = forge_files()
        del files["pack.mcmeta"]
        rep = self.run_check("neoforge/1.20.1", files)
        self.assertIn("pack.mcmeta", " ".join(m for _s, c, m in rep.failures() if c == "C15"))

    def test_published_1_0_0_forge_jar_fails_c15(self):
        """The shipped 1.0.0 NeoForge 1.20.1 JAR has none of the three Forge 47 requirements."""
        candidates = sorted((REPO_ROOT / "mods-jar" / "1.0.0-expanded" / "neoforge" / "1.20.1").glob("*.jar"))
        if not candidates:
            self.skipTest("published 1.0.0 NeoForge 1.20.1 JAR is not on this machine")
        target = target_for("neoforge/1.20.1")
        rep, _ctx = v101.evaluate_jar(target, candidates[0], self.baseline)
        messages = " ".join(m for _s, c, m in rep.failures() if c == "C15")
        self.assertIn("MixinConfigs", messages)
        self.assertIn("no refmap field", messages)
        self.assertIn("pack.mcmeta", messages)

    def test_dangling_refmap_is_only_a_note_outside_the_forge_target(self):
        files = good_files()
        config = json.loads(files["nyanlex.mixins.json"])
        config["refmap"] = "nyanlex.refmap.json"
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        rep = self.run_check("fabric/1.21.4", files)
        self.assertEqual(rep.failures(), [])
        self.assertTrue(any(s == "NOTE" and c == "C05" and "refmap" in m for s, c, m in rep.items))

    def test_unregistered_mixin_config(self):
        files = good_files()
        files["nyanlex.extra.mixins.json"] = b"{}"
        self.assertIn("C05", self.failed_checks("fabric/1.21.4", files))

    def test_text_display_required_from_1_19_4(self):
        files = self.mutated(remove=[MIX + "TextDisplayMixin.class"])
        config = json.loads(files["nyanlex.mixins.json"])
        config["client"].remove("TextDisplayMixin")
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        self.assertIn("C05", self.failed_checks("fabric/1.19.4", files))
        self.assertNotIn("C05", self.failed_checks("fabric/1.19.3", files))

    def test_text_display_forbidden_before_1_19_4(self):
        self.assertIn("C05", self.failed_checks("fabric/1.19.3", good_files()))

    def test_glyph_accessor_required_except_known_targets(self):
        files = self.mutated(remove=[MIX + "BakedGlyphAccessor.class"])
        config = json.loads(files["nyanlex.mixins.json"])
        config["client"].remove("BakedGlyphAccessor")
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        self.assertIn("C05", self.failed_checks("fabric/1.21.4", files))
        rep = self.run_check("fabric/1.18.2", files)
        self.assertNotIn("C05", {c for _s, c, _m in rep.failures() if "accessor" in _m})
        self.assertTrue(any(s == "NOTE" and "KNOWN_EXCEPTION" in m for s, _c, m in rep.items))

    def test_lookup_mixin_state_is_reported(self):
        files = self.mutated(remove=[MIX + "LanguageLookupMixin.class"])
        config = json.loads(files["nyanlex.mixins.json"])
        config["client"].remove("LanguageLookupMixin")
        files["nyanlex.mixins.json"] = json.dumps(config).encode()
        rep = self.run_check("fabric/1.21.4", files)
        self.assertEqual(rep.failures(), [])
        self.assertEqual(rep.info["LanguageLookupMixin"], "no")

    def test_mixin_without_hook_method(self):
        files = self.mutated(replace={MIX + "LanguageReloadMixin.class":
                                      klass(MIX + "LanguageReloadMixin").mixin().refs(PLAT + "OriginalLanguage").to_bytes()})
        self.assertIn("C06", self.failed_checks("fabric/1.21.4", files))

    def test_hud_call_site_missing(self):
        files = self.mutated(replace={MIX + "GuiScoreboardMixin.class": klass(MIX + "GuiScoreboardMixin").mixin()
                                      .method("hook", annotations=[INJECT]).to_bytes()})
        self.assertIn("C07", self.failed_checks("fabric/1.21.4", files))

    def test_entry_point_reference_alone_is_not_wiring(self):
        # NyanLexFabric references OverlayDialogueView in the valid JAR; without the Mixin call it still fails.
        files = self.mutated(replace={MIX + "GuiScoreboardMixin.class": klass(MIX + "GuiScoreboardMixin").mixin()
                                      .method("hook", annotations=[INJECT]).refs(PLAT + "TransientChatLogger").to_bytes()})
        rep = self.run_check("fabric/1.21.4", files)
        self.assertTrue(any("entry point does reference it" in m for _s, _c, m in rep.failures()))

    def test_language_mixin_not_calling_platform(self):
        files = self.mutated(replace={MIX + "LanguageInstanceMixin.class":
                                      klass(MIX + "LanguageInstanceMixin").mixin().method("hook", annotations=[INJECT]).to_bytes()})
        self.assertIn("C07", self.failed_checks("fabric/1.21.4", files))

    def test_main_class_without_original_language(self):
        files = self.mutated(replace={PLAT + "NyanLexFabric.class": klass(PLAT + "NyanLexFabric").refs(
            PLAT + "OverlayDialogueView", PFX + "translate/AnthropicTransport").strings("setManualTranslations").to_bytes()})
        self.assertIn("C07", self.failed_checks("fabric/1.21.4", files))

    def test_manager_screen_never_opened(self):
        files = self.mutated(replace={PLAT + "TranslationConfigScreen.class": klass(PLAT + "TranslationConfigScreen").to_bytes()})
        self.assertIn("C07", self.failed_checks("fabric/1.21.4", files))

    def test_claude_transport_not_referenced(self):
        files = self.mutated(replace={PLAT + "NyanLexFabric.class": klass(PLAT + "NyanLexFabric").refs(
            PLAT + "OriginalLanguage", PLAT + "OverlayDialogueView").strings("setManualTranslations").to_bytes()})
        self.assertIn("C08", self.failed_checks("fabric/1.21.4", files))

    def test_main_class_missing_feature_methods(self):
        files = self.mutated(replace={PLAT + "NyanLexFabric.class": main_class(methods=("flipShowOriginal",)).to_bytes()})
        self.assertIn("C14", self.failed_checks("fabric/1.21.4", files))

    def test_hud_scan_without_render_memo_reset(self):
        files = self.mutated(replace={PLAT + "NyanLexFabric.class": main_class(scan_calls=False).to_bytes()})
        self.assertIn("C14", self.failed_checks("fabric/1.21.4", files))

    def test_text_style_without_decor(self):
        files = self.mutated(replace={PLAT + "FabricTextStyle.class": klass(PLAT + "FabricTextStyle").method("clearRenderMemo").to_bytes()})
        self.assertIn("C14", self.failed_checks("fabric/1.21.4", files))

    def test_fewer_call_sites_than_baseline(self):
        files = self.mutated(replace={PLAT + "OverlayDialogueView.class": overlay_view(calls=1).to_bytes()})
        rep = self.run_check("fabric/1.21.4", files)
        messages = " ".join(m for _s, c, m in rep.failures() if c == "C14")
        self.assertIn("core:OverlayDialogueSession: 1 < 2", messages)

    def test_more_call_sites_than_baseline_is_only_a_note(self):
        files = self.mutated(replace={PLAT + "OverlayDialogueView.class": overlay_view(calls=3).to_bytes()})
        rep = self.run_check("fabric/1.21.4", files)
        self.assertEqual(rep.failures(), [])
        self.assertTrue(any(s == "NOTE" and c == "C14" for s, c, _m in rep.items))

    def test_claude_note_string_missing(self):
        files = self.mutated(replace={PLAT + "AiConfigScreen.class": klass(PLAT + "AiConfigScreen").to_bytes()})
        self.assertIn("C08", self.failed_checks("fabric/1.21.4", files))

    def test_lang_key_missing_in_one_language(self):
        keys = {k: "x" for k in KEYS if k != "nyanlex.settings.chat_log.tip"}
        files = self.mutated(replace={"assets/nyanlex/lang/zh_cn.json": json.dumps(keys).encode()})
        self.assertIn("C09", self.failed_checks("fabric/1.21.4", files))

    def test_lang_extra_key_differs_from_baseline(self):
        keys = {k: "x" for k in KEYS + ["nyanlex.extra"]}
        files = self.mutated(replace={"assets/nyanlex/lang/%s.json" % lang: json.dumps(keys).encode() for lang in v101.LANGS})
        self.assertIn("C09", self.failed_checks("fabric/1.21.4", files))

    def test_capture_classes_rejected_outside_allowed_targets(self):
        files = good_files()
        files[PLAT + "ComponentCapture.class"] = klass(PLAT + "ComponentCapture").to_bytes()
        self.assertIn("C10", self.failed_checks("fabric/1.21.4", files))
        self.assertNotIn("C10", self.failed_checks("fabric/26.1.2", files))

    def test_capture_reference_without_class(self):
        files = self.mutated(replace={PLAT + "BorrowedGlyphs.class": klass(PLAT + "BorrowedGlyphs").refs(PLAT + "JsonlCaptureSink").to_bytes()})
        self.assertIn("C10", self.failed_checks("fabric/1.21.4", files))
        self.assertIn("C10", self.failed_checks("fabric/26.1.2", files))

    def test_composer_slash_skip_still_present(self):
        files = self.mutated(replace={MIX + "ChatComposerMixin.class": klass(MIX + "ChatComposerMixin").mixin().method(
            "fill", "(Ljava/lang/String;)V", invokes=[("net/minecraft/class_342", "method_1867", "(Ljava/lang/String;)V")]).strings("/").to_bytes()})
        self.assertIn("C11", self.failed_checks("fabric/1.21.4", files))

    def test_composer_fill_replaces_text(self):
        files = self.mutated(replace={MIX + "ChatComposerMixin.class": klass(MIX + "ChatComposerMixin").mixin().method(
            "fill", "(Ljava/lang/String;)V", invokes=[("net/minecraft/class_342", "method_1852", "(Ljava/lang/String;)V")]).to_bytes()})
        self.assertIn("C11", self.failed_checks("fabric/1.21.4", files))

    def test_composer_fill_unknown_mapping_is_unverified_not_pass_evidence(self):
        files = self.mutated(replace={MIX + "ChatComposerMixin.class": klass(MIX + "ChatComposerMixin").mixin().method(
            "fill", "(Ljava/lang/String;)V", invokes=[("net/minecraft/class_342", "method_9999", "(Ljava/lang/String;)V")]).to_bytes()})
        rep = self.run_check("fabric/1.21.4", files)
        self.assertEqual(rep.failures(), [])
        self.assertTrue(any(s == "UNVERIFIED" and c == "C11" for s, c, _m in rep.items))

    def test_composer_panel_length_guard_missing(self):
        files = self.mutated(replace={PFX + "translate/ChatComposerPanel.class": klass(PFX + "translate/ChatComposerPanel").field("lastChat", "Ljava/lang/String;").to_bytes()})
        rep = self.run_check("fabric/1.21.4", files)
        messages = " ".join(m for _s, c, m in rep.failures() if c == "C11")
        self.assertIn("CHAT_MAX_LENGTH", messages)
        self.assertIn("lastChat", messages)

    def test_dangling_class_reference(self):
        files = self.mutated(replace={PLAT + "BorrowedGlyphs.class": klass(PLAT + "BorrowedGlyphs").refs(PFX + "translate/NoSuchClass").to_bytes()})
        self.assertIn("C13", self.failed_checks("fabric/1.21.4", files))

    def test_unparseable_class_file(self):
        files = self.mutated(replace={PLAT + "BorrowedGlyphs.class": b"\xca\xfe\xba\xbe\x00\x00"})
        failed = self.failed_checks("fabric/1.21.4", files)
        self.assertIn("C13", failed)

    # -- legacy engine targets -----------------------------------------------------------
    def test_legacy_valid_jar_passes(self):
        rep = self.run_check("fabric/1.14.4", legacy_files())
        self.assertEqual(rep.failures(), [])

    def test_legacy_rejects_modern_feature_classes(self):
        for name in ("config/TranslationManagerPanel", "fabric/OverlayDialogueView", "fabric/mixin/TextDisplayMixin"):
            files = legacy_files()
            files[PFX + name + ".class"] = klass(PFX + name).to_bytes()
            self.assertIn("C12", self.failed_checks("fabric/1.14.4", files), name)

    def test_legacy_old_composer_fails(self):
        files = legacy_files()
        files[PFX + "legacy/mixin/ChatComposerMixin.class"] = klass(PFX + "legacy/mixin/ChatComposerMixin").mixin().method(
            "fill", "(Ljava/lang/String;)V", invokes=[("net/minecraft/class_342", "method_1852", "(Ljava/lang/String;)V")]).strings("/").to_bytes()
        self.assertIn("C11", self.failed_checks("fabric/1.14.4", files))

    # -- robustness ----------------------------------------------------------------------
    def test_corrupt_jar_is_a_failure(self):
        target = target_for("fabric/1.21.4")
        path = self.dir / "broken.jar"
        path.write_bytes(b"PK\x03\x04 not really a zip")
        rep, ctx = v101.evaluate_jar(target, path, self.baseline)
        self.assertIsNone(ctx)
        self.assertEqual([c for _s, c, _m in rep.failures()], ["C00"])

    def test_missing_jar_is_reported_and_exit_code_nonzero(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = v101.main(["--targets", "fabric/1.21.4", "--jar", "fabric/1.21.4=" + str(self.dir / "absent.jar")])
        self.assertEqual(code, 1)
        self.assertIn("MISSING_JAR", out.getvalue())
        self.assertIn("missing=1", out.getvalue())

    def test_cli_pass_summary_and_json(self):
        path = write_jar(self.dir / "NyanLex-Translator-1.0.1-Fabric-1.21.4.jar", good_files())
        report = self.dir / "out.json"
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            v101.main(["--targets", "fabric/1.21.4", "--jar", "fabric/1.21.4=" + str(path),
                       "--json", str(report), "--baseline-target", "fabric/26.1.2"])
        text = out.getvalue()
        # the real 26.1.2 JAR (if built) may or may not be a usable baseline; the summary line must exist either way
        self.assertIn("VERIFY_101 passed=", text)
        self.assertIn("expected=62", text)
        data = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual(data["results"][0]["target"], "fabric/1.21.4")

    def test_matrix_assumptions(self):
        keys = {t.key for t in v101.targets()}
        self.assertEqual(len(keys), v101.EXPECTED_TARGET_COUNT)
        self.assertTrue(v101.LEGACY_KEYS <= keys)
        self.assertEqual(v101.LEGACY_KEYS, {t.key for t in v101.targets() if t.java == 8})
        self.assertTrue(v101.CAPTURE_ALLOWED_KEYS <= keys)
        self.assertTrue(v101.ACCESSOR_FREE_KEYS <= keys)
        self.assertTrue(set(v101.KNOWN_CORE_DIFFS) <= keys)
        self.assertEqual(v101.FORGE_MANIFEST_MIXIN_KEYS, {"neoforge/1.20.1"})


if __name__ == "__main__":
    unittest.main(verbosity=2)
