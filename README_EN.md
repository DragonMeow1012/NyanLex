# Minecraft Translator

[繁體中文說明](README.md)

Client-side, real-time translation for Minecraft chat and interface text.

Minecraft Translator can use Google Translate or an OpenAI-compatible AI endpoint,
preserves Minecraft text styling, and never changes server data or messages sent by
the player. The settings UI follows Minecraft's selected language and the translation
target uses a searchable, vanilla-style language list.

## Download and install

1. Open the [latest GitHub Release](https://github.com/DragonMeow1012/MinecraftTranslator/releases/latest).
2. Download the JAR whose **Minecraft version and mod loader both match** your instance.
3. Install the required loader and, for Fabric builds, the matching Fabric API.
4. Put the JAR in the instance's `mods` folder and start Minecraft.

The files are version-pinned. Do not use a 1.21.1 JAR on 1.21.11, and do not mix
Fabric, Forge, and NeoForge builds.

## Supported versions

| Minecraft | Loader | Required Java | Release file |
| --- | --- | ---: | --- |
| 1.12.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.12.2.jar` |
| 1.13.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.13.2.jar` |
| 1.14.4 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.14.4.jar` |
| 1.15.2 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.15.2.jar` |
| 1.16.5 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.16.5.jar` |
| 1.17.1 | Fabric | 16 | `mctranslator-1.0.2-Fabric-1.17.1.jar` |
| 1.18.2 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.18.2.jar` |
| 1.19.4 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.19.4.jar` |
| 1.20.1 | Fabric / NeoForge | 17 | matching Fabric or NeoForge JAR |
| 1.21.1 | Fabric / NeoForge | 21 | matching Fabric or NeoForge JAR |
| 1.21.11 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.11.jar` |
| 26.1.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.1.2.jar` |
| 26.2 | Fabric / NeoForge | 25 | matching Fabric or NeoForge JAR |

## Features

- Translates chat, item names and tooltips without blocking the render thread.
- Modern builds also support scoreboards, name tags, boss bars, titles, action bars,
  books, lecterns, and custom screen text.
- Original only, translation only, or original + translation modes per surface.
- Google Translate and configurable OpenAI-compatible AI providers.
- Automatic target language following the current Minecraft language.
- Searchable translation-target language screen based on Minecraft's language UI.
- Preserves colors, formatting, click events, hover events, icons, numbers, times,
  URLs, player names, and layout where supported.
- Memory and disk caches reduce duplicate requests.
- Player-name masking prevents listed player IDs from being sent to translation providers.
- AI rate-limit fallback and manual retranslation controls.

## Settings and controls

On modern builds, open **Options → Translation Settings**. Translation settings,
target language, AI provider/model/API keys, request cooldown, cache controls, and
key bindings are available in-game.

Default keys:

| Key | Action |
| --- | --- |
| `G` | Toggle displayed original/translation text |
| `R` | Retranslate the hovered item |
| `P` | Scan and translate the current screen |
| Unbound | Cycle translation display mode |

All keys can be changed in Minecraft's Controls screen or the mod's keybind settings.

## Version-specific limitations

- Forge 1.12.2 and 1.13.2 are compatibility builds focused on chat and item tooltips.
  They use the `G` hotkey and do not include the complete modern settings screen.
- Fabric 1.14.4 through 1.16.5 focus on chat and tooltips, but include target-language
  selection and search.
- Fabric 1.21.11 does not hook boss-bar, entity-name, or scoreboard rendering because
  that release changed those rendering APIs. Chat, tooltips, books, settings, target
  language, and UI translation remain available.
- Compilation and remapping are verified for every release JAR. In-game behavior can
  still vary with other mods, resource packs, and server-specific interfaces.

## Privacy

Translation text is sent only to the provider selected in settings. The mod is
client-side and does not alter outgoing player chat or server data. If an AI provider
is configured, its endpoint and privacy policy apply. API keys are stored locally in
the Minecraft configuration directory; do not share configuration files containing keys.

## Building from source

Each supported Minecraft version has its own Gradle project because Minecraft and
loader APIs are not binary-compatible across these releases. See [PACKAGING.md](PACKAGING.md)
for the project, Java, loader, and release layout matrix.

Version branches use the form:

```text
mc/<minecraft-version>-<loader>
```

The combined release branch is `release/1.0.2-current`. Built artifacts are collected
under `mods-jar/1.0.2`.

## Reporting issues

Open a [GitHub issue](https://github.com/DragonMeow1012/MinecraftTranslator/issues)
and include:

- Minecraft version
- Fabric, Forge, or NeoForge version
- Java version
- Other installed mods
- Relevant client log or crash report

Do not include API keys in logs or screenshots.
