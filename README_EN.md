# Minecraft Translator 1.0.2

[繁體中文說明](README.md)

Minecraft Translator is a client-side, real-time translation mod for Minecraft. It translates chat, items, and interface text that is actually visible on the current screen. Batching, caching, and strict response validation reduce request count and token use without changing server data or messages sent by the player.

## Feature summary

- Translates chat, item names, and tooltips asynchronously without blocking rendering.
- Modern builds also cover scoreboards, name tags, boss bars, titles/subtitles, action bars, books, lecterns, FTB quests, and other screen text.
- Each surface can show original only, translation only, or original + translation, and can independently use AI translation or machine translation.
- Collects only content visible in the current interface. Unopened Creative tabs and background tooltips generated while building search indexes should not create translation requests.
- Hovered items and actively used content have the highest priority and can overtake unsent ordinary batches.
- Searchable target-language and machine-provider screens; the target can also follow Minecraft's current language.
- Modern builds preserve paragraphs, blank lines, colors, bold/italic styling, icons, numbers, times, URLs, player names, click/hover events, and the original layout where supported. Java 8 compatibility builds retain the formatting their older APIs can safely reconstruct.
- Modern builds provide memory and persistent disk caches to avoid duplicate requests. A debug overlay shows waiting, success, failure, and fallback state.
- Modern player-name masking can keep listed player IDs out of ordinary translation text.
- Modern builds support OpenAI-compatible AI endpoints, models, glossaries, multiple rotating API keys, and an option to disable GT fallback after an AI failure. Java 8 builds provide a reduced AI/machine configuration.

## Supported versions: 16 separate JARs

The Java column is the Java major version required to run that Minecraft release. Every JAR is pinned to the Minecraft version and loader in its filename; loaders and versions are not interchangeable.

| # | Minecraft | Loader | Java | Exact JAR name |
| ---: | --- | --- | ---: | --- |
| 1 | 1.12.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.12.2.jar` |
| 2 | 1.13.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.13.2.jar` |
| 3 | 1.14.4 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.14.4.jar` |
| 4 | 1.15.2 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.15.2.jar` |
| 5 | 1.16.5 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.16.5.jar` |
| 6 | 1.17.1 | Fabric | 16 | `mctranslator-1.0.2-Fabric-1.17.1.jar` |
| 7 | 1.18.2 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.18.2.jar` |
| 8 | 1.19.4 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.19.4.jar` |
| 9 | 1.20.1 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.20.1.jar` |
| 10 | 1.20.1 | NeoForge | 17 | `mctranslator-1.0.2-NeoForge-1.20.1.jar` |
| 11 | 1.21.1 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.1.jar` |
| 12 | 1.21.1 | NeoForge | 21 | `mctranslator-1.0.2-NeoForge-1.21.1.jar` |
| 13 | 1.21.11 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.11.jar` |
| 14 | 26.1.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.1.2.jar` |
| 15 | 26.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.2.jar` |
| 16 | 26.2 | NeoForge | 25 | `mctranslator-1.0.2-NeoForge-26.2.jar` |

## Installation

