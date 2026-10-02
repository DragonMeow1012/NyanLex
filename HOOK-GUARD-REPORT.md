# HOOK-GUARD-REPORT

Work on top of 680f09d. Addresses two P0 stability gaps from the neighbour-research digest.

## Mechanism (core, com.dragonmeow.nyanslate.translate)
- HookHealth: O(1) thread-safe hit registry. Each hook logs once on first hit. The first in-world ("ambient": Gui/Hud render, chat render, Forge overlay events) hit starts a 30 s clock; after it, ONE report lists registered-but-never-hit hooks (INFO; ambient misses additionally WARN as probably-broken injection). If no ambient hook ever hits, a fallback report fires 120 s after the first hit of any hook. Triggered lazily from any hook entry (no per-frame logging, no extra tick plumbing). The debug overlay token line now ends with `HOOKS miss n/total [off k]`.
- HookGuard: enter(id) (hit + disabled check), enterSticky(id) (begin/end pairs, never disabled), fail(id, t), call/run/runSticky lambda forms. Exceptions never reach the game: the handler returns the original untranslated value (the modified argument, or the vanilla call for redirects). The same exception kind is logged once per hook with a stack trace, repeat notes at 10/100/1000/...; 20 failures within 10 s disable the hook until the next launch (logged once). VirtualMachineError (except StackOverflowError) is rethrown.
- Java 8 compatible; copied byte-identical to the 5 legacy trees via sync-core.ps1 (added to its shared-boundary list); modern trees get it through the normal translate core mirror.
- Per tree a generated NyanslateHooks.register(info, warn) (first statement of the loader entry point) registers all hook ids; sinks route to the tree's slf4j LOGGER (legacy/Forge pass null -> java.util.logging).

## Tooling
verification/hook-guards.py apply|check: idempotent wrapper for @Inject/@Redirect/@ModifyVariable/@ModifyArg/@SubscribeEvent handlers, the Forge translateScreenString bytecode hook, Fabric event lambdas (ALLOW_GAME/ALLOW_CHAT/tooltip/tick/screen key/before+after render), init registration, token-line summary and NyanslateHooks generation. `check` exits 1 on any unwrapped handler or hook-list drift (currently OK for all 16 source trees). Root JUnit HookGuardCoverageTest enforces the same for the canonical tree.

## Files changed
- New core: src/main/java/.../translate/HookHealth.java, HookGuard.java (+ byte-identical copies in 15 trees)
- New tests: src/test/.../translate/HookGuardTest.java (14), HookGuardCoverageTest.java (2) (+ mirrored into fabric12111 by sync-core)
- New: verification/hook-guards.py; edited: sync-core.ps1
- Per tree: every *Mixin.java handler wrapped; loader main class (NyanslateFabric / Fabric26 / NeoForge / NeoForge26 / LegacyTranslatorMod / NyanslateForge) event wrappers + NyanslateHooks.register call + token-line summary; new generated NyanslateHooks.java.
- fabric263 / neoforge263 share the fabric26 / neoforge26 sources (covered).

## Hook inventory per tree
### fabric1144 - 13 hooks (11 mixin, 2 event/hook)
- mixin: DebugHud.debug, ChatComponent.translate, EntityNameTag.begin, EntityNameTag.end, EntityNameTag.name, FontText.draw, FontText.shadow, FontText.split, ScreenKey.screenKey, ScreenRenderScope.afterScreenRender, ScreenRenderScope.beforeScreenRender
- event/hook: event.clientTick, event.itemTooltip

### fabric1152 - 13 hooks (11 mixin, 2 event/hook)
- mixin: DebugHud.debug, ChatComponent.translate, EntityNameTag.begin, EntityNameTag.end, EntityNameTag.name, FontText.draw, FontText.shadow, FontText.split, ScreenKey.screenKey, ScreenRenderScope.afterScreenRender, ScreenRenderScope.beforeScreenRender
- event/hook: event.clientTick, event.itemTooltip

### fabric1165 - 13 hooks (11 mixin, 2 event/hook)
- mixin: DebugHud.debug, ChatComponent.translate, EntityNameTag.begin, EntityNameTag.end, EntityNameTag.name, FontText.draw, FontText.shadow, FontText.split, ScreenKey.screenKey, ScreenRenderScope.afterScreenRender, ScreenRenderScope.beforeScreenRender
- event/hook: event.clientTick, event.itemTooltip

