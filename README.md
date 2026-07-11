# Minecraft Translator

[English README](README_EN.md)

Minecraft 用戶端即時翻譯模組，可翻譯聊天訊息、物品提示與遊戲介面文字。

Minecraft Translator 支援 Google 翻譯及 OpenAI 相容的 AI API，會盡量保留
Minecraft 原有的文字顏色、格式與互動事件，不會修改伺服器資料或玩家送出的訊息。
設定介面會跟隨 Minecraft 目前使用的語言，翻譯目標語言則使用可搜尋、接近原版
Minecraft 的語言清單。

## 下載與安裝

1. 前往[最新 GitHub Release](https://github.com/DragonMeow1012/MinecraftTranslator/releases/latest)。
2. 下載與你的 **Minecraft 版本及模組載入器完全相符**的 JAR。
3. 安裝對應的 Forge、Fabric 或 NeoForge；Fabric 版還需要相符版本的 Fabric API。
4. 將 JAR 放入遊戲實例的 `mods` 資料夾，然後啟動 Minecraft。

每個 JAR 都只對應檔名標示的版本。請勿將 1.21.1 的 JAR 裝到 1.21.11，
也不要混用 Fabric、Forge 與 NeoForge 版本。

## 支援版本

| Minecraft | 載入器 | Java | Release 檔案 |
| --- | --- | ---: | --- |
| 1.12.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.12.2.jar` |
| 1.13.2 | Forge | 8 | `mctranslator-1.0.2-Forge-1.13.2.jar` |
| 1.14.4 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.14.4.jar` |
| 1.15.2 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.15.2.jar` |
| 1.16.5 | Fabric | 8 | `mctranslator-1.0.2-Fabric-1.16.5.jar` |
| 1.17.1 | Fabric | 16 | `mctranslator-1.0.2-Fabric-1.17.1.jar` |
| 1.18.2 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.18.2.jar` |
| 1.19.4 | Fabric | 17 | `mctranslator-1.0.2-Fabric-1.19.4.jar` |
| 1.20.1 | Fabric / NeoForge | 17 | 選擇相符載入器的 JAR |
| 1.21.1 | Fabric / NeoForge | 21 | 選擇相符載入器的 JAR |
| 1.21.11 | Fabric | 21 | `mctranslator-1.0.2-Fabric-1.21.11.jar` |
| 26.1.2 | Fabric | 25 | `mctranslator-1.0.2-Fabric-26.1.2.jar` |
| 26.2 | Fabric / NeoForge | 25 | 選擇相符載入器的 JAR |

## 功能

- 非同步翻譯聊天訊息、物品名稱與物品提示，不阻塞畫面渲染。
- 新版支援計分板、名稱標籤、Boss Bar、標題、動作列、書本、講台及自訂介面文字。
- 各類文字可分別設定只顯示原文、只顯示翻譯或同時顯示原文與翻譯。
- 支援 Google 翻譯及可自行設定的 OpenAI 相容 AI 服務。
- 翻譯目標語言可自動跟隨 Minecraft 目前語言。
- 使用接近 Minecraft 原版的翻譯目標語言介面，並支援搜尋。
- 盡量保留顏色、格式、點擊與懸停事件、圖示、數字、時間、網址、玩家名稱及版面。
- 記憶體與磁碟快取可減少重複翻譯請求。
- 玩家名稱遮罩可避免將線上玩家 ID 傳給翻譯服務。
- 支援 AI 流量限制備援及手動重新翻譯。
- 玩家正在看的文字會以高優先插隊，先於尚未執行的背景預翻譯送出。
- 可開啟翻譯請求 Debug 浮窗，檢查 AI／GT 請求、成功、失敗與回退狀態。
- AI 精翻可選「停用 GT 回退」；開啟後暫時失敗會等待並重試 AI，不改用 GT。
- 新安裝預設 Google 與 AI 各自使用 2000 ms 請求冷卻，多組 API Key 會輪替使用，
  遇到 429／無效金鑰時只隔離有問題的金鑰。
- 物品預翻譯只處理目前開啟容器中有效、可見的欄位，不再於每個 tick 掃描整個背包。

## 設定與按鍵

新版可從 **選項 → 翻譯設定** 進入模組設定。遊戲內可調整翻譯模式、
目標語言、AI 服務、模型、API Key、請求冷卻、快取與按鍵設定。

預設按鍵：

| 按鍵 | 功能 |
| --- | --- |
| `G` | 切換顯示原文或翻譯 |
| `R` | 重新翻譯滑鼠指向的物品 |
| `P` | 掃描並翻譯目前畫面 |
| 未綁定 | 循環切換翻譯顯示模式 |

所有按鍵都可以在 Minecraft 控制設定或模組的按鍵設定中修改。

## 各版本限制

- Forge 1.12.2 與 1.13.2 是以聊天和物品提示為主的相容版，使用 `G` 鍵切換，
  Java 8 相容設定存放於 `config/mctranslator-forge-legacy.json`；其中包含 AI、
  多 API Key、停用 GT 回退、2000 ms 冷卻與 Debug 浮窗選項。
- Fabric 1.14.4 至 1.16.5 使用 Java 8 專用文字攔截器；遊戲內可切換全表面 AI／GT、
  停用 GT 回退、冷卻及 Debug，AI 端點、模型與金鑰也會保存在舊版設定檔。
- Fabric 1.21.11 已針對該版的 render-state、void 繪圖 API 另行重寫 Boss Bar、
  實體名稱、計分板、聊天及介面攔截，不與 1.21.1 共用錯誤描述符。
- 所有 Release JAR 都已通過編譯、重新映射、載入器 metadata、Minecraft 版本限制及
  Java bytecode 檢查。與其他模組、資源包或伺服器自訂介面搭配時仍可能出現相容性差異。

## 隱私

只有需要翻譯的文字會傳送到設定中選擇的翻譯服務。此模組完全在用戶端運作，
不會修改玩家送出的聊天訊息或伺服器資料。若使用 AI 翻譯，資料處理方式依該 API
服務商的隱私政策為準。API Key 儲存在本機 Minecraft 設定目錄，請勿分享含有
API Key 的設定檔。

## 從原始碼建置

由於不同 Minecraft 與載入器版本之間不具二進位相容性，每個支援版本都是獨立的
Gradle 專案。專案、Java、載入器與成品目錄對照請參考 [PACKAGING.md](PACKAGING.md)。

版本分支命名格式：

```text
mc/<minecraft-version>-<loader>
```

整合發布分支為 `release/1.0.2-current`，建置成品集中在 `mods-jar/1.0.2`。

## 回報問題

請至 [GitHub Issues](https://github.com/DragonMeow1012/MinecraftTranslator/issues)
回報，並附上：

- Minecraft 版本
- Fabric、Forge 或 NeoForge 版本
- Java 版本
- 其他已安裝模組
- 相關用戶端日誌或崩潰報告

請勿在日誌或截圖中公開 API Key。
