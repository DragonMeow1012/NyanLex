"""Check the feature entry points in all 18 distributable JARs.

Translation sharing, native file picker, screen scan, request consent and
do-not-translate terms are checked against the current modern/legacy settings UI.
"""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import json
import re

VERSION = "1.0.0"
ROOT = Path(__file__).resolve().parent.parent
TARGETS = ["forge1122", "forge1132", "fabric1144", "fabric1152", "fabric1165",
           "fabric1171", "fabric1182", "fabric1194", "fabric120", ".", "fabric12111",
           "fabric2612", "fabric26", "fabric263", "neoforge120", "neoforge", "neoforge26", "neoforge263"]
LEGACY_TARGETS = TARGETS[:5]
PREFIX = "com/dragonmeow/nyanlex/"
REQUEST_KEYS = [
    "screen.nyanlex.requests.title",
    "screen.nyanlex.requests.terms",
    "screen.nyanlex.requests.terms.hint",
]
MODERN_SETTINGS_KEYS = ["nyanlex.settings.master", "nyanlex.settings.master.tip",
                        "nyanlex.settings.export", "nyanlex.settings.import",
                        "nyanlex.settings.chat_delivery", "nyanlex.settings.state.ordered",
                        "nyanlex.settings.state.ready_first"]
LEGACY_SETTINGS_KEYS = ["config.nyanlex.online", "config.nyanlex.online.desc",
                        "config.nyanlex.requests.open", "config.nyanlex.translations.export",
                        "config.nyanlex.translations.import", "config.nyanlex.chat_delivery.short",
                        "config.nyanlex.chat_delivery.ordered", "config.nyanlex.chat_delivery.ready_first"]
# The requests screen class name is chosen per tree, so find it by its title key.
REQUEST_UI_STRING = b"screen.nyanlex.requests.title"
CONFIG_FIELDS = [b"translationRequestsEnabled", b"doNotTranslateTerms"]
# Modern core: translate/<name>.class
MODERN_REQUEST_CLASSES = ["DoNotTranslateMatcher", "RequestGate", "RequestsPausedException"]
# Legacy core (Java 8) keeps its switch/term code inside the eight synced core files.
LEGACY_REQUEST_CLASSES = ["LegacyTranslator$RequestsPausedException", "LegacyTemplateText$TermMatcher"]
LEGACY_REQUEST_MEMBERS = {
    "LegacyConfig": CONFIG_FIELDS + [b"normalizeDoNotTranslateTerms"],
    "LegacyTranslator": [b"checkRequestsOpen", b"discardPausedRequests"],
    "LegacyChatRequestProfile": [b"differsOnlyInRequestSwitch"],
}


def read_entry(jar, name, target):
    try:
        return jar.read(name)
    except KeyError:
        raise AssertionError((target, "missing JAR entry", name)) from None


def read_class(jar, name, target, legacy):
    bytecode = read_entry(jar, name, target)
    assert bytecode[:4] == b"\xca\xfe\xba\xbe", (target, name)
    if legacy:
        assert int.from_bytes(bytecode[6:8], "big") == 52, (target, name)
    return bytecode


def key_values(text, extension, key):
    """Every value stored under exactly `key` (a longer key such as key + ".hint" never counts).

    Minecraft 1.12 splits each .lang line at the first '=' without trimming, so the key
    must start the line and touch the '=' exactly as written.
    """
    if extension == "lang":
        pattern = re.compile(r"^" + re.escape(key) + r"=(.*?)\r?$", re.M)
        return [match.group(1) for match in pattern.finditer(text)]
    pattern = re.compile(r'"' + re.escape(key) + r'"\s*:\s*"((?:[^"\\]|\\.)*)"')
    return [json.loads('"' + match.group(1) + '"') for match in pattern.finditer(text)]


