# Minecraft Translator (mctranslator)

A **client-side Fabric mod for Minecraft 1.21.x** that translates **item names &
lore** and **live chat** into **Traditional Chinese (`zh-TW`)** using the free
Google Translate endpoint.

> Client-only: it changes what *you* see. It does not modify the server, other
> players' clients, or anything you send.

---

## Features

- Translate incoming **chat / system messages**.
- Translate **item names and lore** (the hover tooltip — line 0 is the name, the
  rest is lore/stats).
- Two display modes, switchable in-game:
  - **REPLACE** — original text is replaced by the translation.
  - **PARALLEL** — original text is kept, translation appended (`原文 » 譯文`).
- **Colour / formatting preservation.** In PARALLEL mode the original is kept
  byte-for-byte, so multi-colour / gradient / "rainbow" (色彩跑馬燈) text is
  preserved exactly. The colour sweep of the original is also **remembered and
  re-applied across the translated text** (a 4-colour gradient is stretched over
  the translation), and bold/italic/underline/strikethrough/obfuscated carry over
  when the whole original shares them.
- **Live chat update** (default): chat shows the **original immediately**, then the
  line is **replaced in place** with the translation once the background request
  returns — no freeze while waiting. Toggle with `liveChatUpdate`.
- **One-key toggle** for translation on/off, effective immediately.
- Smart filtering: skips blank text, pure numbers (stats/durability), and text
  that is already Chinese.
- Two-tier cache: in-memory LRU + a **disk cache** that is **cleared on every game
  start** (so translations don't accumulate across sessions, but LRU-evicted
  entries are still recovered within a session). Plus a failure backoff to avoid
  hammering / 429-banning the free endpoint.

### Default key binds

| Key | Action |
| --- | --- |
| `\` (backslash) | **Toggle translation on/off** |
| *(unbound)* | Toggle display mode (REPLACE / PARALLEL) — set a key in Controls |

Configurable in *Options → Controls → Minecraft 翻譯器*.

---

## Architecture

A **Minecraft-free core** holds all the logic (so it is unit-testable with inline
mocks), and a thin **Fabric glue layer** handles `Text` ↔ `String` conversion.
Almost everything uses Fabric API **events**; the only Mixin is a tiny `ChatHud`
**accessor** needed to replace an already-displayed chat line (live update).

```
com.dragonmeow.mctranslator
├── config/        TranslatorConfig, DisplayMode               (Gson, no MC)
├── translate/     Translator, GoogleFreeTranslator,
│                  HttpTransport, GoogleResponseParser, TextFilter   (no MC)
├── cache/         TranslationCache (LRU + async + backoff),
│                  PersistentStore, FileStore (disk tier)      (no MC)
├── style/         ColorProfile, StyledRun, StyleMapper        (no MC)
│                  ← captures original colours, sweeps them over the translation
├── service/       TranslationService, TranslationDecision     (no MC)
│
├── TranslatorMod          ← ClientModInitializer (wires it up)      [MC glue]
└── hook/
    ├── ChatTranslationHandler  ← ClientReceiveMessageEvents (chat)  [MC glue]
    ├── ChatLiveUpdater         ← replaces a chat line in place      [MC glue]
    ├── ItemTooltipHandler      ← ItemTooltipCallback (item name+lore)[MC glue]
    ├── TextStyleSupport        ← Text ↔ ColorProfile, builds styled Text [MC glue]
    └── mixin/ChatHudAccessor   ← @Accessor messages / @Invoker refresh [Mixin]