### fabric1171 - 20 hooks (15 mixin, 5 event/hook)
- mixin: DebugHud.debug, BookPage.page, BookPage.resplit, BossBar.boss, ChatComponent.translateLegacyChat, EntityNameTag.stackedNameTag, EntityNameTag.translateNameTag, FontSplit.translateBeforeWrap, GuiScoreboard.clear, GuiScoreboard.held, GuiScoreboard.hud, GuiScoreboard.prepare, GuiScoreboard.score, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.clientTick, event.itemTooltip, event.screenAfterRender, event.screenBeforeRender, event.screenKey

### fabric1182 - 20 hooks (15 mixin, 5 event/hook)
- mixin: DebugHud.debug, BookPage.page, BookPage.resplit, BossBar.boss, ChatComponent.translateLegacyChat, EntityNameTag.stackedNameTag, EntityNameTag.translateNameTag, FontSplit.translateBeforeWrap, GuiScoreboard.clear, GuiScoreboard.held, GuiScoreboard.hud, GuiScoreboard.prepare, GuiScoreboard.score, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.clientTick, event.itemTooltip, event.screenAfterRender, event.screenBeforeRender, event.screenKey

### fabric1194 - 21 hooks (14 mixin, 7 event/hook)
- mixin: DebugHud.debug, BookPage.page, BookPage.resplit, BossBar.boss, EntityNameTag.stackedNameTag, EntityNameTag.translateNameTag, FontSplit.translateBeforeWrap, GuiScoreboard.clear, GuiScoreboard.held, GuiScoreboard.hud, GuiScoreboard.prepare, GuiScoreboard.score, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.allowChat, event.allowGame, event.clientTick, event.itemTooltip, event.screenAfterRender, event.screenBeforeRender, event.screenKey

### fabric120 - 29 hooks (22 mixin, 7 event/hook)
- mixin: ChatComponent.enterChatRender, ChatComponent.exitChatRender, DebugHud.debug, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.stackedNameTag, EntityNameTag.translateNameTag, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextCentered, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextOrdered, GuiGraphicsText.screenTextString, GuiGraphicsText.visibleComponentTooltip, GuiGraphicsText.visibleTooltip, GuiScoreboard.clearScoreboard, GuiScoreboard.heldName, GuiScoreboard.inlineHud, GuiScoreboard.prepareScoreboard, GuiScoreboard.scoreboard, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.allowChat, event.allowGame, event.clientTick, event.itemTooltip, event.screenAfterRender, event.screenBeforeRender, event.screenKey

### fabric12111 - 29 hooks (22 mixin, 7 event/hook)
- mixin: ChatComponent.enterChatRender, ChatComponent.exitChatRender, DebugHud.debug, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.name, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextCentered, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextOrdered, GuiGraphicsText.screenTextString, GuiGraphicsText.visibleComponentTooltip, GuiGraphicsText.visibleTooltip, GuiScoreboard.actionBar, GuiScoreboard.clearScoreboard, GuiScoreboard.heldName, GuiScoreboard.prepareScoreboard, GuiScoreboard.scoreboard, GuiScoreboard.title, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.allowChat, event.allowGame, event.clientTick, event.itemTooltip, event.screenAfterRender, event.screenBeforeRender, event.screenKey

### fabric2612 - 24 hooks (19 mixin, 5 event/hook)
- mixin: Hud.debugRequests, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.captureState, EntityNameTag.name, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextString, Hud.actionBar, Hud.clearScoreboard, Hud.heldName, Hud.prepareScoreboard, Hud.scoreboard, Hud.title, OptionsScreen.addButton, ScreenRenderScope.beginVisibleScreen, ScreenRenderScope.endVisibleScreen, TextField.translateWhole
- event/hook: event.allowChat, event.allowGame, event.clientTick, event.itemTooltip, event.screenKey

### fabric26 - 24 hooks (19 mixin, 5 event/hook)
- mixin: Hud.debugRequests, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.captureState, EntityNameTag.name, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextString, Hud.actionBar, Hud.clearScoreboard, Hud.heldName, Hud.prepareScoreboard, Hud.scoreboard, Hud.title, OptionsScreen.addButton, ScreenRenderScope.beginVisibleScreen, ScreenRenderScope.endVisibleScreen, TextField.translateWhole
- event/hook: event.allowChat, event.allowGame, event.clientTick, event.itemTooltip, event.screenKey

### forge1122 - 11 hooks (0 mixin, 11 event/hook)
- mixin: (none)
- event/hook: event.onOverlayPost, event.onOverlayText, event.afterScreen, event.beforeScreen, event.onChat, event.onClientTick, event.onNameTagPost, event.onNameTagPre, event.onTooltipRender, event.screenKey, hook.translateScreenString

