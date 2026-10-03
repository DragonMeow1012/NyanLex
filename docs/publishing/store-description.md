# NyanLex Translator

Understand your Minecraft world. Join the conversation in your own language.

## Data and privacy

**Online translation is off on a new installation.** Enable it through Quick setup, the settings, or a translation confirmation before text is sent. Existing local translations work while it is off.

When enabled, text from the surfaces you choose is sent to the translation provider you select. Chat translation can include private messages. TAB-list player names are masked locally; other text may still include personal information. Google machine translation uses an unofficial endpoint and can be rate-limited or become unavailable. AI translation connects to your configured service; optional Google fallback can also send a failed AI request's text to Google. API keys are stored in your local configuration and masked in the interface.

Chat composer drafts are sent for translation only when you submit them in the panel. They are not stored in translation caches or exports. The translated text is only inserted into the chat bar; you decide when to send it.

Translation packs are download-only. Pack discovery reads a public GitHub index to show available packs; the selected translation files are downloaded only after confirmation. The mod does not upload your translations or installed-mod list. Exported translations are files you choose to share yourself.

## Features

- Chat input translation: write in a draggable panel that remembers its position, translate into your chosen language (English by default), and fill the normal chat bar. Review the result before pressing Enter to send. Disabled by default; uses your selected chat translation service.
- Translate chat, item names, tooltips, screen text, books and supported HUD text. Choose original text, translation or both.
- Reuse local translations and downloaded packs instead of requesting the same text repeatedly, including after an AI provider or model change.
- Select Google machine translation, Gemini, OpenAI, DeepSeek, an OpenAI-compatible service such as OpenRouter, Ollama or LM Studio, or ChatGPT/Codex sign-in. API services may require your own key and incur provider charges. ChatGPT sign-in requires a separately installed Codex CLI and uses your account's Codex quota.
- On modern targets, AI translates enabled surfaces automatically. With Google, chat is automatic and other surfaces are translated on demand: `R` for the hovered item and `P` for visible screen or HUD text. Existing translations remain available in either mode.
- Full-content warmup on modern targets: expand Categories to choose item names/descriptions and screen text, selected by default. Quest titles and descriptions are screen text. Start or continue manually; cached content is skipped. Some content loads only after entering a world.
- Pause, continue or stop warmup. After switching an AI model, Continue checks the new model's state. Google 429 backoff remains active.
- Export/import translation files, protect terms from translation, and adjust batching. Send interval and collection window default to 5 seconds, with Off and 1–10 second settings.

## Installation and supported versions

Install on the **client only**, using the JAR matching your exact game version and loader. Fabric builds also require the matching Fabric API. A server installation is not needed.

| Loader | Minecraft versions |
| --- | --- |
| Fabric | 1.14.4, 1.15.2, 1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.21.1, 1.21.11, 26.1.2, 26.2, 26.3 |
| NeoForge | 1.20.1, 1.21.1, 26.2, 26.3 |
| Forge | 1.12.2, 1.13.2 |

Legacy targets (Fabric 1.14.4–1.16.5 and Forge 1.12.2–1.13.2) provide a simpler settings interface and do not include translation packs or full-content warmup. Their shortcuts differ; see the [README](https://github.com/DragonMeow1012/NyanLex#keyboard-shortcuts). Translation quality and response time depend on the selected provider; a successful build does not mean every mod screen has been tested.

## Screenshots

Real gameplay screenshots captured in the Better MC modpack. Translation text is unchanged.

![Chat input translation](https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/docs/images/promo/better-mc-chat-composer.png)

![Translated item tooltip](https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/docs/images/promo/better-mc-item-translated.png)

![Original chat and translated text](https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/docs/images/promo/better-mc-bilingual-chat.png)

![Translation settings](https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/docs/images/promo/better-mc-translation-settings.png)

## Project information

AI tools assisted with code, translations, documentation and the project icon. Optional AI functionality connects to local or online language models. The Codex option launches a locally installed Codex process; the mod does not install it for you.

Unofficial project; not affiliated with or endorsed by Mojang, Microsoft or other mod authors. Code is MIT-licensed. Optional translation packs have separate licenses and per-pack attribution described in the [translation hub](https://github.com/DragonMeow1012/NyanLex/tree/main/translation-hub).

[Source and documentation](https://github.com/DragonMeow1012/NyanLex) · [Report an issue](https://github.com/DragonMeow1012/NyanLex/issues)
