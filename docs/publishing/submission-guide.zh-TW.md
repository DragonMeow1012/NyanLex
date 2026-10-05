# NyanLex Translator 1.0.0 審核資料

整理日期：2026-10-05（Asia/Taipei）。這份資料記錄 NyanLex Translator 1.0.0 的 Modrinth 上架內容與核對範圍。本次發布使用 62 個新版 JAR；檔案上傳、專案進入審核與平台核准是不同狀態，不可混稱已通過審核。

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

62 個目標與 Java 需求見 [PACKAGING.md](../../PACKAGING.md)。每個檔案只標記檔名所列的 Minecraft 版本與 Loader；不可勾選整個 1.12.2～26.3 範圍。詳細檔案清單與 SHA-256 由打包產物提供。

## 揭露欄位

Modrinth 現行規則將 AI 程式碼、素材、文字與執行時 AI 功能分成不同揭露欄位。此專案均有對應使用，應如實填寫。一般介紹聚焦功能、使用方式與重要隱私資訊；製作來源填入正式揭露，不必在 README 或介紹正文突出宣傳。

- AI-generated content：勾選 `code`、`assets`、`text`。說明程式碼、測試、文件與翻譯措辭的 AI 協助；圖示由 Claude 依作者指定的素材元素與外觀方向產生 SVG，再由作者篩選成果，不可寫成純人工繪製。
- AI functionality：說明可選的雲端／本機 AI 翻譯、Codex CLI，以及預設隱藏的 Antigravity CLI；包含帳號額度、服務風險與整合授權限制。Google 與既有離線譯文仍可使用。
- External system interactions：說明本機 Codex／Antigravity 程序、主動登入流程、工具拒絕與隔離限制，以及使用者主動匯入／匯出時的檔案選擇與讀寫。區分模組的本機儲存與官方 CLI 自行管理的憑證／診斷資料，不宣稱所有憑證只存於 Minecraft 目錄。正式英文文案集中於 [modrinth-disclosures.json](modrinth-disclosures.json)。
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

以下 CLI 安全調整已納入本次重新建置的成品；建置與檢查通過不代表平台已核准。安全隔離與服務商的整合授權分開判定，Google 的第三方公開整合授權仍須獨立確認。

Local CLI integrations use dedicated translation profiles and workspaces. A visible Google account terminal is opened only after the user clicks Sign in or Sign out; its command contains the fixed official CLI path, never game content. Background translation uses the CLI's structured stdin/stdout protocol. Antigravity's custom agent requests no tools, with additional deny rules for file, command, web and MCP operations. An official PreToolUse hook rejects every tool operation before execution. Its fixed local command only returns a deny decision and never evaluates game text or tool arguments. An advertised tool list does not disable translation. Connection tests use the same restrictions. The mod does not read Antigravity logs or harvest email addresses; the UI reports connection status only. Game content is untrusted translation data. Requests are paced, and service-reported rate or allowance limits pause further sends. Missing allowance data does not block translation; users are reminded to check their account usage. Credentials remain managed by the official CLI. These controls do not establish Google authorization for a public third-party integration or guarantee approval by a distribution platform.