1. Download the exact matching JAR from the [latest GitHub Release](https://github.com/DragonMeow1012/MinecraftTranslator/releases/latest).
2. Install the matching Fabric, Forge, or NeoForge release. Fabric builds also require the matching Fabric API.
3. Put the Minecraft Translator JAR in that instance's `mods` directory.
4. Start the game with the Java version shown in the table.
5. For the first test, use only the loader, its required API, and this mod. Add a large modpack after confirming the clean instance works.

Minecraft 1.21.1 Fabric and Minecraft 1.21.1 NeoForge are different files. Minecraft 1.21.1 and 1.21.11 are not compatible targets either.

## Translation sources

Modern translation settings provide a searchable machine-provider list:

| Source | User API key | Status |
| --- | --- | --- |
| Google GT | Not required | Default machine source; strict batching and validation path |
| Youdao Web | Not required | Unofficial experimental web endpoint |
| DeepL Web | Not required | Unofficial experimental web endpoint |
| Microsoft / Bing Web | Not required | Unofficial experimental web endpoint |

The Youdao, DeepL, and Microsoft sources are no-user-key website interfaces included for regional testing. They are not guaranteed official APIs. A website may change its protocol, add verification, rate-limit or block an IP, reject automation, or remove the current interface at any time, so these sources may fail temporarily or permanently. The mod applies whole-entry batching and reconstruction checks where possible, but does not promise permanent reliability for experimental endpoints.

AI translation is separate from the machine-provider list. It uses the OpenAI-compatible Base URL, model, and API key configured by the user. It can work with Gemini, OpenAI, DeepSeek, OpenRouter, compatible self-hosted services, and other OpenAI-compatible providers; actual features and cost depend on that provider.

## Settings and key bindings

Modern builds expose the full screen under **Options → Translation Settings**. It includes:

- Per-surface display mode and AI/machine engine selection.
- Searchable target language and machine provider.
- Batch-window duration, per-engine request cooldown, and retry behavior.
- AI Base URL, model, API keys, glossary, and the “disable GT fallback” option.
- Translation debug overlay, active provider/language cache clearing, and keybind settings.

Default keys on modern builds:

| Key | Action |
| --- | --- |
| `G` | Quickly toggle displayed original/translated text |
| `R` | Retranslate the item under the pointer |
| `P` | Scan and translate buttons/options on the current screen |
| Unbound | Open Translation Settings |

Every binding can be changed in Minecraft Controls or the mod's keybind screen. Java 8 compatibility builds have a smaller UI and key set; see the legacy section below.

## Batch collection rules

Ordinary translation misses enter one ordered collection queue and are sent as batches of complete entries:

- The default batch window is **5 seconds (5000 ms)** and is configurable.
- Setting it to **0** disables the waiting window, but never performs network I/O inside the render hook. Content is sent on the **next client tick**.
- The safe input budget is **1400 characters per batch**. Splits occur only between complete item names, tooltip lines, or paragraphs; the last entry is never cut in half.
- If one complete entry itself exceeds 1400 characters, it is sent alone instead of being truncated.
- When adding the next entry would exceed the budget, the current batch is sent and the remaining entries stay queued for the next batch.
- Hovered items and other high-priority content are placed before ordinary entries.
- Concurrent requests for the same content coalesce. Only a successful result is split back into individual cache entries.

## Strict AI and Google GT batch integrity

AI and Google GT are the two formally verified core paths. “Guaranteed” here means batch integrity and prevention of cross-entry cache contamination; it does not mean a provider can guarantee perfect translation quality or permanent uptime.

A normal batch follows this protocol:

1. The collector preserves every complete entry and its paragraph boundaries.
2. Each entry receives paired start/end boundaries (dual anchors), while newlines, paragraphs, color spans, and other non-translatable markers are masked.
3. The whole batch is sent through one high-level translation request. Within the normal safety budget, Google GT also stays in one physical HTTP request.
4. The response must pass entry-count, order, paired-anchor, marker, paragraph, and protected-token validation.
5. Only a fully aligned response is split by entry and written to each semantic/style cache.
6. Missing, extra, reordered, or interleaved anchors, lost markers, or semantic text outside an anchor cause the untrusted result to be rejected. The mod may retry with smaller batches, but it does not write one entry's text into another entry's cache.

Colors and formatting are reconstructed from the original Minecraft Component. The translator controls only protected text spans; color-span, paragraph, and formatting markers are validated before the result is projected onto the current screen. Dynamic server numbers, recolored variants, and identical wording with different colors should therefore not inherit an older entry's styling. Fully custom third-party renderers may still require compatibility work; report them with the relevant mod and screenshot.

## Cooldown and 429 protection

Available per-engine request cooldown values are:

`0 / 1000 / 2000 / 4000 / 6000 / 8000 / 10000 ms`

The default is **6000 ms**. AI and machine translation are paced independently. `0` disables proactive pacing only; it does not disable the five-second batch window. A longer cooldown reduces burst-related 429 responses, but cannot guarantee that a third party will not limit an account, key, or IP. Failed entries use backoff and visibility demand so they are not resent every rendered frame.

## Provider/language cache isolation and legacy cache preservation

- Machine caches are partitioned by both machine provider and target language. Switching either one cannot silently reuse another partition's wording.
- Google keeps its established filename, for example `mctranslator-cache-zh-tw.json`; adding the provider selector does not rename or delete it.
- Experimental providers use separate siblings such as `mctranslator-cache-youdao-zh-tw.json`, so they cannot overwrite Google's cache.
- AI caches are independently partitioned by target language.
- “Clear current cache” clears the active machine-provider/language partition and the current language's AI cache; other machine sources and language partitions remain available.
- Legacy cache-format upgrades create a safety backup before migration. The original should not be overwritten when the backup cannot be verified.

## Legacy Forge JSON limitations

Forge 1.12.2 and 1.13.2 are Java 8 compatibility builds focused on chat and item tooltips. They do not have the full modern settings screen or searchable provider picker. Their settings are stored in:

`config/mctranslator-forge-legacy.json`

Close the game before editing this JSON file, then restart after saving. Common fields include:

- `machineTranslationProvider`: `google`, `youdao`, `deepl`, or `microsoft`.
- `batchWindowMs`: default `5000`; `0` means next tick.
- `requestCooldownMs`: default `6000`.
- AI endpoint, model, API keys, GT-fallback policy, and debug options.

These builds use `G` for display toggling and are limited by old Minecraft GUI/text APIs. Fabric 1.14.4–1.16.5 are also Java 8 compatibility ports, but include a compact in-game settings screen. All five Java 8 builds still batch only complete entries, reconstruct AI/GT results with paired boundaries, and reject batches with damaged boundaries; style reconstruction remains limited by the old APIs.

## Privacy and API keys

- Only text requiring translation is sent to the selected provider. The mod does not change server data or outgoing player chat.
- Google GT and the three experimental machine sources do not ask the user for an API key, but the third-party website still receives request text, network address, and ordinary connection metadata. Its privacy policy and terms still apply.
- AI text is sent to the configured Base URL. API keys are stored in the local Minecraft configuration directory; protect configuration files and modpack backups.
- Never paste API keys into an issue, chat message, screenshot, `latest.log`, or crash report. Redact URL query parameters, Authorization headers, and keys in configuration files before reporting.
- Player-name masking keeps recognized online names unchanged and out of requests, but arbitrary custom text can still contain content supplied by a server or player.

## Building from source

The 16 targets are separate Gradle projects because Minecraft, Fabric, Forge, and NeoForge APIs are not binary-compatible across these releases. See [PACKAGING.md](PACKAGING.md) for project directories, required JDKs, build commands, verification, and release layout.

The version remains **1.0.2**, and release artifacts are collected under `mods-jar/1.0.2`. Compilation, remapping, and metadata checks do not replace an in-game test with the matching Minecraft and loader version.

## Reporting issues

Open a [GitHub issue](https://github.com/DragonMeow1012/MinecraftTranslator/issues) and include as much of the following as possible:

- Exact JAR filename, Minecraft version, loader name/version, Fabric API version when applicable, and Java version.
- The affected screen/server/mod, reproduction steps, original text, and expected result.
- Target language, AI or machine mode, selected machine provider, batch-window value, and cooldown.
- Whether you cleared the active cache or changed language/provider, and whether a clean instance reproduces the issue.
- `latest.log`, crash report, debug-overlay output, mod list, and screenshots with sensitive data removed.

Before submitting, verify again that every API key, token, and private-server detail has been redacted.
