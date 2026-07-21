# Minecraft Translator 1.0.2

[繁體中文說明](README.md)

Minecraft Translator is a client-side real-time translation mod. It processes only text that is currently visible and requires translation. It does not change server data or outgoing player chat. Asynchronous queues, complete-entry batching, partitioned caches, and strict response validation reduce requests and prevent one response from contaminating another entry.

## Downloads and release layout

Every JAR is pinned to the Minecraft version and loader printed in its filename. Versions and loaders are not interchangeable. Download files from [GitHub Releases](https://github.com/DragonMeow1012/MinecraftTranslator/releases/latest).

The release provides:

- 16 individual JARs ready for a `mods` directory.
- `MinecraftTranslator-1.0.2-Fabric.zip` containing 11 Fabric JARs.
- `MinecraftTranslator-1.0.2-Forge.zip` containing 2 Forge JARs.
- `MinecraftTranslator-1.0.2-NeoForge.zip` containing 3 NeoForge JARs.
- `MinecraftTranslator-1.0.2-all-versions.zip` with `fabric/`, `forge/`, and `neoforge/` directories.

## Support by exact target

| Minecraft | Loader | Java | JAR | Supported content |
| --- | --- | ---: | --- | --- |
| 1.12.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.12.2.jar` | Chat, item names, and tooltips; `G` display toggle; JSON configuration; Google, Youdao, DeepL, and Microsoft machine translation; OpenAI-compatible AI; complete-entry batching, boundary validation, and cooldown. The old text API limits settings UI and style reconstruction. |
| 1.13.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.13.2.jar` | Chat, item names, and tooltips; `G` display toggle; JSON configuration; Google, Youdao, DeepL, and Microsoft machine translation; OpenAI-compatible AI; complete-entry batching, boundary validation, and cooldown. The old text API limits settings UI and style reconstruction. |
| 1.14.4 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.14.4.jar` | Chat, item names, and tooltips; compact in-game settings; searchable target-language list; machine and AI configuration; `G` toggle; complete-entry batching and boundary validation. Colors and events are preserved where this API allows it. |
| 1.15.2 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.15.2.jar` | Chat, item names, and tooltips; compact in-game settings; searchable target-language list; machine and AI configuration; `G` toggle; complete-entry batching and boundary validation. Colors and events are preserved where this API allows it. |
| 1.16.5 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.16.5.jar` | Chat, item names, and tooltips; compact in-game settings; searchable target-language list; machine and AI configuration; `G` toggle; complete-entry batching and boundary validation. Colors and events are preserved where this API allows it. |
| 1.17.1 | Fabric | 16 | `mctranslator-1.0.2-Fabric-1.17.1.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.18.2 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.18.2.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.19.4 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.19.4.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.20.1 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.20.1.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.20.1 | NeoForge | 17 | `mctranslator-1.0.2-NeoForge-1.20.1.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.21.1 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.1.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.21.1 | NeoForge | 21 | `mctranslator-1.0.2-NeoForge-1.21.1.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 1.21.11 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.11.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 26.1.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.1.2.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 26.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.2.jar` | The 2026-07-14 06:26 tested baseline. Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |
| 26.2 | NeoForge | 25 | `mctranslator-1.0.2-NeoForge-26.2.jar` | Chat, items, scoreboards, name tags, boss bars, titles/subtitles, action bars, books/lecterns, buttons and screen text, and FTB quests; full settings and debug overlay; `G`/`R`/`P`; AI plus four machine sources; disk cache, player-name masking, paragraph/color/format/event protection. |

Fabric 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.21.1, 1.21.11, 26.1.2, and 26.2 plus NeoForge 1.20.1, 1.21.1, and 26.2 include the July 14 style-projection fix. If translated wording is usable but a provider destroys color markers, the mod does not resend forever in the background. A later real observation after cooldown may retry the exact color projection.

## Installation

1. Download the exact JAR from the table.
2. Install the matching Fabric, Forge, or NeoForge release. Fabric targets also need the matching Fabric API.
3. Put the JAR in that instance's `mods` directory.
4. Start Minecraft with the Java major version shown above.
5. Test first with only the loader, required API, and this mod; add a large modpack after the clean instance works.

Fabric and NeoForge files for the same Minecraft version are different. Minecraft 1.21.1 and 1.21.11 are not interchangeable either.

## Translation sources

| Source | User API key | Notes |
| --- | --- | --- |
| Google GT | Not required | Default machine source with complete-entry batching and strict validation. |
| Youdao Web | Not required | Unofficial experimental website endpoint; site changes or restrictions may break it. |
| DeepL Web | Not required | Unofficial experimental website endpoint; site changes or restrictions may break it. |
| Microsoft/Bing Web | Not required | Unofficial experimental website endpoint; site changes or restrictions may break it. |
| OpenAI-compatible AI | Optional, depending on endpoint | Supports Gemini, OpenAI, DeepSeek, OpenRouter, Ollama, LM Studio, and self-hosted services. Base URL, model, rotating keys, glossary, and GT-fallback policy are configurable. A blank key omits the `Authorization` header. |

Youdao, DeepL, and Microsoft are website interfaces for regional testing, not guaranteed permanent APIs. A provider may change protocol, add verification, rate-limit or block an IP, or reject automation. The mod rejects structurally damaged responses but cannot guarantee third-party uptime.

## Display modes, settings, and keys

Each translation surface can independently show original only, translation only, or original plus translation, and can choose AI or machine translation.

Fabric 1.17.1 through 26.2 and all three NeoForge JARs provide searchable target languages and providers, batch/cooldown controls, AI endpoint/model/keys/glossary, cache clearing, a structured debug overlay, and these default keys:

| Key | Action |
| --- | --- |
| `G` | Toggle original/translated display |
| `R` | Retranslate the item under the pointer |
| `P` | Scan and translate buttons/options on the current screen |
| Unbound | Open Translation Settings |

Fabric 1.14.4, 1.15.2, and 1.16.5 provide a compact settings screen and `G`. Forge 1.12.2 and 1.13.2 use `G` and `config/mctranslator-forge-legacy.json`.

Fabric 1.17.1 through 26.2 and all three NeoForge JARs skip the vanilla Language, Skin, Sound, Controls, Chat, Resource Packs, and Accessibility settings pages to avoid retranslating already-localized text. Vanilla Video/Display settings and third-party mod screens remain eligible.

## Batching and validation

- The default batch window is **5000 ms**. Setting it to `0` sends on the next client tick; network I/O still never runs inside a render hook.
- The safe batch budget is **1400 characters**. Splits occur only between complete item names, tooltip rows, or paragraphs.
- Every entry receives paired boundaries. Newlines, paragraphs, color spans, numbers, times, URLs, player names, and other protected tokens are masked.
- A response must pass count, order, boundary, marker, paragraph, and token checks before entering semantic/style caches.
- Missing, crossed, reordered, or extra boundaries, damaged markers, or text outside boundaries are rejected. Smaller retries may occur, but one entry is never written into another entry's cache.
- Concurrent identical requests coalesce. Hovered items and actively used content receive higher priority.

## Cache, cooldown, and diagnostics

- Machine caches are partitioned by provider and target language; AI caches are partitioned by target language.
- Google keeps established filenames such as `mctranslator-cache-zh-tw.json`. Other providers use separate files such as `mctranslator-cache-youdao-zh-tw.json`.
- The default per-engine cooldown is **10000 ms**. Available values are `0 / 1000 / 2000 / 4000 / 6000 / 8000 / 10000 ms`.
- AI and machine translation are paced independently. `0` disables proactive cooldown only, not batching.
- Failed entries use backoff and visibility demand instead of resending every rendered frame.
- Debug reasons distinguish 429 rate limit, HTTP 5xx, authentication, timeout/network, boundary/order damage, paragraph loss, format/token loss, empty responses, and unknown failures.

## Privacy and API keys

- Only text requiring translation is sent to the selected source. Server data and outgoing player chat are not modified.
- AI text goes to the configured Base URL. API keys are stored in the local Minecraft configuration directory.
- Never include keys in issues, chats, screenshots, `latest.log`, or crash reports. Redact URL queries, Authorization headers, and configuration values.
- Player-name masking preserves recognized online names and keeps them out of ordinary translation text, but arbitrary server text may still contain user-provided content.

## Building from source

The 16 targets are separate Gradle projects because the Minecraft, Fabric, Forge, and NeoForge APIs are not binary-compatible across these releases. See [PACKAGING.md](PACKAGING.md) for project directories, JDKs, build commands, and release verification.

## Reporting issues

Open a [GitHub issue](https://github.com/DragonMeow1012/MinecraftTranslator/issues) with the complete JAR name, Minecraft/loader/Java versions, affected screen or mod, reproduction steps, original and expected text, target language, selected source, batching/cooldown settings, and redacted logs, debug overlay, and screenshots.
