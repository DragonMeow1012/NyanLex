# NyanLex Translator 1.0.0 審核資料

整理日期：2026-10-04（Asia/Taipei）。這份資料記錄 NyanLex Translator 1.0.0 的 Modrinth 上架內容與核對範圍。作者已上傳 18 個新版 JAR；檔案上傳、專案進入審核與平台核准是不同狀態，不可混稱已通過審核。

## 填寫資料

| 欄位 | 內容 |
| --- | --- |
| 專案名稱 | NyanLex Translator |
| 英文簡介 | Read chat, items and quests in your language, and translate your replies before sending. Client-side translation with Google, AI or local models. |
| 類型 | Mod；Modrinth 分類為 Utility、Social |
| 安裝端 | Client required；Server unsupported／不需要安裝 |
| 原始碼 | https://github.com/DragonMeow1012/NyanLex |
| 問題回報 | https://github.com/DragonMeow1012/NyanLex/issues |
| 操作文件 | https://github.com/DragonMeow1012/NyanLex/blob/main/README.en.md |
| 程式碼授權 | MIT |
| 頁面文案 | Modrinth 使用 [English](store-description.md)；[繁體中文](store-description.zh-TW.md) 留作中文參考。GitHub 首頁使用根目錄的繁體中文 README |
| 檔案 | 每個版本上傳自己對應的單一 JAR，不能把全版本 ZIP 當成模組 JAR |
| 相依性 | Fabric API：Fabric 檔案的必要依賴；NeoForge／Forge 檔案不要加 Fabric API |

18 個目標與 Java 需求見 [PACKAGING.md](../../PACKAGING.md)。每個檔案只標記檔名所列的 Minecraft 版本與 Loader；不可勾選整個 1.12.2～26.3 範圍。詳細檔案清單與 SHA-256 由打包產物提供。

## 揭露欄位

Modrinth 現行規則將 AI 程式碼、素材、文字與執行時 AI 功能分成不同揭露欄位。此專案均有對應使用，應如實填寫。一般介紹聚焦功能、使用方式與重要隱私資訊；製作來源填入正式揭露，不必在 README 或介紹正文突出宣傳。

- AI-generated content：勾選 `code`、`assets`、`text`。說明程式碼、測試、文件與翻譯措辭的 AI 協助；圖示由 Claude 依作者指定的素材元素與外觀方向產生 SVG，再由作者篩選成果，不可寫成純人工繪製。
- AI functionality：說明可選的雲端／本機 AI 翻譯與本機 Codex CLI；Google 與既有離線譯文仍可使用。
- External system interactions：說明本機 Codex 程序，以及使用者主動匯入／匯出時的檔案選擇與讀寫。
- Telemetry：使用 `opt_in`，說明選定文字與私訊可能傳至所選服務、名稱遮罩的限制、Google 回退、金鑰本機儲存，以及翻譯包只下載、不上傳使用者資料。

AI 素材揭露不是圖片政策的豁免。作者決定暫時保留現有圖示，若審核要求再更換；這仍是未排除的審核風險，不能記錄成已確認符合規則。[Modrinth Content Disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures)、[Content Rules](https://modrinth.com/legal/rules)。

本次只處理 Modrinth。若另外上架 CurseForge，需另行核對其當時規則與表單，不把 Modrinth 的揭露做法直接視為跨平台保證。

## 圖片

- `better-mc-chat-composer.png`：Better MC 中的聊天輸入翻譯浮窗，譯文填入聊天欄後由玩家確認送出。
- `better-mc-item-original.png`、`better-mc-item-translated.png`：同一物品的翻譯前後對照。
- `better-mc-bilingual-chat.png`：聊天原文與繁體中文譯文並列，畫面中可見玩家名稱。
- `better-mc-translation-settings.png`：翻譯設定畫面。
- 圖示：依作者決定保留現有檔案，來源記入正式 AI 素材揭露；不得把格式轉換或重新輸出當成移除 AI 來源。

以上截圖均由 Better MC 遊戲畫面取得。介紹內僅註記一次測試環境，不把整合包或其他模組名稱當成合作背書或完整相容性保證。不要把帶 debug 浮窗的任務截圖、私人桌面圖片或未確認音軌的影片混入資料包。

圖庫文字應描述功能與畫面，不承諾所有模組介面、全部 Minecraft 版本或每種 Loader 組合都支援，也不保證每次翻譯的格式與數值一定完全保留。實際可用組合以各 JAR 對應的版本為準。

## 給審核員的說明（英文）

NyanLex Translator 1.0.0 adds optional outgoing chat translation in a draggable composer. Drafts are translated only on explicit submission, are not cached or exported, and are filled into the normal chat bar for the player to review and send. Online translation is disabled by default on new installations and requires the user's action before text is sent. The page describes the selected external translation providers, possible private-message content, the unofficial Google endpoint, optional fallback, and the local Codex integration. Translation packs are optional downloads; no local translations or mod inventory are uploaded. The code is public under MIT; optional translation data has separate per-pack licensing. Generative AI contributions to code, assets and text, optional AI functionality, external system interactions and opt-in data transfers are described in the corresponding content disclosures. Each JAR targets one Minecraft version and loader. Legacy versions have a reduced interface and do not include translation-pack downloads or full-content warmup.

## 驗證範圍

建置、單元測試、成品 metadata／class 檢查由本次打包紀錄提供。2026-10-04 已實際下載 Modrinth 的 18 個新版 JAR，SHA-256 全部與 `mods-jar/1.0.0/BUILD-PROVENANCE.json` 相符；Fabric 12 個、NeoForge 4 個、Forge 2 個，原 18 筆舊版紀錄已移除。

同日已更新並讀回確認英文介紹、四則圖庫標題／說明、AI 素材揭露及 Google 回退的資料接收者說明。圖示未變更，其餘正式揭露保留。專案狀態仍為 `processing`，要求狀態為 `approved`，不代表平台已核准。

已逐筆核對全部 18 個版本：每筆只有一個對應 JAR，Minecraft／Loader 標籤符合檔案，環境為 `client_only`；網站記錄的 SHA-1、SHA-512 與大小也全部符合本機成品。12 個 Fabric 版本均將 Fabric API（`P7dR8mSH`）列為 `required`；4 個 NeoForge 與 2 個 Forge 版本未誤加 Fabric API。

版本標題統一為 `NyanLex Translator 1.0.0 - <Loader> <Minecraft>`，修正 Forge 1.12.2 標題混用遊戲版號的誤植；18 筆均補上英文版本說明，舊式版本明確排除翻譯包及全內容預熱。保留作者上傳的版本號、檔案、依賴與相容性設定，未重傳或刪除新版檔案。

本紀錄是發布內容與檔案的核對結果，不代替平台審核意見。

沒有聲稱 18 個版本均完成實機點擊測試，也沒有聲稱已通過平台審核。最新預熱、模型切換與介面效果仍需玩家在各自環境確認。
