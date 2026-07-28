# Minecraft Translator 1.0.3

[繁體中文](README.md)

Minecraft Translator is a client-side real-time translation mod. It translates text that needs translation on screen without changing server data or sending chat messages for the player.

## Features

- Translates chat, item names, tooltips, scoreboards, name tags, boss bars, titles, action bars, books, and mod screens.
- Each surface can show original text, translated text, or both.
- Supports Google, Youdao, DeepL, Microsoft web translation, and OpenAI-compatible APIs.
- Every supported target includes ChatGPT/Codex sign-in, model and reasoning-effort selection, and session token usage; the default is `gpt-5.6-terra` / `medium`.
- Async batching, priority queues, disk caches, and failure backoff reduce stalls and duplicate requests.
- Player names are masked only from the TAB list; ordinary item text such as `with Chest` is no longer guessed as a player name.

## Direct downloads

Each JAR supports only the exact Minecraft version and loader in its filename.

[Download the all-versions ZIP with loader/version folders](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/MinecraftTranslator-1.0.3-all-versions.zip)

### Fabric

Fabric targets require matching Fabric Loader and Fabric API versions.

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.14.4 | 8 | [mctranslator-1.0.3-Fabric-1.14.4.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.14.4.jar) |
| 1.15.2 | 8 | [mctranslator-1.0.3-Fabric-1.15.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.15.2.jar) |
| 1.16.5 | 8 | [mctranslator-1.0.3-Fabric-1.16.5.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.16.5.jar) |
| 1.17.1 | 16 | [mctranslator-1.0.3-Fabric-1.17.1.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.17.1.jar) |
| 1.18.2 | 17 | [mctranslator-1.0.3-Fabric-1.18.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.18.2.jar) |
| 1.19.4 | 17 | [mctranslator-1.0.3-Fabric-1.19.4.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.19.4.jar) |
| 1.20.1 | 17 | [mctranslator-1.0.3-Fabric-1.20.1.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.20.1.jar) |
| 1.21.1 | 21 | [mctranslator-1.0.3-Fabric-1.21.1.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.21.1.jar) |
| 1.21.11 | 21 | [mctranslator-1.0.3-Fabric-1.21.11.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-1.21.11.jar) |
| 26.1.2 | 25 | [mctranslator-1.0.3-Fabric-26.1.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-26.1.2.jar) |
| 26.2 | 25 | [mctranslator-1.0.3-Fabric-26.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Fabric-26.2.jar) |

### NeoForge

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.20.1 | 17 | [mctranslator-1.0.3-NeoForge-1.20.1.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-NeoForge-1.20.1.jar) |
| 1.21.1 | 21 | [mctranslator-1.0.3-NeoForge-1.21.1.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-NeoForge-1.21.1.jar) |
| 26.2 | 25 | [mctranslator-1.0.3-NeoForge-26.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-NeoForge-26.2.jar) |

### Forge

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.12.2 | 8 | [mctranslator-1.0.3-Forge-1.12.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Forge-1.12.2.jar) |
| 1.13.2 | 8 | [mctranslator-1.0.3-Forge-1.13.2.jar](https://github.com/DragonMeow1012/MinecraftTranslator/releases/download/v1.0.3/mctranslator-1.0.3-Forge-1.13.2.jar) |

## Installation

1. Download the exact JAR from the tables above.
2. Install the same Minecraft version of Fabric, NeoForge, or Forge.
3. Put the JAR in that instance's `mods` directory.
4. Start the game with the Java version shown in the table.

## Translation sources

| Source | API key | Notes |
| --- | --- | --- |
| Google | Not required | Default machine translation source. |
| Youdao / DeepL / Microsoft | Not required | Experimental web interfaces that may be affected by rate limits or site changes. |
| OpenAI-compatible API | Depends on service | Configurable Base URL, model, API key, glossary, and GT fallback. |
| ChatGPT/Codex | ChatGPT sign-in | Available on every listed target; install Codex CLI first. Includes model, effort, and token controls. |

## Keys

Fabric 1.17.1+ and NeoForge:

| Key | Action |
| --- | --- |
| `G` | Toggle original/translated display |
| `R` | Retranslate the item under the pointer |
| `P` | Scan and translate the current screen's buttons and options |
| Unbound | Open Translation Settings |

Legacy UI:

| Targets | Keys |
| --- | --- |
| Fabric 1.14.4-1.16.5 | `G` opens Translation Settings |
| Forge 1.12.2-1.13.2 | `G` opens Translation Settings; `H` enables/disables translation |

## 1.0.3 highlights

- Keeps the stable 1.0.2 translation architecture.
- Adds ChatGPT/Codex sign-in, model selection, and token display.
- Speeds up Codex by disabling unused tools and summaries, avoiding cleanup waits, and using the advertised priority tier.
- Masks player names only from TAB and removes name guessing from templates and caches.
- Fixes `Bloom Boat with Chest` being sent as `Bloom Boat with {value}`.
- Publishes all 16 supported targets as version 1.0.3.

## Privacy

- Only text requiring translation is sent to the selected source.
- API keys are stored in the local Minecraft configuration directory.
- Player names in TAB are masked locally. Other server text may still include user-provided content.
- Redact API keys, Authorization headers, and private server information before reporting an issue.

## Source and issues

See [PACKAGING.md](PACKAGING.md) for build commands and the release folder layout. Report problems through [GitHub Issues](https://github.com/DragonMeow1012/MinecraftTranslator/issues).
