# Settings UI redesign (scheme A) - W1 + W2 report

Scope: core (pure Java) + glue for **root `src/` (Fabric 1.21.1)** and **`fabric2612/` (MC 26.1.2)**.
Other trees keep their old screen; they only received the synced core copy (new classes + one config field).

## What was built

### Core (`config` package, mirrored by `sync-core.ps1`)
| File | Role |
| --- | --- |
| `SettingsPage` | six tabs: general / display / ai / requests / hub / advanced (+ tab lang key) |
| `SettingEntry` | one button: id, type (TOGGLE / CYCLE / SUBSCREEN / ACTION), label key, tip key, state, `press(cfg)`, `SettingAction`, `SideEffect`, `compactInPair` |
| `SettingAction` | what sub-screen / action an entry opens (glue maps it to its own screens) |
| `SettingsCatalog` | the declarative table of all pages and entries; inverted storage, master-switch wording, cooldown/batch steps live here |
| `SettingsRow`, `StateText` | row of 1-2 entries; Minecraft-free "state" value (lang key + args, or literal) |
| `SettingsLayout` | all geometry (tabs, list, help line, done, scrollbar, columns, placement) so every glue lays out identically |
| `TranslatorConfig.settingsIntroSeen` | new boolean, default false |

Tests added (25): `SettingsCatalogTest` (16: page order, counts, ids/keys, every key exists in en_us/zh_tw/zh_cn/zh_hk with equal `%s` count, master switch semantics, inverted fields, mode cycle, engine toggles, cooldown/batch wrap, formatting, destructive flags) and `SettingsLayoutTest` (9: design coordinates, single column, compact height, intro line, no overlap / in-bounds over 8 sizes x 6 pages, scrolling maths).

### Glue (same structure in both trees)
- `src/.../fabric/TranslationConfigScreen.java` and `fabric2612/.../fabric26/Fabric26ConfigScreen.java` (class names / constructors kept, so `OptionsScreenMixin` and the hotkey entry are untouched).
- Tab row (selected tab yellow) -> list that scrolls by whole rows (wheel, drag scrollbar, PageUp/PageDown; off-window buttons are `visible=false, active=false` so Tab focus never lands on them) -> fixed help line (hover, else keyboard focus, else default hint; also shows 4 s status text after a clear) -> Done.
- Native `Tooltip` on every entry button and on `?` (both trees have `Tooltip`), in addition to the help line.
- Top right `?` button (opens existing help screen); title left, "已翻譯 N 進行中 M" progress in one line left of `?` when it fits.
- First open: `settingsIntroSeen` false -> yellow line "第一次使用？按右上角 ? 看說明" above the list, initial focus on `?`; flag set and saved right away.
- Master switch: label "翻譯總開關：開/關", on = `translationRequestsEnabled == true` (allowed), side effect `clearFtbPending`. Same wording for the two inverted fields (AI fallback, hub startup check).
- Destructive actions use vanilla `ConfirmScreen` with counts: clear cache (`service().translatedCount()`), clear hub translations (`hubLocalCache().size()`); the old press-twice buttons are gone. Open-repo keeps its existing ConfirmScreen. Hub download keeps `startHubIdentifyAndPlan` (confirm + progress screens) and the button shows download percent while a job runs.
- All existing sub-screens remain reachable: language, hotkeys, help, AI (+codex model/effort via it), machine source, do-not-translate terms, hub download confirm/progress/startup prompt, export/import. `TranslationCooldownScreen` / `Fabric26CooldownScreen` deleted (cooldown and batch are now inline cycle buttons on the Requests page). `TranslationHubScreen` / `Fabric26HubScreen` classes are kept but no longer opened from settings (only `HUB_URL` made package-visible and reused).
- Warm-up hook: in the screen class, `static boolean itemWarmupAvailable()` (returns `false`) and `static Screen openItemWarmupScreen(Screen parent)` (returns `null`). While unavailable the button is disabled and labelled "全物品預熱… 即將推出". To wire the feature: make the first return true and the second return the new screen.
- Lang: new namespace `nyanslate.settings.*` (92 keys) in en_us / zh_tw / zh_cn / zh_hk of both trees; zh_tw text follows design 3.2. Old keys all kept.

### 26.x specifics (fabric2612)
`extractRenderState(GuiGraphicsExtractor ...)`, `g.text` / `g.centeredText` with 0xFF-alpha colours, `setScreenAndShow`, `MouseButtonEvent` (`mouseClicked(event, doubleClick)`, `mouseDragged(event, dx, dy)`, `mouseReleased(event)`), `KeyEvent.key()` for PageUp/PageDown; `mouseScrolled(x, y, sx, sy)` unchanged. The list is plain `Button`s + `fill`, not `ObjectSelectionList`, so no list-widget API differences.

## Page contents (button text zh_tw; two-column placement at GUI 320x240)

