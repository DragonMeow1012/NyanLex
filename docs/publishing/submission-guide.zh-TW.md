# NyanLex Translator 1.0.0 審核資料

整理日期：2026-10-03。這份資料供 Modrinth／CurseForge 上架與重新送審使用；尚未代替作者提交。

## 填寫資料

| 欄位 | 內容 |
| --- | --- |
| 專案名稱 | NyanLex Translator |
| 英文簡介 | Real-time translation for chat, items, tooltips and screens, with reusable local translations. |
| 類型 | Mod；客戶端功能／Utility／Quality of Life，依平台實際選項選擇 |
| 安裝端 | Client required；Server unsupported／不需要安裝 |
| 原始碼 | https://github.com/DragonMeow1012/NyanLex |
| 問題回報 | https://github.com/DragonMeow1012/NyanLex/issues |
| 程式碼授權 | MIT |
| 頁面文案 | [English](store-description.md)；[繁體中文](store-description.zh-TW.md)，英文在前 |
| 檔案 | 每個版本上傳自己對應的單一 JAR，不能把全版本 ZIP 當成模組 JAR |
| 相依性 | Fabric API：Fabric 檔案的必要依賴；NeoForge／Forge 檔案不要加 Fabric API |

18 個目標與 Java 需求見 [PACKAGING.md](../../PACKAGING.md)。每個檔案只標記檔名所列的 Minecraft 版本與 Loader；不可勾選整個 1.12.2～26.3 範圍。詳細檔案清單與 SHA-256 由打包產物提供。

## 揭露欄位

Modrinth 現行規則將 AI 程式碼、素材、文字與執行時 AI 功能分成不同揭露欄位。此專案均有對應使用，應如實填寫；本機 Codex 程序整合也應說明。資料傳送採使用者主動開啟，說明接收者、私訊可能被傳送、Google 回退與金鑰本機儲存。這些是依本專案行為整理的填寫建議，平台最終判定由審核員作出。[Modrinth Content Disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures)、[Content Rules](https://modrinth.com/legal/rules)。

CurseForge 使用同一份完整英文功能與隱私說明，保留 AI 輔助圖示及實機截圖的來源說明；不要把頁面內容只寫成外部連結。依其實際表單提供相同揭露。[CurseForge Moderation Policies](https://support.curseforge.com/support/solutions/articles/9000197279-project-and-modpack-moderation-policies)。

## 圖片

- `better-mc-chat-composer.png`：Better MC 中的聊天輸入翻譯浮窗，譯文填入聊天欄後由玩家確認送出。
- `better-mc-item-original.png`、`better-mc-item-translated.png`：同一物品的翻譯前後對照。
- `better-mc-bilingual-chat.png`：聊天原文與繁體中文譯文並列，畫面中可見玩家名稱。
- `better-mc-translation-settings.png`：翻譯設定畫面。
- 圖示：使用 JAR 內宣告的專案圖示，標示 AI 輔助製作。

以上截圖均由 Better MC 遊戲畫面取得。不要把帶 debug 浮窗的任務截圖、私人桌面圖片或未確認音軌的影片混入資料包。

## 給審核員的說明（英文）

NyanLex Translator 1.0.0 adds optional outgoing chat translation in a draggable composer. Drafts are translated only on explicit submission, are not cached or exported, and are filled into the normal chat bar for the player to review and send. Online translation is disabled by default on new installations and requires the user's action before text is sent. The page describes the selected external translation providers, possible private-message content, the unofficial Google endpoint, optional fallback, and the local Codex integration. Translation packs are optional downloads; no local translations or mod inventory are uploaded. The code is public under MIT; optional translation data has separate per-pack licensing. AI assistance and AI functionality are disclosed. The attached JAR is for the exact game version and loader selected on this file. Legacy versions have a reduced interface and do not include translation-pack downloads or full-content warmup.

## 驗證範圍

建置、單元測試、成品 metadata／class 檢查及雜湊比對由本次打包紀錄提供。沒有聲稱 18 個版本均完成實機點擊測試，也沒有聲稱已通過平台審核。最新預熱、模型切換與介面效果仍需玩家在各自環境確認。
