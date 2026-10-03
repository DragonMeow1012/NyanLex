<p align="center">
  <img src="docs/brand/nyanlex-banner.svg" alt="NyanLex Translator — Read Minecraft chat, items and screens in your language." width="960">
</p>

<p align="center"><strong>Understand your Minecraft world. Join the conversation.</strong></p>

<p align="center">
  <a href="https://github.com/DragonMeow1012/NyanLex/releases/tag/v1.0.0"><img src="https://img.shields.io/badge/Release-1.0.0-8b7fd6?style=flat-square" alt="Release 1.0.0"></a>
  <img src="https://img.shields.io/badge/Loaders-Fabric%20%7C%20NeoForge%20%7C%20Forge-52658f?style=flat-square" alt="Fabric, NeoForge and Forge">
  <img src="https://img.shields.io/badge/Install-Client%20only-478978?style=flat-square" alt="Client-side only">
  <a href="LICENSE"><img src="https://img.shields.io/badge/Code-MIT-c09055?style=flat-square" alt="Code license: MIT"></a>
</p>

<p align="center"><b>English</b> · <a href="README.zh-TW.md">繁體中文</a></p>
<p align="center"><a href="#features">Features</a> &nbsp;·&nbsp; <a href="#direct-downloads">Download</a> &nbsp;·&nbsp; <a href="#in-game-screenshots">See it in game</a> &nbsp;·&nbsp; <a href="#translation-sources">Translation sources</a> &nbsp;·&nbsp; <a href="#keyboard-shortcuts">Shortcuts</a> &nbsp;·&nbsp; <a href="#privacy">Privacy</a></p>

