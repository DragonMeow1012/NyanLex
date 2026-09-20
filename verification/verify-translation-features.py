"""Check the new feature entry points in all 18 distributable JARs."""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import json

ROOT = Path(__file__).resolve().parent.parent
TARGETS = ["forge1122", "forge1132", "fabric1144", "fabric1152", "fabric1165",
           "fabric1171", "fabric1182", "fabric1194", "fabric120", ".", "fabric12111",
           "fabric2612", "fabric26", "fabric263", "neoforge120", "neoforge", "neoforge26", "neoforge263"]
PREFIX = "com/dragonmeow/mctranslator/"


def main():
    results = []
    for target in TARGETS:
        jars = [p for p in (ROOT / target / "build/libs").glob("mctranslator-1.0.6-*.jar")
                if not p.name.endswith(("-sources.jar", "-dev.jar", "-javadoc.jar"))]
        assert len(jars) == 1, (target, jars)
        path = jars[0]
        legacy = target in TARGETS[:5]
        with ZipFile(path) as jar:
            assert jar.testzip() is None, path
            for name in ["TranslationFile", "TranslationFileDialog", "TranslationFileDialog$Picker", "ScreenTranslationCapture"]:
                bytecode = jar.read(PREFIX + "translate/" + name + ".class")
                assert bytecode[:4] == b"\xca\xfe\xba\xbe"
                if legacy:
                    assert int.from_bytes(bytecode[6:8], "big") == 52, (target, name)
                if name == "TranslationFile":
                    assert b"writeParts" in bytecode, target
                if name == "TranslationFileDialog":
                    assert b"importFiles" in bytecode, target
                if name == "TranslationFileDialog$Picker":
                    assert b"setMultipleMode" in bytecode, target
            service = ("forgelegacy/LegacyTranslator" if target.startswith("forge") else
                       "legacy/LegacyTranslator" if legacy else "service/TranslationService")
            bytecode = jar.read(PREFIX + service + ".class")
            for method in [b"retranslateScreen", b"exportTranslations", b"importTranslations"]:
                assert method in bytecode, (target, method)
            languages = ["en_us", "zh_tw"] if target.startswith("forge") else ["en_us", "zh_tw", "zh_cn", "zh_hk"]
            for language in languages:
                extension = "lang" if target == "forge1122" else "json"
                text = jar.read("assets/mctranslator/lang/" + language + "." + extension).decode("utf-8")
                for key in ["config.mctranslator.translations.export", "config.mctranslator.translations.import", "key.mctranslator.screenscan"]:
                    assert text.count(key) == 1, (target, language, key)
            if target == "forge1122":
                assert b"FMLCorePlugin:" in jar.read("META-INF/MANIFEST.MF")
                assert PREFIX + "forgelegacy/ScreenTextTransformer.class" in jar.namelist()
            if target == "forge1132":
                assert "mctranslator-screen-text.js" in jar.namelist()
                assert "META-INF/coremods.json" in jar.namelist()
        results.append({"target": target, "jar": str(path.relative_to(ROOT)),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    output = ROOT / "build/translation-feature-artifacts.json"
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print("TRANSLATION_FEATURE_ARTIFACTS_OK targets=18 sharing=18 screen_scan=18 legacy_java8=5")


if __name__ == "__main__":
    main()