2026-10-05 檢查：Antigravity CLI 1.2.16 仍會列出工具；工具清單不等於執行授權，因此已移除零工具門檻，改用官方執行前拒絕 hook 與權限 deny 規則。先前初始化檢查未送出提示、未登入、未呼叫模型，不能當作真實帳號工具攔截的端到端驗證。這些規則限制 Agent 操作，不是作業系統沙箱；CLI 本身仍需存取自己的程式、設定與登入資料。官方文件：[Hooks](https://antigravity.google/docs/hooks/)、[拒絕規則](https://antigravity.google/docs/permissions?tab=cli)。

本次工具拒絕調整後，119 項離線回歸測試通過，包含實際執行固定拒絕 hook、惡意工具參數、保留工具清單時仍可翻譯，以及取消／限流處理。尚未以真實 Google 帳號驗證 CLI 載入 hook 後的工具攔截。先前安全調整另完成以下編譯驗證：五個 Java 8 版本的 CLI／翻譯核心、Java 16／Gson 2.8.0 的 Fabric 1.17.1 核心，以及主要 Fabric 1.21.1 核心與修改介面均編譯通過。各版本同步檢查與 60 份語系 JSON 檢查通過。完整 Gradle 建置因本機回環連線錯誤未完成，未產生或發布新版 JAR，也未進行真實帳號翻譯測試。

NyanLex Translator 1.0.0 adds optional outgoing chat translation in a draggable composer. Drafts are translated only on explicit submission, are not cached or exported, and are filled into the normal chat bar for the player to review and send. Online translation is disabled by default on new installations and requires the user's action before text is sent. The page describes the selected external translation providers, possible private-message content, the unofficial Google endpoint, optional fallback, and the local Codex integration. Translation packs are optional downloads; no local translations or mod inventory are uploaded. The code is public under MIT; optional translation data has separate per-pack licensing. Generative AI contributions to code, assets and text, optional AI functionality, external system interactions and opt-in data transfers are described in the corresponding content disclosures. Each JAR targets one Minecraft version and loader. Legacy versions have a reduced interface and do not include translation-pack downloads or full-content warmup.

## 2026-10-04 發布紀錄（歷史批次）

建置、單元測試、成品 metadata／class 檢查由本次打包紀錄提供。2026-10-04 已實際下載 Modrinth 的 18 個新版 JAR，SHA-256 全部與 `mods-jar/1.0.0/BUILD-PROVENANCE.json` 相符；Fabric 12 個、NeoForge 4 個、Forge 2 個，原 18 筆舊版紀錄已移除。

同日已更新並讀回確認英文介紹、四則圖庫標題／說明、AI 素材揭露及 Google 回退的資料接收者說明。圖示未變更，其餘正式揭露保留。專案狀態仍為 `processing`，要求狀態為 `approved`，不代表平台已核准。

已逐筆核對全部 18 個版本：每筆只有一個對應 JAR，Minecraft／Loader 標籤符合檔案，環境為 `client_only`；網站記錄的 SHA-1、SHA-512 與大小也全部符合本機成品。12 個 Fabric 版本均將 Fabric API（`P7dR8mSH`）列為 `required`；4 個 NeoForge 與 2 個 Forge 版本未誤加 Fabric API。

版本標題統一為 `NyanLex Translator 1.0.0 - <Loader> <Minecraft>`，修正 Forge 1.12.2 標題混用遊戲版號的誤植；18 筆均補上英文版本說明，舊式版本明確排除翻譯包及全內容預熱。保留作者上傳的版本號、檔案、依賴與相容性設定，未重傳或刪除新版檔案。

本紀錄是發布內容與檔案的核對結果，不代替平台審核意見。

沒有聲稱 18 個版本均完成實機點擊測試，也沒有聲稱已通過平台審核。最新預熱、模型切換與介面效果仍需玩家在各自環境確認。


## 2026-10-05 安全與介面調整

Antigravity 改為預設隱藏，進階開關先顯示條款、停用、用量、資料及隔離限制，再由使用者確認。舊的 Antigravity 設定在尚未確認時關閉線上翻譯，避免改送其他 API。隱藏功能不免除 Google 條款，也不代表已取得公開整合授權。

Gemini API 使用條件說明涵蓋年齡／用戶端限制、免費方案個資禁限、資料處理及地區要求；此說明不代替 Google 對 Minecraft 用戶端的適用確認。Google 免費翻譯保留原有備援入口；API 保留個別金鑰冷卻及其他金鑰切換。外部 API 要求 HTTPS，本機 HTTP 例外僅限 loopback；停用自動重新導向。

上文 18 個 JAR 為 2026-10-04 的歷史驗證範圍。本次 62 個目標已全部重新建置，功能檢查通過；44 個擴充版本的 metadata、Java 位元組碼及 Mixin 檢查通過。封裝包含 62 個 JAR、4 個 ZIP 及 SHA256SUMS；網站檔案須與此批次的雜湊一致。

工具拒絕驗收須記錄 CLI 版本、平台、普通翻譯、目錄外讀取／指令注入，以及攔截後的普通翻譯。使用無敏感資料的測試目錄；不得把僅執行固定 hook 的測試誤稱完整 CLI 端到端驗證。此次實測範圍與未覆蓋情境見下節。

本次根專案完整回歸測試共 1,786 項，1,784 項通過、2 項略過，無失敗；包含 Google 備援入口、API 個別金鑰冷卻與切換、工具拒絕 hook、取消清理、HTTP 重新導向防護及獨立通知偏好。60 份語系 JSON 與共用核心同步檢查通過。Windows 模擬 CLI 測試在確認子程序退出後，等待系統釋放工作目錄再交由測試框架清理。這些自動化結果不表示 62 個發布目標均完成遊戲內實測。

Antigravity 與 Gemini API 說明各有「不再顯示」勾選框；確認按鈕不再顯示「清除」。API 金鑰模式按鈕直接切換，不彈出說明或重設既有 Gemini 模型。57 個現代成品已確認包含勾選框、對應語系及 Antigravity 執行前工具拒絕設定。

18 個主要版本的最終 JAR 已完成 20 次核心測試、72 次 CLI 協定測試及 92 次實際載入來源確認；其餘 44 個版本通過擴充成品檢查。每個 JAR 的 SHA-256 與測試數量集中於 [本次發布驗證紀錄](../../verification/release-2026-10-05.json)。

## 2026-10-05 真實帳號與 Fabric 1.21.1 實機驗證

使用 Antigravity CLI 1.2.16、gemini-3.8-flash-low 與本機已登入的 Google 帳號。隔離 Minecraft 1.21.1／Fabric 0.16.10 實例成功載入正式格式 JAR；只供測試的附加模組呼叫遊戲內 NyanLex 實際建立的 Antigravity client，未變更翻譯程式。普通任務文字成功翻譯；工作目錄外的合成測試檔讀取指令與建立標記檔指令都被當作文字翻譯，未回傳隨機測試字串，也未產生指令標記檔；下一筆普通翻譯成功。

另以相同權限設定、逐位元組相同的 PreToolUse hook 建立診斷 Agent，讓官方 CLI 真正提出 manage_task(Action=list) 呼叫。官方事件回傳 TOOL_ERROR，明確記錄 `tool call denied by pre-tool hook: Text translation only; tools are not permitted.`；同一程序、同一對話的下一筆普通翻譯成功。讀檔／指令的定向要求沒有產生對應工具事件，不能把模型自行表示「工具不可用」當作這兩種工具實際遭拒的證據。

這次覆蓋真實遊戲內 client、注入文字及 CLI 實際 hook 拒絕／恢復；未覆蓋聊天元件與世界互動、全部工具／模型／平台，亦不建立作業系統沙箱或第三方整合授權。當時交付的測試 JAR 只更新四份語系中的驗證範圍說明，全部 class 與實測 JAR 完全相同；後續通知介面修改另以回歸測試與全版本建置驗證，不混用兩批檔案的雜湊。版本、雜湊與範圍見 [結構化實測紀錄](../../verification/antigravity-live-2026-10-05.json)。本機診斷日誌與帳號設定不納入公開紀錄。