> **Online translation starts off.** When enabled, selected text goes to your chosen provider; chat can include private messages. Saved translations remain available offline. [Read the privacy details](#privacy).

## Features

<table>
  <tr>
    <td width="50%" valign="top"><h3>✍️ Reply in your own language</h3><p>The floating composer starts at the bottom right and can be dragged elsewhere. Search for a target language, translate your draft, review it in the chat bar, then send it yourself. Closing chat with Esc keeps your unfinished draft for the next time you open chat during the same game session.</p></td>
    <td width="50%" valign="top"><h3>💬 Keep up with the conversation</h3><p>Read chat with the original and translation together. Follow your teammates while keeping the original wording close at hand.</p></td>
  </tr>
  <tr>
    <td width="50%" valign="top"><h3>📖 Follow every clue</h3><p>Discover item abilities, quest stories, books, and interfaces in a familiar language, so you can focus on exploring.</p></td>
    <td width="50%" valign="top"><h3>🔥 Get ready before you explore</h3><p>Warm up the content you want to read, with categories you can choose. Saved translations are ready to use when the adventure begins.</p></td>
  </tr>
  <tr>
    <td width="50%" valign="top"><h3>🌐 Find your translation style</h3><p>Choose Google or an AI service to suit your needs, or connect a local model. Make the experience your own.</p></td>
    <td width="50%" valign="top"><h3>💾 Share a good translation</h3><p>Get started with translation packs or share your own with friends. Keep the translations you have already made, ready for next time.</p></td>
  </tr>
</table>

The mod UI follows Minecraft's language and supports English, Japanese, Traditional Chinese and Simplified Chinese. Other client languages use the English UI. This does not restrict the available translation target languages. Previously saved composer positions are preserved.

## In-game screenshots

### Item tooltips, before and after

Read the item description while keeping its numbers and text colors. Click either image to view it at full size.

<table>
  <tr><th width="50%">Original</th><th width="50%">Traditional Chinese translation</th></tr>
  <tr>
    <td valign="top"><img src="docs/images/promo/hypixel-potion-en.png" alt="Original English item tooltip" width="380"></td>
    <td valign="top"><img src="docs/images/promo/hypixel-potion-zh-TW.png" alt="The same item translated into Traditional Chinese" width="380"></td>
  </tr>
</table>

### Original chat and translation, together

<p align="center"><img src="docs/images/promo/hypixel-chat-bilingual.png" alt="Bilingual chat with player names pixelated" width="660"></p>

Actual gameplay screenshots. Player names are pixelated; translation text is unchanged. Translation quality and response time depend on your provider.

<details>
<summary>See more: choosing your translation language</summary>

![Translation language selection](docs/images/promo/language-selector.png)

</details>

## Get started

1. **Pick your build.** [Download](#direct-downloads) the single JAR matching your Minecraft version and loader, then put it in that instance's `mods` folder. Fabric also needs the matching Fabric API.
2. **Choose your language and provider.** Start the game and use Quick setup. Online translation turns on only after you confirm your choice.
3. **Start reading.** On modern targets, press <kbd>R</kbd> over an item, <kbd>P</kbd> for visible screen or HUD text, and <kbd>G</kbd> to switch between original and translated display.

| Mode | Chat | Items, screens and other surfaces |
| --- | --- | --- |
| Google machine translation | Automatic | On demand with `R` / `P` |
| AI translation | Automatic | Automatic; `R` / `P` force a fresh translation |
| Saved translations | Display immediately | Display immediately, without another request |

Choose original text, translation, or both for each surface. Existing AI translations take priority, followed by translation packs and saved machine translations. Legacy controls differ; see [keyboard shortcuts](#keyboard-shortcuts).

## Direct downloads

Each JAR is for the exact Minecraft version and loader in its filename. **Install one matching JAR, not the whole bundle.**

| Fabric · 12 builds | NeoForge · 4 builds | Forge · 2 builds |
| :---: | :---: | :---: |
| Selected versions from 1.14.4 to 26.3 | 1.20.1, 1.21.1, 26.2, 26.3 | 1.12.2, 1.13.2 |
| [Fabric ZIP](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/NyanLex-1.0.0-Fabric.zip) | [NeoForge ZIP](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/NyanLex-1.0.0-NeoForge.zip) | [Forge ZIP](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/NyanLex-1.0.0-Forge.zip) |

[Release page](https://github.com/DragonMeow1012/NyanLex/releases/tag/v1.0.0) · [All-versions ZIP](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/NyanLex-1.0.0-all-versions.zip) · [SHA-256 checksums](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/SHA256SUMS.txt)

Expand a loader to find your build:

<details>
<summary><b>Fabric · 12 builds — individual JARs and Java requirements</b></summary>

Fabric targets require matching Fabric Loader and Fabric API versions.

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.14.4 | 8 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.14.4.jar) |
| 1.15.2 | 8 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.15.2.jar) |
| 1.16.5 | 8 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.16.5.jar) |
| 1.17.1 | 16 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.17.1.jar) |
| 1.18.2 | 17 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.18.2.jar) |
| 1.19.4 | 17 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.19.4.jar) |
| 1.20.1 | 17 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.20.1.jar) |
| 1.21.1 | 21 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.21.1.jar) |
| 1.21.11 | 21 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-1.21.11.jar) |
| 26.1.2 | 25 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-26.1.2.jar) |
| 26.2 | 25 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-26.2.jar) |
| 26.3 | 25 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Fabric-26.3.jar) |

</details>

<details>
<summary><b>NeoForge · 4 builds — individual JARs and Java requirements</b></summary>

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.20.1 | 17 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-1.20.1.jar) |
| 1.21.1 | 21 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-1.21.1.jar) |
| 26.2 | 25 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-26.2.jar) |
| 26.3 | 25 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-NeoForge-26.3.jar) |

</details>

<details>
<summary><b>Forge · 2 builds — individual JARs and Java requirements</b></summary>

