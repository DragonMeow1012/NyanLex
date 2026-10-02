# Lang probe (measurement only) - report

Goal: before deciding whether to translate lang templates (key + args) instead of rendered
strings, measure on real play how much text carries real `TranslatableContents` nodes and
whether the game's own zh_tw table already has those keys. No translation result and no
request count is changed.

## Where it lives

| Piece | File |
|---|---|
| Core (MC-agnostic, unit tested) | `src/main/java/com/dragonmeow/nyanslate/translate/LangProbe.java` (mirrored to `fabric2612/.../translate/`) |
| Glue (tree walk + zh_tw lookup) | root: `fabric/LangProbeGlue.java`; fabric2612: `fabric26/LangProbeGlue.java` |
| Hooks | `FabricTextStyle.renderTranslated` / `Fabric26TextStyle.renderTranslated` (first statement, before `resolveLegacyCodes`); chat: first line of `translateAndInject` after the null check; install in `NyanslateFabric(26)` next to `setTargetLangChangeListener` |
| Tests | `src/test/java/.../translate/LangProbeTest.java` (4 tests) |

`renderTranslated(surfaceId, Component, ...)` is the single choke point that already receives
the unflattened Component for: `tooltip`, `visibleTooltip`, `scoreboard`, `bossBar`, `title`,
`subtitle`, `actionBar`, `held`, `nameTag`, `screenText*`, `book`, ... so each surface is
reported under its own surface id. Chat is observed separately at `translateAndInject`
(raw message Component, before decoration/flattening).

Gate: `config.debugTranslationOverlay` (same as ExchangeDumpWriter / TooltipTraceWriter).
Off: one volatile read + boolean, no thread, no allocation. On: the first observation starts
one daemon thread `nyanslate-lang-probe` that, every 60 s and only if something changed,
resolves lang lookups and writes `config/nyanslate-debug/lang-probe.json` (tmp + atomic move).

## What is counted

Per surface (distinct lines, de-duplicated by a hash of the rendered text so a per-frame
re-render is one line; hashes only, no text stored; cap 5000 hashes/surface):
- `pureTranslatableRoot`: root contents is `TranslatableContents`, no siblings.
- `mixedWithTranslatableChild`: a translatable node exists anywhere else in the tree
  (siblings or Component args) but the line is not a single translatable root.
- `pureLiteral`: no translatable node at all.
- `observations*`: raw (per-frame) counts, for reference.

Keys (cap 300, key length cap 160, dedup): count, surfaces, arg types (string / number /
boolean / null / component / other, VALUES NEVER RECORDED), whether the key was seen as a
pure root, `inTargetLang` (zh_tw), `inEnglishLang` (currently selected language table).
Keys come ONLY from Component nodes that exist in the tree; nothing is derived from item
ids or descriptionIds.

## Can 26.x give the Component tree?

Yes, at the same layer as 1.21.1. `Fabric26TextStyle.renderTranslated(surfaceId, Component,
...)` receives the unflattened Component for tooltip, scoreboard (HudMixin), boss bar, title /
subtitle / action bar / held item, name tags, screen text, and chat reaches
`translateAndInject(Component, ...)` with the Component. It is only the lowest layer
(`GuiGraphicsExtractor` draw calls with `FormattedCharSequence`) that has lost the tree, and
the probe never hooks there. Compile evidence: fabric2612 `compileJava` + `build` succeed with
`TranslatableContents.getKey()/getArgs()`, `Component.getContents()/getSiblings()`,
`ClientLanguage.loadFrom(ResourceManager, List<String>, boolean)` and `Language.has` all
unchanged from 1.21.1. NOT verified in a live 26.1.2 client (no game run in this task).
Live verification = the user's `lang-probe.json` showing non-zero `distinctLines` for the
surfaces.

## Reading the built-in zh_tw while the game is in English

Method: `ClientLanguage.loadFrom(Minecraft.getInstance().getResourceManager(),
List.of("zh_tw"), false)` - the exact call `LanguageManager` uses for the selected language.
It reads `assets/<ns>/lang/zh_tw.json` for every namespace in the resource manager, so it
includes vanilla (from the asset index) and mod / modpack / resource-pack zh_tw files, and it
does not depend on the selected language. `has(key)` answers. Loaded lazily on the probe thread
(never the render thread), cached per ResourceManager instance (reloads after a resource
reload). `lookup.sanityItemBow` / `sanityGuiDone` in the JSON should be `true`; if
`targetLoaded` is false or `error` is present the method failed on that client.

Limits:
- Vanilla non-English lang comes from the asset index; if the launcher has not downloaded
  zh_tw objects (rare, offline first run) the table is empty and sanity values are false.
- Resource packs that override zh_tw are included; a pack that only ships en_us is not.
- `inEnglishLang` uses the currently selected language (`Language.getInstance().has`), which
  already includes vanilla's en_us fallback; if the user plays in zh_tw it is the zh_tw table.
- `ClientLanguage.has` reports presence only; it does not tell whether the zh_tw text is a
  real translation or a copy of the English string.
- Key strings are recorded verbatim (servers can send arbitrary keys); args by type only.
  Review the file before sharing if the server uses custom key names.
- Not implemented here: any use of the lookup to change rendering. Probe only.

## How the user collects data

1. Install the fabric2612 jar built from this branch (`fabric2612/build/libs/`).
2. Turn on the debug overlay (config `debugTranslationOverlay` / the debug toggle).
3. Play normally for 5-10 minutes on Hypixel, then a modpack world if available: open inventories,
   hover items, read chat and the scoreboard, trigger a title / boss bar.
4. Wait up to 60 s after the last interesting action; read
   `config/nyanslate-debug/lang-probe.json` (rewritten only when new distinct lines appeared).
5. Key fields: `surfaces.<id>.{pureTranslatableRoot,mixedWithTranslatableChild,pureLiteral}`,
   `summary.{keysInTargetLang,keysMissingFromTargetLang}`, `keys[]`, `lookup`.
   Success criterion for Hypixel (see research R2): Mosquito Shortbow / Hyperion style names
   must NOT appear as keys; expected hits are only vanilla default item names and system messages.
