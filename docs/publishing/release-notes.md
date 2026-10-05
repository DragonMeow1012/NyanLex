# NyanLex Translator 1.0.0

The first release under the NyanLex Translator name brings together bilingual chat, item and screen translation, reusable translations and the new chat composer.

## Security and settings update

- Antigravity is hidden by default and can be enabled in Advanced settings after reading the account, service and privacy risks. Official CLI sign-in does not establish authorization for this third-party integration.
- CLI translation uses dedicated profiles, untrusted-text boundaries, tool restrictions and bounded process lifetimes. Antigravity rejects tool requests through a pre-execution hook. Selected scenarios were tested with CLI 1.2.16; this is not an operating-system sandbox or a guarantee for every CLI version.
- Antigravity and Gemini API notices have separate **Don't show again** checkboxes and clear confirmation labels. Selecting API-key mode no longer opens a notice or resets an existing Gemini model.
- Do-not-translate terms appear first in their settings section. Remote API endpoints require HTTPS; local loopback HTTP remains available. Automatic redirects are disabled.
- Reported CLI rate limits pause requests; unavailable remaining-allowance information does not block translation. Google machine translation retains its alternate-endpoint attempt, and API keys retain individual cooldowns and rotation.

## Write in your language. Join the conversation.

Enable **Chat input translation**, open chat, and write in the floating panel. Choose a target language (English by default), then select **Translate & fill**. Review or edit the result in the normal chat bar and press Enter when you are ready to send.

- The composer starts at the bottom right, preserves a saved position, and supports language search by name or code.
- Closing chat with Esc keeps the unfinished draft in memory for the current game session; quitting the game clears it.
- The mod UI supports English, Japanese, Traditional Chinese and Simplified Chinese, independently of the chosen translation target language.
- The outgoing language is independent of the language used to read game content.
- Uses your selected chat translation service and existing request pacing. Google rate-limit protection still applies.
- Drafts are sent for translation only when you submit them in the panel. They are not written to translation caches or exports.
- Editing the draft, changing the service, or closing chat discards stale results. Failed or overlong translations leave your draft available to edit and retry.
- Four complete README translations are available; Traditional Chinese remains the GitHub homepage.
- Long descriptions remain readable when an AI response loses only display-wrap markers. A single review can restore the layout; cached reads do not repeat the request, and numbers, protected names and colour markers must still survive validation.

Also included: Google, Gemini, OpenAI, DeepSeek, OpenAI-compatible services, ChatGPT/Codex sign-in, and Google sign-in through a separately installed Antigravity CLI on modern Fabric/NeoForge builds. Antigravity mode uses the signed-in Google account's allowance and obtains its model variants from the CLI. Translation import/export, downloadable translation packs and full-content warmup with selectable categories remain available. Completed translations remain reusable after switching AI providers or models.

## Compatibility and display fixes

- Added optional Google account sign-in through Antigravity CLI, with live model discovery, connection status, login/logout guidance and a bounded connection test. NyanLex does not scan global CLI logs for an account email.
- Open Translation Settings from the settings menu, or enter `/nyanlex` in chat if another UI mod hides that entry.
- Google machine translation now tries a compatible alternate endpoint once when the primary endpoint returns a 429 or block page.
- FTB Quests supports both the older public-instance API and the newer accessor API. Quest tabs, titles and body text now share one refresh path, so pressing `G` changes the displayed text—not only the mode indicator—and completed asynchronous translations appear without toggling twice.
- Jade/WAILA-style object-name overlays have a dedicated optional compatibility hook on supported modern builds.
- Advancement toasts and advancement announcements translate the advancement title while preserving the surrounding game message.
- The chat composer keeps the selected target language visible after translation.
- The translated counter now reports the durable accumulated translation total instead of stopping at the in-memory 5,000-entry cache limit. Warmup progress remains a separate count for the current job.

Includes **62 Minecraft/loader builds**: 37 Fabric, 23 NeoForge and 2 Forge. Install only the JAR matching your game version and loader; Fabric also requires Fabric API. Chat input translation starts disabled and requires online translation to be enabled.

## Expanded version coverage

| Loader | Minecraft releases |
| --- | --- |
| Fabric · 37 builds | Every stable release from 1.16.5 through 26.3, plus 1.14.4 and 1.15.2 |
| NeoForge · 23 builds | Every stable release from 1.20.1 through 26.3 |
| Forge · 2 builds | 1.12.2 and 1.13.2 |

