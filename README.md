# NyanLex Translator 1.0.0

[繁體中文](README.zh-TW.md)

NyanLex Translator is a client-side real-time translation mod. It translates text that needs translation on screen without changing server data or sending chat messages for the player.

> **Unofficial.** NyanLex Translator is an independent community-style fan project. It is not affiliated with, endorsed by, or sponsored by Mojang, Microsoft, Hypixel, or any mod author. Minecraft is a trademark of Mojang AB / Microsoft.

## Privacy (read this first)

- **Online translation is off on a new install.** While it is off, no translation service receives any of your text.
- You can turn it on in three ways: from the Quick setup that opens by itself the first time you reach the title screen (choose machine translation, AI translation, or "Not now"; nothing you choose is applied, and nothing is sent, until you press Done); by pressing the translate-item key (default `R`) or translate-screen key (default `P`) while it is off, which first opens a confirmation window and only sends after you press "Start translating"; or in Translation settings > General ("Online translation").
- Things that work without turning it on, and send nothing: translations already in your local cache, translation packs you downloaded, the built-in glossary, and the vanilla text from the game's own language files.
- Once it is on, the text of the surfaces you set to translate (item descriptions, screens, and so on) is sent. If chat translation is enabled, chat messages are sent as well, **including private messages**.
- The text goes to the translation service you choose:
  - **Machine translation (Google, no key)** uses an **unofficial** web endpoint that may be rate-limited or stop working at any time.
  - **AI engines** (an OpenAI-compatible service such as Gemini, OpenAI or DeepSeek, or ChatGPT/Codex sign-in) also need your own key or sign-in, and go only to the service you configure. Signing in with ChatGPT uses your account's Codex quota.
- **API keys are stored only in the config file on your machine**, are sent only to the provider you chose, are masked in the settings screen, and are never written to logs or debug dumps.
- Users upgrading from an earlier version keep their existing settings: if you were already translating, online translation stays on.
- **Translation packs are download-only.** Finding and downloading them only reads public files from GitHub (`index.json` plus the files you confirm); no text and no list of your mods is sent, nothing is ever uploaded, and nothing is downloaded until you confirm.
- Player names from the TAB list are masked locally before sending; other server text may still contain user-provided content.

## Compatibility

- Fabric, NeoForge, and Forge targets are listed below (Minecraft 1.12.2 to 26.3).
- The legacy targets (Fabric 1.14.4-1.16.5 and Forge 1.12.2-1.13.2) have a simpler interface: a short Quick setup, the on-the-spot confirmation window and a categorized settings screen, but no translation packs and no pre-translation.
- Quest and task-book screens in modpacks get extra optimization (long paragraphs, colored text, tooltips).
- This is a client-side mod; it does not modify servers and does not send chat for you.

## Features

- Translates chat, item names, tooltips, scoreboards, name tags, boss bars, titles, action bars, books, and mod screens.
- Each surface can show original text, translated text, or both.
- Supports Google machine translation (unofficial endpoint) and OpenAI-compatible APIs such as Gemini, OpenAI, DeepSeek, OpenRouter, Ollama and LM Studio.
- Every supported target includes ChatGPT/Codex sign-in, model and reasoning-effort selection, and session token usage; the default is `gpt-5.6-terra` / `medium`.
- Async batching, priority queues, disk caches, and failure backoff reduce stalls and duplicate requests.
- Player names are masked only from the TAB list; ordinary item text such as `with Chest` is no longer guessed as a player name.
- **Items and mod screens follow the translation service you pick for them**: with AI they translate automatically like chat; with machine translation (Google) they translate on demand, so joining a server never floods it with text: press `R` on the item under your cursor, or `P` to translate the current screen. Anything already translated is shown straight from your saved translations either way; with AI, `R`/`P` force a fresh translation. Chat, scoreboards, name tags, boss bars, titles, action bars, and books keep translating automatically.
- **Segmented tooltip cache**: long tooltips (title plus multi-line body) cache and restore per segment, so only the segment that actually changed needs a fresh request.
- **Translation packs** (ready-made AI translations prepared by the maintainers and hosted on GitHub): the mod does not check for them at startup. There are two ways to get them: the last page of the Quick setup detects them automatically (it only appears when packs for your installed mods are found), and the "Detect and download translation packs" button under Translation settings > Packs. Both list each pack with its size and the expected total, download only after you confirm, and merge the files into your saved translations. "Clear downloaded translation packs" in the same category removes only what came from packs; translations you made yourself are kept. It only reads the hub's `index.json` and the files you confirm - nothing local is ever uploaded. Pack content holds only translated text and hashes (never the original text) and is licensed CC BY-NC-SA 4.0; see [translation-hub/README.md](translation-hub/README.md).
- **Settings in seven categories** (Esc > Options > Translation settings...): General, Display, Service, Packs, Translations, Advanced and About, with a search box and an in-game manual. The Quick setup can be run again from General. "Do-not-translate terms" keeps server or brand names in the original language (case-insensitive, whole-word).
- **Pre-translate all items** (Translation settings > Translations): translates every item name and description in the background, starting from the title screen. It needs the AI service and asks first when online translation is off. The confirmation screen lists the estimated number of sends and tokens and every warning; once started, progress shows in a screen corner and you can pause or stop it at any time.
- Under Translation settings > Translations you can export and import saved translations as JSON to merge a friend's translations locally while keeping your own, and clear the saved translations of the current language.

