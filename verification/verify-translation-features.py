"""Check the feature entry points in every declared distributable JAR.

Translation sharing, native file picker, screen scan, request consent,
do-not-translate terms and the optional compatibility hooks are checked against
the current modern/legacy settings UI.
"""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import json
import re
import argparse

from release_matrix import BASE_TARGETS, targets

VERSION = "1.0.0"
ROOT = Path(__file__).resolve().parent.parent
LEGACY_TARGETS = [target.project for target in BASE_TARGETS if target.java == 8]
PREFIX = "com/dragonmeow/nyanlex/"
INTERMEDIARY_BINARY_NAMES = {
    "net.minecraft.client.gui.screens.inventory.BookViewScreen": ["net.minecraft.class_3872"],
    "net.minecraft.world.level.block.entity.SignBlockEntity": ["net.minecraft.class_2625"],
    "net.minecraft.world.level.block.entity.SignText": ["net.minecraft.class_8242"],
}
REQUEST_KEYS = [
    "screen.nyanlex.requests.title",
    "screen.nyanlex.requests.terms",
    "screen.nyanlex.requests.terms.hint",
]
MODERN_SETTINGS_KEYS = ["nyanlex.settings.master", "nyanlex.settings.master.tip",
                        "nyanlex.settings.export", "nyanlex.settings.import", "nyanlex.settings.chat_delivery", "nyanlex.settings.state.chat_ordered", "nyanlex.settings.state.chat_ready", "nyanlex.settings.state.chat_original"]
LEGACY_SETTINGS_KEYS = ["config.nyanlex.online", "config.nyanlex.online.desc",
                        "config.nyanlex.requests.open", "config.nyanlex.translations.export",
                        "config.nyanlex.translations.import", "config.nyanlex.chat_delivery.short", "nyanlex.settings.state.chat_ordered", "nyanlex.settings.state.chat_ready", "nyanlex.settings.state.chat_original"]
# The requests screen class name is chosen per tree, so find it by its title key.
REQUEST_UI_STRING = b"screen.nyanlex.requests.title"
CONFIG_FIELDS = [b"translationRequestsEnabled", b"doNotTranslateTerms",
                 b"chatDeliveryMode", b"chatComposerEnabled", b"chatComposerLanguage", b"chatComposerX", b"chatComposerY"]
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


def assert_java_level(jar, target, java):
    """Check every class, not only entrypoints or legacy feature markers."""
    for name in jar.namelist():
        if name.endswith(".class"):
            data = read_class(jar, name, target, False)
            major = int.from_bytes(data[6:8], "big")
            assert major <= java + 44, (target, "Java bytecode exceeds declared target", name, major, java)


def assert_composer_boundary(jar, name, target, legacy):
    """Keep Mixin-generated inner classes out of the chat-screen integration.

    NeoForge 26.1.2 frame computation cannot resolve the generated
    ChatScreen$Anonymous name. The screen now directly implements Host, and
    uses the existing external platform canvas instead of anonymous adapters.
    """
    bytecode = read_class(jar, name, target, legacy)
    generated = [entry for entry in jar.namelist()
                 if entry.startswith(name[:-6] + "$") and entry.endswith(".class")]
    assert not generated, (target, "unsafe composer Mixin inner classes", generated)
    assert (PREFIX + "translate/ChatComposerPanel$Host").encode() in bytecode, (
        target, "composer screen must implement the existing Host boundary")


def registered_client_mixin(jar, configs, simple_name, target, legacy):
    matches = [config for config in configs if simple_name in config.get("client", [])]
    assert len(matches) == 1, (target, simple_name + " mixin registration", len(matches))
    path = matches[0]["package"].replace(".", "/") + "/" + simple_name + ".class"
    return read_class(jar, path, target, legacy)