| Minecraft | Java | Download |
| --- | ---: | --- |
| 1.12.2 | 8 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Forge-1.12.2.jar) |
| 1.13.2 | 8 | [Download JAR](https://github.com/DragonMeow1012/NyanLex/releases/download/v1.0.0/nyanlex-1.0.0-Forge-1.13.2.jar) |

</details>

## Compatibility

- Fabric, NeoForge, and Forge targets are listed in [Direct downloads](#direct-downloads) (Minecraft 1.12.2 to 26.3).
- The legacy targets (Fabric 1.14.4-1.16.5 and Forge 1.12.2-1.13.2) have a simpler interface: a short Quick setup, the on-the-spot confirmation window and a categorized settings screen, but no translation packs and no full-content warmup.
- Quest and task-book screens in modpacks get extra optimization (long paragraphs, colored text, tooltips).
- This is a client-side mod; it does not modify servers and does not send chat for you.

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

## Full-content warmup

Open **Translation settings > Translations**, then expand **Categories** to choose **Item names and descriptions** and **Screen text**, both selected by default. Quest titles and descriptions belong to Screen text. Warmup runs only when you press Start or Continue, skips existing translations, and can be paused or stopped. It requires AI translation and asks first if online translation is off. Some screen content loads only after entering a world; enter the world before starting warmup for that content.

You can continue manually after a rate-limit pause; switching models checks the new model state. Google 429 pause and backoff protection remains in place.

## Translation packs and sharing

Translation packs contain ready-made AI translations prepared by the maintainers and hosted on GitHub. The mod does not check for them at startup. There are two ways to get them: the last page of the Quick setup detects them automatically (it only appears when packs for your installed mods are found), and the "Detect and download translation packs" button under Translation settings > Packs. Both list each pack with its size and the expected total, download only after you confirm, and merge the files into your saved translations. "Clear downloaded translation packs" in the same category removes only what came from packs; translations you made yourself are kept. It only reads the hub's `index.json` and the files you confirm - nothing local is ever uploaded. Pack content holds only translated text and hashes (never the original text) and is licensed CC BY-NC-SA 4.0; see [translation-hub/README.md](translation-hub/README.md).

### Sharing translations

Use **Export translations** in Translation Settings. **Supports automatic split exports and batch imports**: small exports produce one JSON file; larger exports produce `translations.part-0001.json`, `translations.part-0002.json`, and so on. Share the entire set. Your friend selects the same target language, then uses Ctrl/Shift to select multiple JSON files in **Import translations**. Import merges valid missing entries, keeps existing translations, and makes no translation requests. Files contain translation rows only, without API keys, login credentials, or settings.

<details>
<summary>Format compatibility, split files and import limits</summary>

Fabric 1.17.1+ and NeoForge share one compatible format. Fabric 1.14.4–1.16.5 and Forge 1.12.2–1.13.2 share the legacy format. Files cannot be imported across these two format families. Each part is limited to 32 MiB and 100,000 entries; this is **not a limit on the total export**, which splits automatically. Existing single-file exports remain compatible. For an older oversized JSON, re-export from the client holding the cached translations.

Batch imports process files in filename order; the first valid translation wins. A damaged, incompatible, or over-capacity file does not stop other files. The completion message reports added translations, successful/total files, and failures. Successful imports are not rolled back. Exports never overwrite existing files; choose another name if a destination already exists.

Legacy clients retain their 8,192-entry shared cache limit. A file exceeding the remaining capacity is rejected without replacing existing translations. Modern disk cache files retain up to 100,000 entries by default; overflow still evicts older entries under the existing cache policy. Splitting exports does not increase client cache capacity.

</details>

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

`P` captures currently visible text, including a hovered tooltip; with no screen open it captures the HUD text you can see (scoreboard rows, boss bars, titles, the action bar, name tags). It does not scan off-screen content or activate while typing. With machine translation, everything except chat translates on demand (see Get started above), so `R`/`P` are how you get those translations; with AI they translate automatically and `R`/`P` force a fresh translation. Completion time depends on the translation service.

<details>
<summary>Upgrading from an earlier project name</summary>

If your keybinds look reset after upgrading: this release changes the mod id from `mctranslator` to `nyanlex`. The first launch automatically copies your old config file, translation caches, and any custom `options.txt` keybinds over to the new name (the old files are kept, not deleted), once. If a translation cache already exists under the new name, the old one is merged into it instead (rows already in the new file win), also once, so a cache cleared or deleted afterwards does not come back.

</details>

<details>
<summary>More settings and translation behavior</summary>

Every supported target includes ChatGPT/Codex sign-in, model and reasoning-effort selection, and session token usage; the default is `gpt-5.6-terra` / `medium`.

Async batching, priority queues, disk caches, and failure backoff reduce main-thread work and duplicate requests. The send interval and collection window both default to 5 seconds, with 11 settings: Off or 1–10 seconds.

Player names are masked from the TAB list. Modern targets skip labels consisting of known mod, shader or technical names and their versions; existing translations still take precedence.

**Segmented tooltip cache**: long tooltips (title plus multi-line body) cache and restore per segment, so only the segment that actually changed needs a fresh request.

**Settings in seven categories** (Esc > Options > Translation settings...): General, Display, Service, Packs, Translations, Advanced and About, with a search box and an in-game manual. The Quick setup can be run again from General. "Do-not-translate terms" keeps server or brand names in the original language (case-insensitive, whole-word).

</details>

<details>
<summary>Using chat input translation</summary>

Enable it in **General**, then open chat. Write your draft, choose a target language (English by default), and click **Translate & fill**. Review or edit the result in the chat bar, then press Enter to send. Drag the floating header to move it; its position is remembered.

</details>

## Privacy

- **Online translation is off on a new install.** While it is off, no translation service receives any of your text.
- You can turn it on in three ways: from the Quick setup that opens by itself the first time you reach the title screen (choose machine translation, AI translation, or "Not now"; nothing you choose is applied, and nothing is sent, until you press Done); by pressing the translate-item key (default `R`) or translate-screen key (default `P`) while it is off, which first opens a confirmation window and only sends after you press "Start translating"; or in Translation settings > General ("Online translation").
- Things that work without turning it on, and send nothing: translations already in your local cache, translation packs you downloaded, the built-in glossary, and the vanilla text from the game's own language files.
- Outgoing drafts are translated only when you click **Translate & fill** or press Enter in the composer. Unfinished drafts are kept in memory when you close chat, but not after exiting the game. Drafts are not written to translation caches or included in translation exports. You still review and send the final chat message yourself.
- Once it is on, the text of the surfaces you set to translate (item descriptions, screens, and so on) is sent. If chat translation is enabled, chat messages are sent as well, **including private messages**.
- The text goes to the translation service you choose:
  - **Machine translation (Google, no key)** uses an **unofficial** web endpoint that may be rate-limited or stop working at any time.
  - **AI engines** (an OpenAI-compatible service such as Gemini, OpenAI or DeepSeek, or ChatGPT/Codex sign-in) also need your own key or sign-in, and go only to the service you configure. Signing in with ChatGPT uses your account's Codex quota.
- **API keys are stored only in the config file on your machine**, are sent only to the provider you chose, are masked in the settings screen, and are never written to logs or debug dumps.
- Users upgrading from an earlier version keep their existing settings: if you were already translating, online translation stays on.
- **Translation packs are download-only.** Finding and downloading them only reads public files from GitHub (`index.json` plus the files you confirm); no local text or installed-mod list is uploaded, and translation files are downloaded only after confirmation (discovery first reads the public index).
- Player names from the TAB list are masked locally before sending; other server text may still contain user-provided content.

## About NyanLex

[Read the release notes](https://github.com/DragonMeow1012/NyanLex/releases/tag/v1.0.0).

See [PACKAGING.md](PACKAGING.md) for build commands and the release folder layout. Report problems through [GitHub Issues](https://github.com/DragonMeow1012/NyanLex/issues).

Code is licensed under [MIT](LICENSE). Translation data has separate source and license notices in the [translation hub](translation-hub/README.md).

Unofficial project; not affiliated with, endorsed by, or sponsored by Mojang, Microsoft, Hypixel, or other mod authors. Minecraft is a trademark of Mojang AB / Microsoft.