## In-game screenshots

### Chat translation

Player names in this screenshot are masked.

Chat can display the original message and its translation together for comparison.

![Bilingual server chat with player names masked](docs/images/promo/hypixel-chat-bilingual.png)

<details>
<summary>Choose a familiar language from the translation language list</summary>

![Translation language selection](docs/images/promo/language-selector.png)

</details>

## Direct downloads

Each JAR supports only the exact Minecraft version and loader in its filename.

[Download the all-versions ZIP with loader/version folders](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/NyanLex-1.0.0-all-versions.zip)


### Fabric

Fabric targets require matching Fabric Loader and Fabric API versions.

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.14.4 | 8 | [nyanlex-1.0.0-Fabric-1.14.4.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.14.4.jar) |
| 1.15.2 | 8 | [nyanlex-1.0.0-Fabric-1.15.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.15.2.jar) |
| 1.16.5 | 8 | [nyanlex-1.0.0-Fabric-1.16.5.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.16.5.jar) |
| 1.17.1 | 16 | [nyanlex-1.0.0-Fabric-1.17.1.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.17.1.jar) |
| 1.18.2 | 17 | [nyanlex-1.0.0-Fabric-1.18.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.18.2.jar) |
| 1.19.4 | 17 | [nyanlex-1.0.0-Fabric-1.19.4.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.19.4.jar) |
| 1.20.1 | 17 | [nyanlex-1.0.0-Fabric-1.20.1.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.20.1.jar) |
| 1.21.1 | 21 | [nyanlex-1.0.0-Fabric-1.21.1.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.21.1.jar) |
| 1.21.11 | 21 | [nyanlex-1.0.0-Fabric-1.21.11.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.21.11.jar) |
| 26.1.2 | 25 | [nyanlex-1.0.0-Fabric-26.1.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-26.1.2.jar) |
| 26.2 | 25 | [nyanlex-1.0.0-Fabric-26.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-26.2.jar) |
| 26.3 | 25 | [nyanlex-1.0.0-Fabric-26.3.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-26.3.jar) |

### NeoForge

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.20.1 | 17 | [nyanlex-1.0.0-NeoForge-1.20.1.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-1.20.1.jar) |
| 1.21.1 | 21 | [nyanlex-1.0.0-NeoForge-1.21.1.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-1.21.1.jar) |
| 26.2 | 25 | [nyanlex-1.0.0-NeoForge-26.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-26.2.jar) |
| 26.3 | 25 | [nyanlex-1.0.0-NeoForge-26.3.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-26.3.jar) |

### Forge

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.12.2 | 8 | [nyanlex-1.0.0-Forge-1.12.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Forge-1.12.2.jar) |
| 1.13.2 | 8 | [nyanlex-1.0.0-Forge-1.13.2.jar](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Forge-1.13.2.jar) |

## Installation

1. Download the exact JAR from the tables above.
2. Install the same Minecraft version of Fabric, NeoForge, or Forge.
3. Put the JAR in that instance's `mods` directory.
4. Start the game with the Java version shown in the table.

## Translation sources

| Source | API key | Notes |
| --- | --- | --- |
| Google | Not required | The only machine translation source. **Unofficial endpoint** that may be limited or stop working at any time. |
| OpenAI-compatible API | Depends on service; may be left empty for a local server | Works with Gemini, OpenAI and DeepSeek (preset buttons), or any OpenAI-compatible service such as OpenRouter, Ollama, LM Studio or one you host yourself. You can set the Base URL, the model, several API keys that are used in rotation, and a glossary, and choose whether machine translation (Google) fills in when the AI fails. When the key is left empty, no `Authorization` header is sent. |
| ChatGPT/Codex | ChatGPT sign-in | Available on every listed target; install Codex CLI first. Includes model, effort, and token controls. |

## Sharing translations

Use **Export translations** in Translation Settings. **1.0.5 supports automatic split exports and batch imports**: small exports produce one JSON file; larger exports produce `translations.part-0001.json`, `translations.part-0002.json`, and so on. Share the entire set. Your friend selects the same target language, then uses Ctrl/Shift to select multiple JSON files in **Import translations**. Import merges valid missing entries, keeps existing translations, and makes no translation requests. Files contain translation rows only, without API keys, login credentials, or settings.

Fabric 1.17.1+ and NeoForge share one compatible format. Fabric 1.14.4–1.16.5 and Forge 1.12.2–1.13.2 share the legacy format. Files cannot be imported across these two format families. Each part is limited to 32 MiB and 100,000 entries; this is **not a limit on the total export**, which splits automatically. Existing single-file exports remain compatible. For an older oversized JSON, re-export from the client holding the cached translations.

Batch imports process files in filename order; the first valid translation wins. A damaged, incompatible, or over-capacity file does not stop other files. The completion message reports added translations, successful/total files, and failures. Successful imports are not rolled back. Exports never overwrite existing files; choose another name if a destination already exists.

