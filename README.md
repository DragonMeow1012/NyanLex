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
- The legacy targets (Fabric 1.14.4-1.16.5 and Forge 1.12.2-1.13.2) have a simpler interface: a short Quick setup, the on-the-spot confirmation window and a categorized settings screen, but no translation packs and no full-content warmup.
- Quest and task-book screens in modpacks get extra optimization (long paragraphs, colored text, tooltips).
- This is a client-side mod; it does not modify servers and does not send chat for you.

## Features

- Translates chat, item names, tooltips, scoreboards, name tags, boss bars, titles, action bars, books, and mod screens.
- Each surface can show original text, translated text, or both.
- Supports Google machine translation (unofficial endpoint) and OpenAI-compatible APIs such as Gemini, OpenAI, DeepSeek, OpenRouter, Ollama and LM Studio.
- Every supported target includes ChatGPT/Codex sign-in, model and reasoning-effort selection, and session token usage; the default is `gpt-5.6-terra` / `medium`.
- Async batching, priority queues, disk caches, and failure backoff reduce main-thread work and duplicate requests. The send interval and collection window both default to 5 seconds, with 11 settings: Off or 1–10 seconds.
- Player names are masked from the TAB list. Modern targets skip labels consisting of known mod, shader or technical names and their versions; existing translations still take precedence.
- **Everything except chat follows the translation service you pick for it**: with AI it translates automatically like chat; with machine translation (Google) only chat translates on its own and the rest is on demand, so joining a server never floods it with text: press `R` on the item under your cursor, `P` with a screen open to translate that screen, or `P` in the world to translate the scoreboard, name tags, boss bars, titles and action bar you can see (sent as one batch). Anything already translated is shown straight from your saved translations either way (a text the AI already translated first, then translation packs, then saved machine translations); with AI, `R`/`P` force a fresh translation.
- **Segmented tooltip cache**: long tooltips (title plus multi-line body) cache and restore per segment, so only the segment that actually changed needs a fresh request.
- **Translation packs** (ready-made AI translations prepared by the maintainers and hosted on GitHub): the mod does not check for them at startup. There are two ways to get them: the last page of the Quick setup detects them automatically (it only appears when packs for your installed mods are found), and the "Detect and download translation packs" button under Translation settings > Packs. Both list each pack with its size and the expected total, download only after you confirm, and merge the files into your saved translations. "Clear downloaded translation packs" in the same category removes only what came from packs; translations you made yourself are kept. It only reads the hub's `index.json` and the files you confirm - nothing local is ever uploaded. Pack content holds only translated text and hashes (never the original text) and is licensed CC BY-NC-SA 4.0; see [translation-hub/README.md](translation-hub/README.md).
- **Settings in seven categories** (Esc > Options > Translation settings...): General, Display, Service, Packs, Translations, Advanced and About, with a search box and an in-game manual. The Quick setup can be run again from General. "Do-not-translate terms" keeps server or brand names in the original language (case-insensitive, whole-word).
- **Full-content warmup** (Translation settings > Translations): expand Categories to choose Item names and descriptions and Screen text, both selected by default. Quest titles and descriptions belong to Screen text. Warmup runs only when you press Start or Continue, skips existing translations, and can be paused or stopped. It requires AI translation and asks first if online translation is off. Some screen content loads only after entering a world; enter the world before starting warmup for that content.
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

| Source | API key / sign-in | Notes |
| --- | --- | --- |
| Google machine translation | Not required | Uses an unofficial web endpoint that may be limited or stop working. Retains the 429 pause and backoff protection. |
| Gemini | Your API key | Includes a preset button; connects through the OpenAI-compatible interface with a configurable model. |
| OpenAI | Your API key | Includes a preset button, configurable model and service URL. |
| DeepSeek | Your API key | Includes a preset button, configurable model and service URL. |
| OpenRouter | Your API key | Enter its OpenAI-compatible service URL and model in the AI settings. |
| Ollama / LM Studio | Depends on your local server; may be empty | Connects to an OpenAI-compatible server you run. You provide the model. |
| Other OpenAI-compatible services | Depends on the service | Custom Base URL, model, rotating API keys and glossary. An empty key sends no `Authorization` header. |
| ChatGPT/Codex | ChatGPT sign-in | Available on every listed target; install Codex CLI first. Includes model, reasoning effort and token controls, and uses your account's Codex quota. |

You can choose whether Google machine translation fills in after an AI failure. With this fallback enabled, the text may also be sent to Google.

Local caches, imported translations, downloaded packs and the built-in glossary provide existing translations without a new translation request. See the [translation hub documentation](translation-hub/README.md) for pack sources, format and licensing.

## Sharing translations

Use **Export translations** in Translation Settings. **Supports automatic split exports and batch imports**: small exports produce one JSON file; larger exports produce `translations.part-0001.json`, `translations.part-0002.json`, and so on. Share the entire set. Your friend selects the same target language, then uses Ctrl/Shift to select multiple JSON files in **Import translations**. Import merges valid missing entries, keeps existing translations, and makes no translation requests. Files contain translation rows only, without API keys, login credentials, or settings.

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

`P` captures currently visible text, including a hovered tooltip; with no screen open it captures the HUD text you can see (scoreboard rows, boss bars, titles, the action bar, name tags). It does not scan off-screen content or activate while typing. With machine translation, everything except chat translates on demand (see Features above), so `R`/`P` are how you get those translations; with AI they translate automatically and `R`/`P` force a fresh translation. Completion time depends on the translation service.

If your keybinds look reset after upgrading: this release changes the mod id from `mctranslator` to `nyanlex`. The first launch automatically copies your old config file, translation caches, and any custom `options.txt` keybinds over to the new name (the old files are kept, not deleted), once. If a translation cache already exists under the new name, the old one is merged into it instead (rows already in the new file win), also once, so a cache cleared or deleted afterwards does not come back.

## 1.0.0 highlights

- **Renamed to NyanLex Translator**: the package, mod id, config/cache filename prefix, and GitHub translation hub all moved to the new name. The first launch automatically copies your config, caches, and keybinds saved under any earlier name to the new name (originals are kept; existing new-named files are never overwritten).
- With machine translation, everything except chat is triggered on demand (`R`/`P`), so nothing you haven't looked at is sent ahead of time; with AI it translates automatically like chat. A text the AI has already translated is shown first even when machine translation is selected.
- Tooltips now cache per segment, cutting down on re-requesting an entire long tooltip for one changed line.
- Adds translation packs: ready-made translations prepared by the maintainers. There is no startup check; you find them from the last page of the Quick setup (shown only when packs for your installed mods exist) or with "Translation settings > Packs > Detect and download translation packs", and nothing downloads until you confirm. "Clear downloaded translation packs" removes them again.
- Rebuilds the settings into seven categories with search and an in-game manual, adds a Quick setup that opens by itself on the first start, and adds "Full-content warmup" with selectable categories.

## Source and issues

See [PACKAGING.md](PACKAGING.md) for build commands and the release folder layout. Report problems through [GitHub Issues](https://github.com/DragonMeow1012/NyanLex/issues).