NeoForge does not exist for Minecraft before 1.20.1. Minecraft snapshots and pre-releases are excluded; some NeoForge loader builds are beta. Each file targets exactly the Minecraft version in its name, not all versions between endpoints. See the [full download and Java requirements table](https://github.com/DragonMeow1012/NyanLex#下載矩陣).

The NeoForge 26.1.2 build is compiled against loader **26.1.2.109**. The chat composer no longer creates anonymous Mixin inner classes, avoiding the generated `ChatScreen$Anonymous` class-resolution failure encountered during startup. Other loader/API compatibility still depends on the exact build and installed mods.

Validation covers automated tests, per-target builds, packaged classes and metadata, Java levels, Mixin selectors and invocation targets, and file hashes. It does not include an in-game playthrough of every target or compatibility certification for every modpack.

## 繁體中文

這是改名為 NyanLex Translator 後的首版，整合雙語聊天、物品與介面翻譯、既有譯文沿用，以及聊天輸入翻譯浮窗。

### 安全與設定更新

- Antigravity 預設隱藏，可在進階設定閱讀帳號、服務與隱私風險後開啟。官方 CLI 登入不代表本第三方整合已取得授權。
- CLI 翻譯使用專用設定目錄、不可信文字邊界、工具限制與程序逾時。Antigravity 透過執行前掛鉤拒絕工具請求；已以 CLI 1.2.16 驗證特定情境，不代表作業系統沙箱或所有 CLI 版本的保證。
- Antigravity 與 Gemini API 說明各有獨立的「不再顯示」勾選框，確認按鈕文字已修正。切換 API 金鑰模式不再彈出通知，也不會重設既有 Gemini 模型。
- 不翻譯詞彙移至該設定區最上方。遠端 API 限用 HTTPS，本機回送位址仍可使用 HTTP，並停用自動重新導向。
- CLI 明確回報限流時暫停請求；無法查到剩餘額度不會阻止翻譯。保留 Google 備用入口嘗試，以及 API 金鑰各自冷卻與輪流使用的行為。

### 用自己的語言，自在回話

開啟 **聊天輸入翻譯** 後，打開聊天框，在小浮窗寫下想說的話。選擇目標語言（預設英文），按 **翻譯並填入**，譯文就會出現在下方聊天欄；確認或修改後，再按 Enter 送出。

- 浮窗預設位於右下角，已有儲存位置則保留；語言選單可搜尋名稱或代碼。
- 按 Esc 關閉聊天後，同次遊戲執行期間保留未完成草稿；結束遊戲後清除。
- 模組 UI 支援英文、日文、繁體中文、簡體中文，不限制遊戲內容的翻譯目標語言。
- 發話語言與閱讀遊戲內容的翻譯語言分開設定。
- 沿用聊天的翻譯服務、送出間隔與限流保護；Google 的 429 保護持續生效。
- 只有主動提交浮窗草稿時才會要求翻譯，草稿不寫入翻譯快取或匯出檔。
- 修改草稿、切換服務或關閉聊天後，過時結果不會覆蓋聊天欄；翻譯失敗或超過聊天長度時會保留草稿，方便修改重試。
- 提供四種語言的完整 README，GitHub 首頁維持繁體中文。
- 長篇說明只遺失自動換行標記時，先保留可讀譯文，再校正一次；讀取快取不會反覆重送。數值、受保護名稱與顏色標記仍需通過完整檢查。

同時提供 Google、Gemini、OpenAI、DeepSeek、OpenAI 相容服務與 ChatGPT／Codex 登入，以及翻譯匯入／匯出。現代版本另有翻譯包下載與可選分類的全內容預熱；切換 AI 服務或模型後，已完成的譯文仍可沿用。

### 相容性與顯示修正

- 現代 Fabric／NeoForge 版本新增選用的 Antigravity CLI Google 帳號登入，使用該帳號的 Antigravity 額度，並提供即時模型清單、連線狀態、登入／登出引導及有逾時限制的連線測試；不掃描全域 CLI 日誌取得信箱。
- 你可以從設定選單開啟翻譯設定；如果入口被其他 UI 模組隱藏，也可以在聊天欄輸入 `/nyanlex`。
- Google 機器翻譯的主要端點回傳 429 或阻擋頁面時，會改試一次相容的備用端點。
- FTB Quests 同時相容舊版公開實例 API 與新版存取方法。任務索引、標題和內文統一走同一條刷新路徑，按 `G` 時不再只有模式提示改變；背景翻譯完成後也會直接套用，不必再切換兩次。
- 支援的現代版本新增 Jade／WAILA 類物件名稱提示的專用選用相容掛鉤。
- 成就彈窗與聊天中的成就訊息會翻譯成就名稱，並保留遊戲原本的訊息格式。
- 聊天輸入翻譯完成後仍會顯示目前選定的目標語言。
- 已翻譯數量改為顯示永久保存的累積總數，不再受記憶體快取 5,000 筆上限影響；本次預熱進度仍另外計算。

包含 **62 個 Minecraft／Loader 版本**：Fabric 37 個、NeoForge 23 個、Forge 2 個。請只安裝符合遊戲版本與 Loader 的單一 JAR；Fabric 另需 Fabric API。聊天輸入翻譯預設關閉，使用時也需開啟線上翻譯。

完整支援範圍請以表格為準：Fabric 提供 Minecraft 1.16.5 至 26.3 的所有正式版本，NeoForge 提供 1.20.1 至 26.3 的所有正式版本；另保留 Fabric 1.14.4、1.15.2 與 Forge 1.12.2、1.13.2。每個 JAR 僅適用於檔名所示的 Minecraft 版本與 Loader。

### 補齊支援版本

- Fabric：Minecraft 1.16.5～26.3 的全部正式版本，並保留 1.14.4、1.15.2。
- NeoForge：Minecraft 1.20.1～26.3 的全部正式版本；更早的 Minecraft 沒有對應的 NeoForge。
- Forge：保留 Minecraft 1.12.2、1.13.2。

不包含 Minecraft 快照／預覽版；部分 NeoForge Loader 本身為 beta。每個 JAR 都對應檔名所示的精確遊戲版本，不代表同一檔案可通用整個範圍。

NeoForge 26.1.2 以 Loader **26.1.2.109** 編譯，並移除聊天浮窗中的匿名 Mixin 類別，修正啟動時 `ChatScreen$Anonymous` 類別解析失敗。其他 Loader／API 相容性仍取決於精確版本與已安裝模組。

驗證範圍包含自動化測試、逐版本建置、成品 class 與 metadata、Java 需求、Mixin 注入與呼叫目標，以及檔案雜湊；不代表所有版本均完成實機遊玩，或所有整合包都已通過相容性測試。
