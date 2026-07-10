# Minecraft Translator

Client-side Minecraft translation mod for Traditional/Simplified Chinese. It
supports Google Translate and OpenAI-compatible AI endpoints, preserves Minecraft
text styling, and never modifies server data or messages sent by the player.

## Supported builds

| Loader | Minecraft |
| --- | --- |
| Fabric | 1.20.1, 1.21.1, 26.2 |
| NeoForge/Forge-compatible tree | 1.20.1, 1.21.1, 26.2 |

Each version is an independent Gradle project. The Minecraft-free core under
`src/main/java/com/dragonmeow/mctranslator/{cache,config,service,style,translate}` is
canonical and is mirrored into every loader tree by `sync-core.ps1`.

## Features

- Per-surface modes: original, translation, or original + translation.
- Independent Google/AI engine choice for chat, tooltips, scoreboard, name tags,
  boss bars, titles, action bar, books, and custom GUI text.
- Non-blocking render path with settle-window batching and global single-flight
  request deduplication.
- Player-name masking: listed real player IDs never leave the client and are
  restored verbatim after translation.
- Actual player-entity name tags bypass translation as complete components. If a
  stale cache once mistranslated an ID, the whole vanilla tag still replaces it.
- Verified style-run markers preserve Minecraft colours, formatting, click, hover,
  and insertion events without character-position projection.
- Per-line numeric/time/icon/player/server templates: changing live values reuses one
  canonical cache key while semantic labels and locations remain independently translated.
- Animated-text churn detection, failure backoff, AI rate-limit fallback, and
  provisional Google results that AI can replace after recovery.
- AI-first display policy: an existing AI translation wins; otherwise an existing
  Google result displays immediately and is marked for one AI supplement.
- Versioned compact disk cache with atomic replacement.
- Fabric 26.2 request-debug HUD showing only strings that actually reached a
  backend.

## Request pipeline

```text
Minecraft surface
  -> TranslationService (mode, language filter, player-name mask)
  -> TranslationTemplate.Snapshot
       deterministic slots: numbers, clocks, durations, icons, URLs, ranks,
                            player event IDs, and labelled server instance IDs
  -> TranslationCache
       memory/disk lookup -> settle queue -> single-flight -> backend
  -> placeholder restore -> validation -> style-independent cache copy
  -> Minecraft style renderer
```

One immutable template snapshot follows a request from enqueue through backend
dispatch, restore, and persistence. Dynamic, layout, and style tokens must all return
with the exact same numbered multiset; complete pairs may move for target-language
grammar, but missing, duplicated, or foreign tokens are rejected before persistence.

Global and per-item retranslation are hard invalidations. They remove canonical,
styled, and de-styled entries, detach the old single-flight request, and increment a
per-key revision. A response started before the deletion is therefore unable to
write the old translation back after the new request begins.

### Volatile-value behaviour

- `Mana Cost: 99` is requested as `Mana Cost: ⟦MT0⟧`. Later values such as 124
  restore from the same translation without another request.
- clock/style/icon-only text such as `§711:20pm §b☽§v` contains no translatable
  content and never reaches a backend.
- `SkyBlock Hub #11`, `Players: 48/60`, `Server: mega33A`, and `Click` are four
  independent line-cache entries. A later card with `#13`, `44/60`, or
  `Server: alphaShard` restores the current values without another request. Only a
  genuinely unseen label/format is submitted.
- locations, boss names, skills, quest states, and statuses are semantic text. Each
  distinct phrase is translated once and then retained permanently; the system does
  not guess that changing words are safe verbatim slots.
- hard newlines and blank section rows are an immutable UI skeleton. Tooltip, book,
  FTB, and screen-scan content is translated per original row; the full item/page may
  still be supplied as read-only AI context for coherent wording.

## Cache schema

Disk caches use schema 3 JSON Lines. The first physical line is the schema header;
every following physical line is exactly one translation:

```json
{"schema":3}
{"key":"Mana Cost: ⟦MT0⟧","translation":"魔力消耗：⟦MT0⟧"}
{"key":"Defense: ⟦MT0⟧","translation":"防禦：⟦MT0⟧","provisional":true}
```

The file is a canonical snapshot: each key appears once, batch updates write once,
and a temporary file is atomically moved into place. Embedded line breaks are JSON
escaped, so they cannot split an entry. Older schemas are discarded instead of
migrated.

The old startup crawler that translated every registered item has been removed.
Opening a container still queues the names on the currently visible page; it never
crawls unseen pages or the complete item registry. The local player's actual hotbar,
backpack, equipped armour, and off-hand names are also queued once and then only when
a newly acquired name appears. Full item lore remains hover-driven to avoid buying an
entire inventory of descriptions in the background.

AI requests use a compact Minecraft/mod-localization instruction instead of sending
a built-in glossary on every call. The prompt asks for established Minecraft wording
(for example `Enchant → 附魔` for Traditional Chinese). User-pinned term overrides are
included only when the current text contains that term, and tooltip reference context
is capped at 24 lines / 2,000 characters.

## Fabric 26.2 request debug HUD

Open **翻譯設定** and enable **翻譯請求偵錯懸浮窗**. The overlay shows recent
canonical strings that crossed the final backend boundary:

- `…` in flight
- `✓` completed
- `↪` AI path used Google fallback
- `＝` a short NPC/proper name echoed unchanged three times and is now durably kept original
- `✗` failed

Cache hits, duplicate callers, filtered clocks, and learned no-request locations do
not appear. The trace is bounded to 80 entries; the HUD shows the newest 12 and
completed rows expire automatically. Debug-HUD text bypasses translation completely;
it cannot recursively create new requests from its own `[AI ...]` / `[Google ...]`
rows. Each row pairs the exact canonical backend input and output as
`原文: … -> 翻譯: …`; pending and failed rows explicitly say `等待中` or `失敗`.

Any semantic template that produces three consecutive unusable content responses is
stored as a durable keep-original decision. Future lookups, including after a restart,
make no request. HTTP/429/transport failures never count toward this threshold; manual
or global retranslation removes the decision.

## Building and testing

Run the canonical Minecraft-free test suite from the repository root:

```powershell
.\.gradle-local\gradle-8.10\bin\gradle.bat test --no-daemon
```

After a core edit:

```powershell
powershell -ExecutionPolicy Bypass -File .\sync-core.ps1
```

See `PACKAGING.md` for all six Java/Gradle commands and the 1.0.2 output layout.

## Design constraints

- The render thread performs lookup/enqueue only; HTTP runs on background workers.
- Translation output is rejected if it is empty, an identity echo, likely
  mojibake, half-transliterated poison, or loses a protected player name.
- Hot memory/request/debug tables are bounded. Per-language disk translations are
  deliberately permanent and unbounded until the user explicitly clears that language.
- GT stand-ins share one semantic AI-supplement family across player IDs, live values and
  colour variants. A family retries at most three times per session; manual retranslation
  resets it, and a provisional result can never overwrite a final AI translation.
- Fixed HUD column gaps and dynamic/style tokens are validated as one protocol skeleton;
  a provider response that moves a value across a column or colour segment is rejected.
- Loader glue owns Minecraft API/version differences. Core packages contain no
  Minecraft classes and are covered by inline-fake tests.

## Known limitations

- The free Google endpoint is unofficial and may rate-limit or change.
- A newly encountered semantic location or proper name still needs one request;
  subsequent appearances reuse its per-language cache entry.
- If a provider damages a style/layout/value marker, the exact original component is
  shown instead of guessing colours or positions.
- API keys are stored in the local mod config; protect that file like any credential.