Legacy clients retain their 8,192-entry shared cache limit. A file exceeding the remaining capacity is rejected without replacing existing translations. Modern disk cache files retain up to 100,000 entries by default; overflow still evicts older entries under the existing cache policy. Splitting exports does not increase client cache capacity.


## Keyboard shortcuts

Fabric 1.17.1+ and NeoForge:

| Key | Action |
| --- | --- |
| `G` | Toggle original/translated display |
| `R` | Translate / retranslate the item under the pointer |
| `P` | Translate / retranslate visible text and tooltips on the current screen |
| Unbound | Open Translation Settings |

Legacy UI:

| Targets | Keys |
| --- | --- |
| Fabric 1.14.4-1.16.5 | `G` opens Translation Settings; `P` retranslates the current screen |
| Forge 1.12.2-1.13.2 | `G` opens Translation Settings; `H` enables/disables translation; `P` retranslates the current screen |

`P` captures currently visible text, including a hovered tooltip; it does not scan off-screen content or activate while typing. With machine translation, items and mod-screen text translate on demand (see Features above), so `R`/`P` are how you get those translations; with AI they translate automatically and `R`/`P` force a fresh translation. Completion time depends on the translation service.

If your keybinds look reset after upgrading: this release changes the mod id from `mctranslator` to `nyanlex`. The first launch automatically copies your old config file, translation caches, and any custom `options.txt` keybinds over to the new name (the old files are kept, not deleted), once.

## 1.0.0 highlights

- **Renamed to NyanLex Translator**: the package, mod id, config/cache filename prefix, and GitHub translation hub all moved to the new name. The first launch automatically copies your config, caches, and keybinds saved under any earlier name to the new name (originals are kept; existing new-named files are never overwritten).
- With machine translation, item and mod-screen translation is triggered on demand (`R`/`P`), so nothing you haven't looked at is sent ahead of time; with AI they translate automatically like chat. Chat and other live text keep translating automatically.
- Tooltips now cache per segment, cutting down on re-requesting an entire long tooltip for one changed line.
- Adds translation packs: ready-made translations prepared by the maintainers. There is no startup check; you find them from the last page of the Quick setup (shown only when packs for your installed mods exist) or with "Translation settings > Packs > Detect and download translation packs", and nothing downloads until you confirm. "Clear downloaded translation packs" removes them again.
- Rebuilds the settings into seven categories with search and an in-game manual, adds a Quick setup that opens by itself on the first start, and adds "Pre-translate all items".

## 1.0.6 highlights

- Three performance-only fixes aimed at occasional in-game hitches; translation output, cache files, and settings are unchanged:
  - Late chat translations that arrive as a batch now re-layout the chat box once instead of once per message.
  - Name-tag matching against the online player list is memoized and invalidated whenever the list refreshes.
  - Number-slot regular expressions used when restoring translation templates are precompiled and cached instead of being rebuilt on every cache hit.
- All 18 Minecraft/loader targets are updated together. Real-world hitch reduction still needs in-game comparison; this release does not claim to eliminate it.

## 1.0.5 highlights

- `P` retranslates visible current-screen text, including mod quest paragraphs and hovered tooltips.
- Adds translation-file export/import to share existing translations while keeping local entries.
- Removes repeated validation of modern in-memory cache hits; long-session stutter improvements still need in-game comparison.
- Includes all 18 Minecraft/loader targets, with 26.3 included in the all-versions ZIP.

## 1.0.4 highlights

- Fixes runaway CPU, memory, and disk I/O when opening inventories or containers after a long session. Modern targets now use a bounded append journal instead of sorting and rewriting the entire cache for each item translation on the render thread.
- Changes inventory, container, hotbar, and off-hand warming to a 350 ms delta scan. Legacy targets also use callback-free prefetching, preventing every slot from being resubmitted each tick or accumulating waiters.
- Bounds translation queues, executor work, in-flight requests, callbacks, retries, Codex state, and memory caches, and releases completed state so resource use does not grow with play time.
- Adds cache-hit fast paths and on-demand retry scans, reduces repeated player-name, regex, and context allocations, caps raw HTTP responses at 4 MiB on modern targets, and retains a streaming character cap on legacy targets.
- Ports the same fixes to all 16 Minecraft/loader targets ever offered by the project releases, with per-target builds and final-JAR regression checks.

## 1.0.3 highlights

- Keeps the stable 1.0.2 translation architecture.
- Adds ChatGPT/Codex sign-in, model selection, and token display.
- Speeds up Codex by disabling unused tools and summaries, avoiding cleanup waits, and using the advertised priority tier.
- Masks player names only from TAB and removes name guessing from templates and caches.
- Fixes `Bloom Boat with Chest` being sent as `Bloom Boat with {value}`.

## Credits

The icon was made with AI assistance.

## Source and issues

See [PACKAGING.md](PACKAGING.md) for build commands and the release folder layout. Report problems through [GitHub Issues](https://github.com/DragonMeow1012/NyanLex/issues).
