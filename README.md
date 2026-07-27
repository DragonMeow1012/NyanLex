# Minecraft Translator 1.0.2

[English README](README_EN.md)

Minecraft Translator 是純用戶端即時翻譯模組。它只處理玩家目前看到、且確實需要翻譯的文字，不修改伺服器資料，也不改寫玩家送出的聊天訊息。模組使用非同步佇列、完整項目批次、分區快取與嚴格回應驗證，降低請求次數並避免錯誤結果污染其他文字。

## 下載與發布檔案

每個 JAR 只支援檔名標示的 Minecraft 版本與 Loader，不能跨版本或跨 Loader 混用。請從 [GitHub Releases](https://github.com/DragonMeow1012/MinecraftTranslator/releases/latest) 下載。

Release 提供：

- 16 個可直接放入 `mods` 的獨立 JAR。
- `MinecraftTranslator-1.0.2-Fabric.zip`：包含 11 個 Fabric JAR。
- `MinecraftTranslator-1.0.2-Forge.zip`：包含 2 個 Forge JAR。
- `MinecraftTranslator-1.0.2-NeoForge.zip`：包含 3 個 NeoForge JAR。
- `MinecraftTranslator-1.0.2-all-versions.zip`：內含 `fabric/`、`forge/`、`neoforge/` 三個資料夾。

## 各版本支援內容

| Minecraft | Loader | Java | JAR | 支援內容 |
| --- | --- | ---: | --- | --- |
| 1.12.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.12.2.jar` | 聊天、物品名稱與物品提示；`G` 切換顯示；JSON 設定；Google／Youdao／DeepL／Microsoft 機翻；OpenAI 相容 AI；完整項目批次、邊界驗證與請求冷卻。受舊版文字 API 限制，不提供完整圖形設定頁與完整樣式重建。 |
| 1.13.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.13.2.jar` | 聊天、物品名稱與物品提示；`G` 切換顯示；JSON 設定；Google／Youdao／DeepL／Microsoft 機翻；OpenAI 相容 AI；完整項目批次、邊界驗證與請求冷卻。受舊版文字 API 限制，不提供完整圖形設定頁與完整樣式重建。 |
| 1.14.4 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.14.4.jar` | 聊天、物品名稱與物品提示；精簡遊戲內設定頁；目標語言清單與搜尋；機翻來源與 AI 設定；`G` 切換；完整項目批次與邊界驗證。顏色與互動事件依此版本 API 能力盡量保留。 |
| 1.15.2 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.15.2.jar` | 聊天、物品名稱與物品提示；精簡遊戲內設定頁；目標語言清單與搜尋；機翻來源與 AI 設定；`G` 切換；完整項目批次與邊界驗證。顏色與互動事件依此版本 API 能力盡量保留。 |
| 1.16.5 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.16.5.jar` | 聊天、物品名稱與物品提示；精簡遊戲內設定頁；目標語言清單與搜尋；機翻來源與 AI 設定；`G` 切換；完整項目批次與邊界驗證。顏色與互動事件依此版本 API 能力盡量保留。 |
| 1.17.1 | Fabric | 16 | `mctranslator-1.0.2-Fabric-1.17.1.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.18.2 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.18.2.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.19.4 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.19.4.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.20.1 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.20.1.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.20.1 | NeoForge | 17 | `mctranslator-1.0.2-NeoForge-1.20.1.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.21.1 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.1.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.21.1 | NeoForge | 21 | `mctranslator-1.0.2-NeoForge-1.21.1.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 1.21.11 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.11.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 26.1.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.1.2.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 26.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.2.jar` | 2026/7/14 06:26 實測基準。聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |
| 26.2 | NeoForge | 25 | `mctranslator-1.0.2-NeoForge-26.2.jar` | 聊天、物品、計分板、名牌、Boss 血條、標題／副標題、動作列、書本／講台、按鈕與畫面文字、FTB 任務；完整設定頁與偵錯疊加層；`G`／`R`／`P`；AI 與四種機翻；磁碟快取、玩家名稱遮罩、段落／顏色／格式／互動事件保護。 |

Fabric 1.17.1、1.18.2、1.19.4、1.20.1、1.21.1、1.21.11、26.1.2、26.2，以及 NeoForge 1.20.1、1.21.1、26.2 都包含 7/14 基準版的樣式投影修正：翻譯文字已可顯示、但顏色標記被供應商破壞時，不會在背景無限重送；冷卻後再次真正看到相同內容，才會重新嘗試精確顏色投影。

## 安裝

1. 依上表下載完全相符的 JAR。
2. 安裝相符的 Fabric、Forge 或 NeoForge。Fabric 版本還需要相符的 Fabric API。
3. 將 JAR 放進該 Minecraft 實例的 `mods` 資料夾。
4. 使用上表指定的 Java 主版本啟動。
5. 第一次測試建議只安裝 Loader、必要 API 與本模組；確認正常後再加入大型模組包。

同一 Minecraft 版本的 Fabric 與 NeoForge 仍是不同檔案；1.21.1 與 1.21.11 也不能互換。

## 翻譯來源

| 來源 | 使用者 API Key | 說明 |
| --- | --- | --- |
| Google GT | 不需要 | 預設機翻來源，使用完整項目批次與嚴格回應驗證。 |
| Youdao Web | 不需要 | 非官方實驗網頁端點，網站改版或限制時可能失效。 |
| DeepL Web | 不需要 | 非官方實驗網頁端點，網站改版或限制時可能失效。 |
| Microsoft／Bing Web | 不需要 | 非官方實驗網頁端點，網站改版或限制時可能失效。 |
| OpenAI 相容 AI | 視端點而定，可留空 | 可使用 Gemini、OpenAI、DeepSeek、OpenRouter、Ollama、LM Studio 或自架服務。支援 Base URL、模型、多把金鑰輪替、詞彙表與停用 GT 回退。金鑰留空時不送出 `Authorization` header。 |

Youdao、DeepL 與 Microsoft 是供區域測試的網站介面，不保證長期可用。網站可能改版、加入驗證、限制 IP 或拒絕自動化；模組會拒絕結構損壞的回應，但無法保證第三方服務永久上線。

## 顯示模式、設定與快捷鍵

每個翻譯區域可獨立選擇：只顯示原文、只顯示翻譯、原文加翻譯，以及使用 AI 或機翻。

Fabric 1.17.1 至 26.2 與三個 NeoForge JAR 提供：

- 可搜尋的目標語言與機翻來源。
- 批次等待時間、每個引擎的請求冷卻與失敗退避。
- AI Base URL、模型、選填 API Key、詞彙表與 GT 回退選項。
- 目前 Provider／語言分區的快取清理。
- 等待、成功、失敗、回退狀態偵錯疊加層。
- `G`：快速切換原文／翻譯。
- `R`：重新翻譯游標下物品。
- `P`：掃描並翻譯目前畫面的按鈕與選項。
- 未綁定快捷鍵：開啟翻譯設定。

Fabric 1.14.4、1.15.2、1.16.5 提供精簡設定頁與 `G`；Forge 1.12.2、1.13.2 使用 `G` 與 `config/mctranslator-forge-legacy.json`。

為避免重複翻譯 Minecraft 已本地化的文字，Fabric 1.17.1 至 26.2 與三個 NeoForge JAR 會跳過原版語言、外觀、音效、控制、聊天、資源包與無障礙設定頁；原版顯示／影像設定與第三方模組畫面仍可翻譯。

## 批次、驗證與格式保護

- 預設批次等待時間為 **5000 ms**；設成 `0` 代表下一個 client tick 送出，仍不會在渲染 hook 內直接做網路 I/O。
- 單一批次安全輸入上限為 **1400 字元**，只會在完整物品名稱、提示行或段落之間切分，絕不從項目中間截斷。
- 每個項目都有成對起訖邊界；換行、段落、顏色區段、數字、時間、URL、玩家名稱與其他不可翻譯 token 會先受保護。
- 回應必須通過項目數量、順序、邊界、標記、段落與 token 驗證，才會寫入語意／樣式快取。
- 邊界遺失、交錯、順序錯誤、標記損壞或錨點外多出文字時，結果會被拒絕；模組可以縮小批次重試，但不會把一筆翻譯寫進另一筆快取。
- 相同內容的並行請求會合併；游標下物品與玩家正在互動的內容具有較高優先級。

## 快取、冷卻與偵錯

- 機翻快取依「機翻來源＋目標語言」分區；AI 快取依目標語言分區。
- Google 保留既有檔名，例如 `mctranslator-cache-zh-tw.json`；其他來源使用獨立檔案，例如 `mctranslator-cache-youdao-zh-tw.json`。
- 預設每個引擎請求冷卻為 **10000 ms**。可選值：`0 / 1000 / 2000 / 4000 / 6000 / 8000 / 10000 ms`。
- AI 與機翻分別計時；`0` 只關閉主動冷卻，不會關閉批次等待。
- 失敗項目使用退避與可見性需求，不會每個渲染影格重送。
- 偵錯原因可區分 `429 rate limit`、`HTTP 5xx`、認證、逾時／網路、邊界／順序損壞、段落遺失、格式／token 遺失、空回應與 `unknown`。

## 隱私與 API Key

- 只有需要翻譯的文字會送到選定來源；模組不修改伺服器資料或玩家送出的聊天。
- AI 文字會送往使用者設定的 Base URL；API Key 儲存在本機 Minecraft 設定資料夾。
- 不要把 API Key 放進 Issue、聊天、截圖、`latest.log` 或崩潰報告。回報前請遮蔽 URL 查詢參數、Authorization header 與設定檔金鑰。
- 玩家名稱遮罩會保留已辨識的線上名稱並避免當成一般翻譯文字送出，但任意伺服器自訂文字仍可能包含玩家或伺服器提供的內容。

## 從原始碼建置

16 個目標是彼此獨立的 Gradle 專案，因為不同 Minecraft、Fabric、Forge 與 NeoForge API 不具二進位相容性。共用核心與平台責任邊界請見 [ARCHITECTURE.md](ARCHITECTURE.md)；專案目錄、JDK、建置與發布驗證方式請見 [PACKAGING.md](PACKAGING.md)。

## 回報問題

請在 [GitHub Issues](https://github.com/DragonMeow1012/MinecraftTranslator/issues) 提供：完整 JAR 檔名、Minecraft／Loader／Java 版本、受影響畫面或模組、重現步驟、原文與預期結果、目標語言、翻譯來源、批次與冷卻設定，以及移除敏感資料後的 `latest.log`、偵錯疊加層與截圖。