### forge1132 - 12 hooks (0 mixin, 12 event/hook)
- mixin: (none)
- event/hook: event.onOverlayPost, event.onOverlayText, event.afterScreen, event.beforeScreen, event.onChat, event.onClientTick, event.onNameTagPost, event.onNameTagPre, event.onTooltipRender, event.screenKey, event.screenKeyReleased, hook.translateScreenString

### neoforge120 - 28 hooks (21 mixin, 7 event/hook)
- mixin: ChatComponent.enterChatRender, ChatComponent.exitChatRender, DebugHud.debug, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.stackedNameTag, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextCentered, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextOrdered, GuiGraphicsText.screenTextString, GuiGraphicsText.visibleComponentTooltip, GuiGraphicsText.visibleTooltip, GuiScoreboard.clearScoreboard, GuiScoreboard.heldName, GuiScoreboard.inlineHud, GuiScoreboard.prepareScoreboard, GuiScoreboard.scoreboard, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.onClientChat, event.onClientTick, event.onItemTooltip, event.onRenderNameTag, event.onScreenKeyPressed, event.onScreenRenderPost, event.onScreenRenderPre

### neoforge26 - 23 hooks (17 mixin, 6 event/hook)
- mixin: Hud.debugRequests, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.captureState, EntityNameTag.name, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextString, Hud.actionBar, Hud.clearScoreboard, Hud.heldName, Hud.prepareScoreboard, Hud.scoreboard, Hud.title, OptionsScreen.addButton, TextField.translateWhole
- event/hook: event.onClientChat, event.onClientTick, event.onItemTooltip, event.onScreenKeyPressed, event.onScreenRenderPost, event.onScreenRenderPre

### neoforge - 29 hooks (22 mixin, 7 event/hook)
- mixin: ChatComponent.enterChatRender, ChatComponent.exitChatRender, DebugHud.debug, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.stackedNameTag, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextCentered, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextOrdered, GuiGraphicsText.screenTextString, GuiGraphicsText.visibleComponentTooltip, GuiGraphicsText.visibleTooltip, GuiScoreboard.actionBar, GuiScoreboard.clearScoreboard, GuiScoreboard.heldName, GuiScoreboard.prepareScoreboard, GuiScoreboard.scoreboard, GuiScoreboard.title, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.onClientChat, event.onClientTick, event.onItemTooltip, event.onRenderNameTag, event.onScreenKeyPressed, event.onScreenRenderPost, event.onScreenRenderPre

### root (src) - 30 hooks (23 mixin, 7 event/hook)
- mixin: ChatComponent.enterChatRender, ChatComponent.exitChatRender, DebugHud.debug, BookPage.forceResplit, BookPage.translateBookPage, BossBar.bossBar, EntityNameTag.stackedNameTag, EntityNameTag.translateNameTag, FontSplit.translateBeforeWrap, GuiGraphicsText.screenTextCentered, GuiGraphicsText.screenTextComponent, GuiGraphicsText.screenTextOrdered, GuiGraphicsText.screenTextString, GuiGraphicsText.visibleComponentTooltip, GuiGraphicsText.visibleTooltip, GuiScoreboard.actionBar, GuiScoreboard.clearScoreboard, GuiScoreboard.heldName, GuiScoreboard.prepareScoreboard, GuiScoreboard.scoreboard, GuiScoreboard.title, OptionsScreen.addToggle, TextField.translateWhole
- event/hook: event.allowChat, event.allowGame, event.clientTick, event.itemTooltip, event.screenAfterRender, event.screenBeforeRender, event.screenKey

## Verification
- Root `gradle test --offline` (gradle 8.10, JDK 21): 1246 tests, 0 failures, 2 skipped (baseline 1230 + 16 new).
- fabric12111 `test --tests *HookGuard*` green (mirrored tests).
- sync-core.ps1 -> 0 pending; verification/sync-legacy-forge-core.ps1 -Check -> SYNC_FORGE_CORE_OK.
- `python verification/hook-guards.py check` -> OK; `git diff --check` clean.
- compileJava BUILD SUCCESSFUL on all 18 targets: root, fabric1144/1152/1165/1171/1182/1194/120/12111/2612/26/263, neoforge/120/26/263, forge1122, forge1132 (neoforge120 needed an online run because its per-project Minecraft cache is not portable into a fresh worktree; forge via wrappers + temurin8).

## Not done / notes
- No real game launch: hook ids and fallbacks are verified by compile, source checks and unit tests only.
- The miss-report timer starts at the first ambient hook hit; if every ambient hook is broken the 120 s fallback applies.
- Gradle used the shared default user home (~/.gradle) rather than an isolated one: an empty home cannot build offline.