def contains_binary_name(bytecode, dotted_name):
    candidates = [dotted_name]
    candidates.extend(INTERMEDIARY_BINARY_NAMES.get(dotted_name, []))
    return any((encoded := candidate.encode()) in bytecode
               or encoded.replace(b".", b"/") in bytecode
               for candidate in candidates)


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
        timing = read_class(jar, PREFIX + "translate/ChatDeliveryMode.class", target, legacy)
        assert all(value in timing for value in (b"ORDERED", b"READY_FIRST", b"ORIGINAL_FIRST")), (target, "chat timing choices missing")
        assert jar.testzip() is None, path
        mixin_configs = ([] if forge else
                         [json.loads(jar.read(name)) for name in jar.namelist()
                          if name.endswith(".mixins.json")])
        read_class(jar, PREFIX + "translate/ChatComposerPanel.class", target, legacy)
        if forge:
            read_class(jar, PREFIX + "forgelegacy/ForgeChatComposer.class", target, True)
        else:
            composers = [config for config in mixin_configs
                         if "ChatComposerMixin" in config.get("client", [])]
            assert len(composers) == 1, (target, "chat composer mixin registration")
            assert_composer_boundary(
                jar,
                composers[0]["package"].replace(".", "/") + "/ChatComposerMixin.class",
                target,
                legacy,
            )
        if not legacy:
            read_class(jar, PREFIX + "service/OutgoingChatTranslator.class", target, False)

            book = registered_client_mixin(
                jar, mixin_configs, "BookPageMixin", target, False)
            for marker in [b"nyanlex$lastBookMode", b"bookMode", b"ORIGINAL_ONLY"]:
                assert marker in book, (target, "book G-mode cache invalidation", marker)
            assert any(marker in book for marker in (b"nyanlex$forceResplit", b"nyanlex$resplit")), (
                target, "book G-mode cache invalidation hook")
            assert any(marker in book for marker in (b"nyanlex$translateBookPage", b"nyanlex$page")), (
                target, "book page translation hook")

            # Platforms with the generic screen-text surface must leave already-laid-out book
            # rows to BookPageMixin. This is independent of which final GUI draw overloads a
            # Minecraft version exposes.
            generic_gui = [config for config in mixin_configs
                           if "GuiGraphicsTextMixin" in config.get("client", [])]
            if generic_gui:
                assert len(generic_gui) == 1, (target, "generic GUI mixin registration")
                ordered_platforms = [name for name in jar.namelist()
                                     if name.startswith(PREFIX) and name.endswith(".class")
                                     and b"screenTextFcs" in jar.read(name)]
                assert len(ordered_platforms) == 1, (
                    target, "ordered screen-text platform boundary", ordered_platforms)
                ordered_platform = read_class(
                    jar, ordered_platforms[0], target, legacy)
                assert contains_binary_name(
                    ordered_platform,
                    "net.minecraft.client.gui.screens.inventory.BookViewScreen"), (
                    target, "book rows must not re-enter generic screen translation")

            # 1.17+ world signs translate only at their four-row client render cache.
            # Keeping the translated cache inside this mixin (separate from vanilla's
            # original cache) is what makes G switch back immediately without mutating
            # SignText/SignBlockEntity or any server/NBT state.
            sign = registered_client_mixin(
                jar, mixin_configs, "SignTextMixin", target, False)
            # Vanilla member names are remapped inside production Fabric JARs.
            # Check NyanLex-owned semantic markers here; check-port-mixins.py
            # separately resolves and validates the actual target descriptors.
            for marker in [b"worldSignLines", b"WorldSign.renderMessages",
                           b"nyanlex$projectedLines", b"nyanlex$preparedLines",
                           b"WorldSignRenderScope"]:
                assert marker in sign, (target, "world-sign render projection", marker)
            for forbidden in [b"setMessage", b"setText", b"CompoundTag"]:
                assert forbidden not in sign, (target, "world-sign data mutation", forbidden)
            expected_owner = ("net.minecraft.world.level.block.entity.SignBlockEntity"
                              if target in {"fabric1171", "fabric1182", "fabric1194"}
                              else "net.minecraft.world.level.block.entity.SignText")
            assert contains_binary_name(sign, expected_owner), (
                target, "world-sign cache owner", expected_owner)
            sign_renderer = registered_client_mixin(
                jar, mixin_configs, "SignRendererLayoutMixin", target, False)
            for marker in [b"fitTranslatedRows", b"WorldSignRenderAdapter",
                           b"SignRendererLayout.fitTranslatedRows"]:
                assert marker in sign_renderer, (target, "world-sign renderer fit", marker)
            adapters = [name for name in jar.namelist()
                        if name.startswith(PREFIX) and name.endswith("/WorldSignRenderAdapter.class")]
            assert len(adapters) == 1, (target, "world-sign renderer adapter", adapters)
            adapter = read_class(jar, adapters[0], target, False)
            for marker in [b"WorldSignRenderScope", b"fitScale", b"scale"]:
                assert marker in adapter, (target, "world-sign complete-row fit", marker)
            scope = read_class(
                jar, PREFIX + "translate/WorldSignRenderScope.class", target, False)
            for marker in [b"begin", b"active", b"end", b"fitScale"]:
                assert marker in scope, (target, "world-sign render scope", marker)
            sign_platforms = [name for name in jar.namelist()
                              if name.startswith(PREFIX) and name.endswith(".class")
                              and b"worldSignLines" in jar.read(name)]
            assert len(sign_platforms) == 2, (
                target, "world-sign projection boundary", sign_platforms)
            # One hit is the platform entry point and the other is the mixin caller.
            platform_name = next(name for name in sign_platforms
                                 if not name.endswith("/SignTextMixin.class"))
            sign_platform = read_class(jar, platform_name, target, False)
            for marker in [b"worldSign", b"warmWorldTextBatch", b"translateUi", b"nameMode"]:
                assert marker in sign_platform, (target, "world-sign surface policy", marker)

        # Jade object names and advancement toast titles are available from 1.16.5.
        # Both are optional-at-runtime boundaries: the JAR must still load when Jade is
        # absent, while every supported Jade provider generation shares one mixin.
        if not forge and target not in {"fabric1144", "fabric1152"}:
            jade = registered_client_mixin(
                jar, mixin_configs, "JadeObjectNameMixin", target, legacy)
            assert contains_binary_name(jade, "snownee.jade.addon.core.ObjectNameProvider"), (
                target, "missing Jade provider generation", b"ObjectNameProvider")
            for marker in [b"ObjectNameProvider$ForBlock",
                           b"ObjectNameProvider$ForEntity"]:
                assert marker in jade, (target, "missing Jade provider generation", marker)
            assert (b"translateVisible" if legacy else b"jadeObjectName") in jade, (
                target, "Jade object-name hook is not wired to the translation surface")

            advancement = registered_client_mixin(
                jar, mixin_configs, "AdvancementToastMixin", target, legacy)
            assert b"AdvancementToast" in advancement, (target, "advancement toast target")
            assert (b"translateVisible" if legacy else b"advancementText") in advancement, (
                target, "advancement toast hook is not wired to the translation surface")

            if not legacy:
                semantic_chat = [name for name in jar.namelist()
                                 if name.startswith(PREFIX) and name.endswith(".class")
                                 and b"chat.type.advancement." in jar.read(name)]
                assert len(semantic_chat) == 1, (
                    target, "semantic advancement chat integration", semantic_chat)

        # Optional FTB Quests compatibility must retain both API generations. Older releases
        # expose ClientQuestFile.INSTANCE and ftblibrary.ui.TextField; newer releases use the
        # getInstance() accessor and client.gui.widget.TextField package.
        quest_source = PREFIX + "warmup/QuestWarmupSource.class"
        if quest_source in jar.namelist():
            quest = read_class(jar, quest_source, target, legacy)
            for marker in [b"INSTANCE", b"getInstance"]:
                assert marker in quest, (target, "missing FTB quest-file compatibility", marker)
            text_field = registered_client_mixin(
                jar, mixin_configs, "TextFieldMixin", target, legacy)
            for marker in ["dev.ftb.mods.ftblibrary.ui.TextField",
                           "dev.ftb.mods.ftblibrary.client.gui.widget.TextField"]:
                assert contains_binary_name(text_field, marker), (
                    target, "missing FTB TextField compatibility", marker)

            # Completed quest translations must refresh the current FTB page from the
            # cache. Mutating the old widget before refresh races with FTB replacing it,
            # which used to make the text update only after toggling the display twice.
            quest_platforms = [name for name in jar.namelist()
                               if name.startswith(PREFIX) and name.endswith(".class")
                               and b"questWidgetText" in jar.read(name)
                               and b"refreshCurrentQuestScreen" in jar.read(name)]
            assert len(quest_platforms) == 1, (target, "quest widget integration", quest_platforms)
            quest_platform = read_class(jar, quest_platforms[0], target, legacy)
            assert b"refreshCurrentQuestScreen" in quest_platform, (
                target, "quest completion must refresh the current screen")
            assert b"applyQuestWidgetText" not in quest_platform, (
                target, "stale quest widget mutation path remains")
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
        if not legacy:
            assert b"warmWorldTextBatch" in bytecode, (target, b"warmWorldTextBatch")

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

            # The UI count represents the durable active-language store, not the
            # deliberately capped in-memory LRU. Confirm the store contract and each
            # delegating implementation made it into every modern artifact.
            for name in ["PersistentStore", "FileStore", "LanguageFileStore",
                         "ProviderLanguageFileStore", "TranslationCache"]:
                cache_class = read_class(
                    jar, PREFIX + "cache/" + name + ".class", target, False)
                assert b"size" in cache_class, (target, name, "durable size contract")

        languages = ["en_us", "ja_jp", "zh_tw", "zh_cn"]
        extension = "lang" if target == "forge1122" else "json"
        for language in languages:
            text = read_entry(jar, "assets/nyanlex/lang/" + language + "." + extension, target).decode("utf-8")
            settings_keys = LEGACY_SETTINGS_KEYS if legacy else MODERN_SETTINGS_KEYS
            for key in REQUEST_KEYS + settings_keys + ["key.nyanlex.screenscan",
                    "nyanlex.composer.title", "nyanlex.composer.fill", "nyanlex.composer.paused",
                    "config.nyanlex.composer" if legacy else "nyanlex.settings.composer"]:
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
        for member in [b"translationRequestsEnabled"]:
            assert member in ui, (target, ui_class, "unwired setting", member)
        for member in ([b"A_EXPORT", b"A_IMPORT", b"A_ONLINE"] if legacy else [b"EXPORT", b"IMPORT", b"master"]):
            assert member in ui, (target, ui_class, member)

        if target == "forge1122":
            assert b"FMLCorePlugin:" in read_entry(jar, "META-INF/MANIFEST.MF", target), target
            assert PREFIX + "forgelegacy/ScreenTextTransformer.class" in jar.namelist()
        if target == "forge1132":
            assert "nyanlex-screen-text.js" in jar.namelist()
            assert "META-INF/coremods.json" in jar.namelist()
    return {"requests_ui": [name[len(PREFIX):] for name in request_ui]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ports-only", action="store_true", help="Validate only the newly added port targets")
    parser.add_argument("--target", action="append", help="Validate a specific loader/version, repeatable")
    args = parser.parse_args()
    selected = [target for target in targets() if not args.ports_only or target.expanded]
    if args.target:
        unknown = set(args.target) - {target.key for target in selected}
        if unknown:
            parser.error("Unknown targets: " + ", ".join(sorted(unknown)))
        selected = [target for target in selected if target.key in args.target]
    results = []
    for target in selected:
        path = target.jar
        assert path.is_file(), (target.key, "missing built artifact", path)
        before = path.read_bytes()
        with ZipFile(path) as jar:
            assert_java_level(jar, target.key, target.java)
        found = check_jar(target.source_project, path)
        assert path.read_bytes() == before, (target.key, "artifact changed during feature validation")
        results.append({"target": target.key, "jar": str(path.relative_to(ROOT)),
                        "sha256": hashlib.sha256(before).hexdigest(),
                        "requests_ui": found["requests_ui"]})
    output = ROOT / "build/translation-feature-artifacts.json"
    output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    compatibility = sum(target.source_project not in {"fabric1144", "fabric1152",
                                                       "forge1122", "forge1132"}
                        for target in selected)
    modern = sum(target.source_project not in LEGACY_TARGETS for target in selected)
    print("TRANSLATION_FEATURE_ARTIFACTS_OK version={} targets={} sharing={} screen_scan={} "
          "legacy_java8={} request_switch={} do_not_translate={} requests_ui={} "
          "jade={} advancements={} world_signs={} durable_counts={}".format(
              VERSION, len(results), len(results), len(results), sum(target.java == 8 for target in selected),
              len(results), len(results), len(results), compatibility, compatibility, modern, modern))


if __name__ == "__main__":
    main()
