# 漸層修正報告（Nyanslate 1.0.0，2026-10-02）

## 問題 1：tooltip 路徑沒有重套漸層
- 根因：驗證 harness 顯示，彩虹 render 命中「純色版」快取譯文（無 ⟦CS⟧ 標記，被標成 style-fallback）時，
  `markedChat` 走 `styledAnchored` → 逐字 1 字元 run 無錨點 → `styledChatContent` 取單一主色，漸層消失。
- 修法（`FabricTextStyle`，11 棵 glue 同步）：新增 `gradientWholeLine`，在 `styledAnchored` / `styledChatContent`
  開頭呼叫。當原文「恰好是一整段逐字漸層（可含空白 run）」時，用既有 `appendGradient`
  （`ColorShapeMatcher.sourceIndexFor`，端點對齊的比例插值）把原色序列重分佈到譯文每個字元：
  譯文較短則稀疏取樣、較長則相鄰字元重複同色；首字=首色、末字=末色；中文每字一個 Style。
  前後空白保留。非漸層多色句（含兩塊色 EPIC｜DUNGEON GLOVES、逐詞異色）不進此路徑，維持原結構、不比例上色。
- 刻意不含 rank 徽章（只用 `coalesceGradientRuns`），[MVP++] 徽章行為不變。

## 問題 2：短詞多色誤判漸層
- 修法：`qualifiesAsGradientZone` 增加 `switchesColourInsideWords`：把區間內每個「連續非空白字元」視為一個詞，
  長度 ≥2 的詞若被切成 ≥2 個色段算「字內切換」(split)，若只佔單一色段算 unsplit（以詞為單位換色）。
  必須 split ≥ 1 且 unsplit×2 ≤ split 才算漸層。`Go on up to it now`（0 個 split）→ 不是漸層。
  單字母詞中性。真彩虹（MYTHIC DUNGEON BOW 等）、2 字一階的漸層仍判為漸層。純詞內漸層名（Family A）路徑未動。
- 既有限制：色段本身含空白字元（如 " D"）時不會被視為「跨空白」，與修前一致。

## 順手：ABILITY 結束規則
- `TooltipSegmentPlanner.ABILITY_END` 允許 `⟦CS#⟧`/`⟦/CS#⟧` 前綴，Cooldown 列帶色標記也能結束區塊，
  後面的 Passive / 第二 Ability 正確切開；稀有度與交易列不被吞（測試覆蓋）。

## 測試（root：1242 tests，0 失敗，2 skipped；基準 1230，+12）
- 新 `GradientDetectionTest`（10）：`Go on up to it now`（有/無色空格）、真彩虹三組、2 字階梯漸層、[MVP++] 徽章、
  `Steve` 漸層名仍 Family A、tooltip 路徑 5 字／16 字重分佈與端點、style-fallback 經 `rebuildRich`、
  非漸層兩塊色與逐詞異色不得比例上色。
- `TooltipSegmentPlannerTest`（+2）：CS 前綴 Cooldown 切開 Passive／稀有度／交易；同一 CS ability 區塊在不同尾端下 segment 文字相同。
- 既有 GradientRarityLineTest、SubWordCoalesceTest、聊天彩色人名相關測試全過。

## 同步／編譯
- `sync-core.ps1` 已執行（9 棵樹 + fabric1171 的 core、fabric12111 測試樹）。
- 11 處 TextStyle（root、fabric1171/1182/1194/120/12111/26/2612、neoforge/120/26）皆含 `gradientWholeLine`
  與 `switchesColourInsideWords`（grep 確認，1171/1182 用 TextComponent 版本）。
- compileJava：root（測試）、fabric1171/1182/1194/120/12111（需 gradle 9.5.0）/2612/26/263、neoforge/26/263 EXIT 0；neoforge120 失敗屬環境（缺 JDK17 且 foojay 無法下載），未能驗證。