```

`HttpTransport` and `Translator` are interfaces, so the network/backend are
replaced by **inline fakes** in tests — no real HTTP, no Minecraft classpath.

### Translation strategy

- **Chat (default = live update)** → the original line is shown **immediately**,
  translated in the background, then the line is **replaced in place** when ready
  (`liveChatUpdate = true`). No client-thread freeze.
  - If you set `liveChatUpdate = false`, chat falls back to the older behaviour:
    a **synchronous blocking** translate before display when `blockingChat = true`
    (bounded by a 4 s timeout), or show-original-and-warm-cache when `false`.
- **Item tooltips** → **always non-blocking** (they redraw every frame while
  hovering; a blocking network call there would freeze the game). On a cache miss
  the original is shown and the translation pops in a frame or two later.
- **Two-tier cache** → in-memory LRU (fast) backed by an on-disk store. The disk
  tier is **wiped on game start** (`clearDiskCacheOnStart`), so a long session keeps
  LRU-evicted entries without translations piling up across sessions.
- **Failure backoff** → after a failed translation the same string is suppressed
  for `failureBackoffMs` (default 10 s) before being retried, so a hovered item
  that keeps failing does not fire a request every frame (and won't get the free
  endpoint rate-limited / 429-banned).
- **Failure backoff** → after a failed translation the same string is suppressed
  for `failureBackoffMs` (default 10 s) before being retried, so a hovered item
  that keeps failing does not fire a request every frame (and won't get the free
  endpoint rate-limited / 429-banned).

### Chat hooking detail

Fabric 1.21.x has no single "modify received chat" event:

- **System / server game messages** (where most server chat arrives) →
  `ClientReceiveMessageEvents.MODIFY_GAME`, modified in place.
- **Signed player chat** → only `ALLOW_CHAT` exists, so the original is suppressed
  and the (decorated) line is re-injected into the chat HUD. Trade-off: re-injected
  lines lose the signed-message trust indicator. The whole path is wrapped in a
  guard that falls back to the original message on any error, so chat is never lost.

In **live mode**, the displayed line is found again by reference in `ChatHud`'s
message list and its content is swapped for the translation, then `refresh()` re-flows
the chat (`ChatLiveUpdater` + `ChatHudAccessor`).

### Colour preservation (how the "rainbow" is remembered)

1. `TextStyleSupport.extract(Text)` walks the component tree via `Text.visit` and
   records the colour + format of **every visible character** into a `ColorProfile`.
2. The plain text is translated.
3. `StyleMapper.toRuns(translated, profile)` stretches the original colour sequence
   across the translated string and groups it into coloured runs.
4. `TextStyleSupport.styled(...)` rebuilds a `Text` from those runs. In PARALLEL
   mode the **original `Text` is appended untouched**, so animated rainbows that
   the server re-sends each tick keep animating.

---

## Install (prebuilt)

A built jar is at **`build/libs/mctranslator-1.0.0.jar`**. To install:

1. Install **Fabric Loader** for Minecraft **1.21.1** and run it once.
2. Put **`mctranslator-1.0.0.jar`** and **[Fabric API](https://modrinth.com/mod/fabric-api)**
   (the 1.21.1 build) into `.minecraft/mods/`.
3. Launch the Fabric 1.21.1 profile. Config is written to `.minecraft/config/mctranslator.json`.

> Built against: Minecraft 1.21.1, Yarn 1.21.1+build.3, Fabric Loader 0.16.10,
> Fabric API 0.116.12+1.21.1 (Loom 1.7.4, JDK 21).

## Building from source

Requires **JDK 21**.

```bash
gradle wrapper        # once, if you don't have the wrapper
./gradlew build       # -> build/libs/mctranslator-1.0.0.jar
./gradlew runClient   # dev client with the mod loaded
./gradlew test        # 63 unit tests
```

---

## Tests

All tests use **inline mocks** — the network (`HttpTransport`), the translation
backend (`Translator`), and the async executor are replaced by small inline
implementations inside each test. No real HTTP, no Minecraft on the test classpath.

| Test | Covers |
| --- | --- |
| `GoogleResponseParserTest` | Parsing real Google responses, multi-sentence, escapes, malformed input |
| `GoogleFreeTranslatorTest` | URL building, parse via inline transport, IO-error wrapping |
| `TextFilterTest` | Skips blanks/numbers/already-Chinese; translates real text |
| `TranslationCacheTest` | Blocking + caching, failure not cached, async dedup + callback, failure backoff, disk-tier recovery, LRU eviction |
| `TranslationServiceTest` | REPLACE/PARALLEL decisions, toggles, item-path non-blocking, async chat request |
| `ColorProfileTest` | Per-char colour capture + proportional index mapping + clamping |
| `StyleMapperTest` | Solid colour, rainbow sweep, gradient stretch, surrogate-pair safety, format flags |
| `FileStoreTest` | Disk round-trip, clear-on-start wipe, load-on-reopen, overwrite |
| `TranslatorConfigTest` | JSON round-trip, defaulting, normalisation |

63 tests, all passing (also run by `./gradlew test` against the real toolchain).
Parser + end-to-end path were also validated against the live Google endpoint
during development.

---

## Known limitations

- **Free endpoint is unofficial** — may rate-limit or change format. For
  production, switch to the official Cloud Translation API.
- **Colour mapping is approximate.** The original colour sweep is stretched across
  a different-length translation, so it won't line up character-for-character with
  the source; click/hover events and fonts on the original are not reapplied to the
  translated portion.
- **Tooltip translation lags by a frame or two on first hover** (non-blocking by
  design) and re-extracts colours each frame while hovering (cheap for a few lines).
- **Re-injected player chat loses the signed-message trust indicator** (see
  *Chat hooking detail*).
- **Live chat replacement matches the line by reference.** If chat is cleared or the
  line scrolls out of the buffer before the translation returns, that line stays as
  the original (no crash). REPLACE mode on player chat keeps the `<player>` prefix
  but drops the original message body.
- **Privacy:** chat and item text is sent to Google for translation.