def check_jar(target, path):
    legacy = target in LEGACY_TARGETS
    forge = target.startswith("forge")
    with ZipFile(path) as jar:
        assert jar.testzip() is None, path
        for name in ["TranslationFile", "TranslationFileDialog", "TranslationFileDialog$Picker", "ScreenTranslationCapture"]:
            bytecode = read_class(jar, PREFIX + "translate/" + name + ".class", target, legacy)
            if name == "TranslationFile":
                assert b"writeParts" in bytecode, target
            if name == "TranslationFileDialog":
                assert b"importFiles" in bytecode, target
            if name == "TranslationFileDialog$Picker":
                assert b"setMultipleMode" in bytecode, target
        service = ("forgelegacy/LegacyTranslator" if forge else
                   "legacy/LegacyTranslator" if legacy else "service/TranslationService")
        bytecode = read_entry(jar, PREFIX + service + ".class", target)
        for method in [b"retranslateScreen", b"exportTranslations", b"importTranslations"]:
            assert method in bytecode, (target, method)

        # 1.0.7 request switch + do-not-translate terms in the core.
        if legacy:
            package = PREFIX + ("forgelegacy/" if forge else "legacy/")
            for name in LEGACY_REQUEST_CLASSES:
                read_class(jar, package + name + ".class", target, True)
            for name, members in LEGACY_REQUEST_MEMBERS.items():
                bytecode = read_class(jar, package + name + ".class", target, True)
                for member in members:
                    assert member in bytecode, (target, name, member)
        else:
            for name in MODERN_REQUEST_CLASSES:
                read_class(jar, PREFIX + "translate/" + name + ".class", target, False)
            bytecode = read_class(jar, PREFIX + "config/TranslatorConfig.class", target, False)
            for member in CONFIG_FIELDS:
                assert member in bytecode, (target, "TranslatorConfig", member)

        languages = ["en_us", "zh_tw"] if forge else ["en_us", "zh_tw", "zh_cn", "zh_hk"]
        extension = "lang" if target == "forge1122" else "json"
        for language in languages:
            text = read_entry(jar, "assets/nyanlex/lang/" + language + "." + extension, target).decode("utf-8")
            settings_keys = LEGACY_SETTINGS_KEYS if legacy else MODERN_SETTINGS_KEYS
            for key in REQUEST_KEYS + settings_keys + ["key.nyanlex.screenscan"]:
                values = key_values(text, extension, key)
                assert len(values) == 1, (target, language, key, len(values))
                assert values[0].strip(), (target, language, key, "empty value")

        # 1.0.7 settings UI: some class of this JAR opens the requests/terms screen.
        request_ui = sorted(name for name in jar.namelist()
                            if name.startswith(PREFIX) and name.endswith(".class")
                            and REQUEST_UI_STRING in jar.read(name))
        assert request_ui, (target, "no class references " + REQUEST_UI_STRING.decode())

        ui_class = ("forgelegacy/LegacyUiModel" if forge else "legacy/LegacyUiModel") if legacy else "config/SettingsCatalog"
        ui = read_class(jar, PREFIX + ui_class + ".class", target, legacy)
        for member in [b"translationRequestsEnabled", b"deliverChatTranslationsInOrder"]:
            assert member in ui, (target, ui_class, "unwired setting", member)
        for member in ([b"A_EXPORT", b"A_IMPORT", b"A_ONLINE"] if legacy else [b"EXPORT", b"IMPORT", b"master", b"chat_delivery"]):
            assert member in ui, (target, ui_class, member)

        if target == "forge1122":
            assert b"FMLCorePlugin:" in read_entry(jar, "META-INF/MANIFEST.MF", target), target
            assert PREFIX + "forgelegacy/ScreenTextTransformer.class" in jar.namelist()
        if target == "forge1132":
            assert "nyanlex-screen-text.js" in jar.namelist()
            assert "META-INF/coremods.json" in jar.namelist()
    return {"requests_ui": [name[len(PREFIX):] for name in request_ui]}


def main():
    results = []
    for target in TARGETS:
        jars = [p for p in (ROOT / target / "build/libs").glob("nyanlex-" + VERSION + "-*.jar")
                if not p.name.endswith(("-sources.jar", "-dev.jar", "-javadoc.jar"))]
        assert len(jars) == 1, (target, jars)
        path = jars[0]
        found = check_jar(target, path)
        results.append({"target": target, "jar": str(path.relative_to(ROOT)),
                        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "requests_ui": found["requests_ui"]})
    output = ROOT / "build/translation-feature-artifacts.json"
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print("TRANSLATION_FEATURE_ARTIFACTS_OK version={} targets={} sharing={} screen_scan={} "
          "legacy_java8={} request_switch={} do_not_translate={} requests_ui={}".format(
              VERSION, len(results), len(results), len(results), len(LEGACY_TARGETS),
              len(results), len(results), len(results)))


if __name__ == "__main__":
    main()