| Page | Row: entries | Type |
| --- | --- | --- |
| 一般 | r0: 翻譯總開關：開 / 翻譯語言：跟隨遊戲（zh-TW） ; r1: 快捷鍵… / 使用說明… | toggle, sub, sub, sub |
| 顯示 | 9 rows (聊天, 物品提示, 記分板, 名牌, Boss 血條, 標題, 動作列, 書籍, 介面): `[X：雙語]` mode cycle (譯文 -> 雙語 -> 原文) + `[AI/機翻]` engine toggle | cycle + toggle |
| AI | r0: AI 設定… / 機翻來源：Google 免費翻譯 ; r1: AI 失敗補譯：開 / 介面掃描：機翻 | sub, sub, toggle, toggle |
| 請求 | r0: 請求冷卻：10 秒 / 批次收集：5 秒 ; r1: 聊天送出：依序 / 不翻譯詞彙… ; r2: 全物品預熱…（即將推出，停用） | cycle, cycle, toggle, sub, action |
| 倉庫 | r0: 分享翻譯：關 / 啟動檢查：開 ; r1: 下載倉庫翻譯… / 開啟倉庫網頁… ; r2: 清除倉庫翻譯…（確認） | toggle, toggle, action x3 |
| 進階 | r0: 匯出翻譯檔… / 匯入翻譯檔… ; r1: 清除快取…（確認） / 偵錯浮窗：關 | action, action, action, toggle |

## Layout coordinates (from `SettingsLayout`, GUI-scaled px)

Rules: title y=8 (x=8); tabs y=24, h20; first-run hint y=47 (list shifts +11); list top y=50, rows 22 apart, buttons 20 high; help area at y=h-50 (2 lines, 9 px each; list bottom = help y - 2); Done at y=h-26, w=min(200,w-24); `?` at (w-22, 4) 16x14; scrollbar x = content right + 3, w4. Compact (h<200): no title, tabs at y=4 left of `?`, 1 help line at h-38, list top 30. Two columns when w>=260 (column w=min(150,(w-24)/2), gap 6), single column of min(220,w-24) otherwise; display rows in two columns: mode button w = content - 80 - 6, engine button 80.

| GUI | cols | colW | contentX | tab w | list top | list bottom | visible rows | help y (lines) | done y | scrollbar x |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 240x180 | 1 | 216 | 12 | 32 | 30 | 140 | 5 | 142 (1) | 154 | 231 |
| 240x240 | 1 | 216 | 12 | 36 | 50 | 188 | 6 | 190 (2) | 214 | 231 |
| 320x240 | 2 | 148 | 9 | 49 | 50 | 188 | 6 | 190 (2) | 214 | 314 |
| 320x270 | 2 | 148 | 9 | 49 | 50 | 218 | 7 | 220 (2) | 244 | 314 |
| 427x240 | 2 | 150 | 60 | 58 | 50 | 188 | 6 | 190 (2) | 214 | 369 |

Row counts per page (two columns / one column): 一般 2/4, 顯示 9/18, AI 2/4, 請求 3/5, 倉庫 3/5, 進階 2/4. Only 顯示 scrolls in two columns (320x240: max first row 3; 320x270: 2); in single column 顯示 scrolls (13 at 240x180, 12 at 240x240) and every other page fits without a scrollbar.

Placement at 320x240 (x, width): general/ai/requests/hub/advanced left cell x=9 w=148, right cell x=163 w=148; display mode x=9 w=216, engine x=231 w=80.

No screenshots: the game client could not be driven in this environment, so layout was verified by unit tests (no overlap, in-bounds, scroll maths) and by compiling against both Minecraft versions; an in-game look is still needed.

## Differences from the design
1. Column width at 320 is 148 (rule `(w-24)/2`), not 150; 150 only from w>=324.
2. 介面 (screen text) stays a three-way cycle (原文/雙語/譯文) like the current code and `DisplayMode.next()`; the design said two-state. Not changed because the stored mode/semantics already support three values.
3. Mode state names use design wording 原文 / 雙語 / 譯文 (old keys 原文＋翻譯 / 只有翻譯 kept, unused by the new screen); engine states 機翻 / AI.
4. Help-screen split into six per-tab sections (design 3.4) not done; existing help screen opens from `?`.
5. "Share translations" first-enable ConfirmScreen (design 3.3) and "import: confirm merge" not added; only the destructive actions were converted.
6. Machine-source entry exists once (AI page), not duplicated on 一般 (matches design recommendation). Provider state shows the plain provider name (no "實驗" suffix; picker screen still shows it).
7. Keyboard: Tab can only reach rows inside the window; use PageUp/PageDown (or the wheel) to scroll, then Tab.
8. Hub screen classes kept (unused from settings) rather than deleted, to keep the diff small; `?` button replaces the old yellow top-left help button.
9. Item warm-up is an inert hook (see above).

## Verification
- Root: `gradle test --offline` -> 1255 tests, 0 failures (baseline 1230 + 25), 2 skipped (pre-existing). `gradle assemble` OK.
- `sync-core.ps1` ran clean (only new core files, `TranslatorConfig`, and the two test files mirrored to fabric12111).
- Build results of all other trees are listed in the final section below.

### Other trees (glue untouched, synced core) - `compileJava`
All OK: fabric1171, fabric1182, fabric1194, fabric120, fabric12111, fabric26, fabric263, neoforge, neoforge26, neoforge263, neoforge120 (the last one needed an online run: NeoGradle's per-project cache is absent in a fresh worktree). Root and fabric2612 are full builds (root `assemble`, fabric2612 `build`).

### Jars
- `fabric2612/build/libs/nyanslate-1.0.0-Fabric-26.1.2.jar` SHA-256 `9caf6ea61fe9b44fe2907cddd8f4027edb9b71febea46460a220a75a167a70f5`
- `build/libs/nyanslate-1.0.0-Fabric-1.21.1.jar` SHA-256 `9b49dacbe9376dd92e101158fcd165a23c337c0154d0b995ee6ed1e292b13650`
