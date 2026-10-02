# 全物品預熱（Item Warm-up）實作報告

Base：release/nyanslate-1.0.0 @ 680f09d。範圍：core + root(1.21.1) + fabric2612(26.1.2) glue。主設定畫面未動。

## core API（com.dragonmeow.nyanslate.warmup，已加入 sync-core.ps1 $corePackages）
- `ItemWarmupTarget(itemId, namespace, List<String> sources)`：一個物品的 tooltip 翻譯單位（tooltipParagraphPlan 的 sources，標題在內）。
- `ItemWarmupSource`（glue 實作，client thread）：`totalItemCount() / isAvailable() / probeNext(max) / isExhausted() / reset()`。
- `ItemWarmupBackend`（glue 實作，包 TranslationService）：`isAiEngine / requestsEnabled / isRateLimited / isReady / isPending / warm`。
- `ItemWarmupScanner(source, backend)`：乾跑掃描，`tick(budget)`、`scanned()/total()/plan(maxItems, usageSnapshot)`；不送任何請求。
- `ItemWarmupPlan`：totalItems、cachedItems、missingItems、missingUnits、missingChars、willSubmitItems、estimatedRequests、estimatedTokens、calibrated。
- `ItemWarmupEstimator`：見下方公式。
- `ItemWarmupDriver(source, backend, Supplier<TranslatorConfig>, LongSupplier clock)`：由 client tick 呼叫 `tick()`。
  - State：IDLE / RUNNING / PAUSED / DONE / STOPPED；PauseReason：NONE / USER / NO_WORLD / RATE_LIMITED / REQUESTS_OFF / ENGINE（除 USER 外自動恢復）。
  - `start()`（需 itemWarmupEnabled 且 AI 引擎）、`pause()`、`resume()`、`stop()`、`progress()`、`addListener/removeListener`。
  - 每 chunk（8 件）送出前檢查：總開關、AI 引擎、世界、`isRateLimited()`；chunk 間隔 `itemWarmupChunkDelayMs`；前一 chunk 仍 pending 則等待（上限 120 s）；每 tick 最多探測 40 件（避免 client thread 卡頓）；每次啟動上限 `itemWarmupMaxItemsPerSession`（只計實際送出的件數，已快取不計）。
  - 已快取即進度：重啟不需游標，已翻譯的物品（所有單位 isReady）直接略過。
- TranslationService 新增：`warmTooltipBatchBackground(List<String>)`（低優先序、機翻 no-op）、`isItemWarmupEngine()`。
- TranslatorConfig：`itemWarmupEnabled=false`、`itemWarmupWarningAcknowledged=false`、`itemWarmupChunkDelayMs=3000`（下限 500）、`itemWarmupMaxItemsPerSession=3000`（下限 0）。

## 估算公式（ItemWarmupEstimator）
共用單位（trim 後相同字串）只算一次。`willSubmit = min(missingItems, maxItemsPerSession)`；`chars` = 缺漏單位字元數 x (willSubmit / missingItems)。
`requests = max(ceil(willSubmit / 8), ceil(chars / 6000))`；`input = chars/3.5 + requests x 700`；`output = chars/2.5`；`tokens = input + output`。
若本次 session 已有 >= 5 次請求（SessionTokenUsage），tokens x clamp(實測每請求 token / 預設每請求 token, 0.25, 4)。

## glue 進入點
- root：`com.dragonmeow.nyanslate.fabric.NyanslateFabric.openItemWarmupScreen(net.minecraft.client.gui.screens.Screen parent)`（static，public）。
- fabric2612：`com.dragonmeow.nyanslate.fabric26.NyanslateFabric26.openItemWarmupScreen(Screen parent)`。
- 行為：driver RUNNING/PAUSED 時開進度畫面，否則開確認畫面（機翻時只顯示不可用原因；不在世界時提示先進世界）。
- 確認畫面：逐 tick 掃描 -> 顯示物品/已快取/待翻譯數、本次預估請求與 token、四段警告（背景最低優先序、用量與費用與 429 連帶暫停聊天/tooltip、只涵蓋模組註冊物品且對 Hypixel 無效、可暫停停止且下次自動接續）；按「開始預熱」才寫 `itemWarmupEnabled=true`、`itemWarmupWarningAcknowledged=true` 並開始。
- 進度畫面：進度條、狀態、暫停/繼續、停止（同時關閉 itemWarmupEnabled）、返回（離開不影響執行）。
- 自動接續：已確認警告且 enabled 時，每次啟動進世界約 20 秒（400 tick）後自動 `start()` 一次。
- 單件探測：`BuiltInRegistries.ITEM`（略過 AIR）、`getDefaultInstance()`、`getTooltipLines(TooltipContext.of(level), player, NORMAL)`，掛 `tooltipProbeDepth` 避免觸發 hover 翻譯路徑；例外一律吞掉視為無單位。
- lang：en_us / zh_tw / zh_cn / zh_hk 的 `screen.nyanslate.warmup.*`（插在 `hub.confirm.title` 之前，避免與設定 UI 分頁的新增 key 衝突）。

## 測試（root：1243 全綠，基準 1230 + 13）
`ItemWarmupDriverTest`：已快取不送、chunk 節流、總開關即停與恢復、共用單位只送一次（含估算只算一次）、限流暫停與恢復、無世界/使用者暫停/停止、重啟免游標續做且各單位只送一次、每次啟動上限、pending 背壓、每 tick 探測預算、停用/機翻拒絕啟動、估算（已快取數、上限、用量校正）、前景優先不被背景餓死（PriorityTranslationExecutor：背景低優先序排在前景之後；機翻 no-op）。

## 已知限制
- Hypixel 等伺服器物品（vanilla id + NBT）登錄表看不到，預熱無效，警告文案已說明。
- 只取預設堆疊，不展開附魔書/藥水等變體。
- 429 斷路器全引擎共用；預熱遇限流會自動暫停，但已發生的 429 仍會連帶影響前景，真正隔離需專用金鑰。
- <1.20.5 五樹與 legacy 未實作（本次範圍外）；僅 root 與 fabric2612 有畫面 glue，其餘樹只同步 core。
- 遊戲內畫面未實測（僅編譯）。
