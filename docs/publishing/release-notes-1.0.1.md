# NyanLex Translator 1.0.1

## v1.0.1 improvements

- Chat input translation now also works for commands: start the chat box with `/`, such as a party chat command. The translation is inserted at the cursor and never overwrites what you have already typed.
- Server story dialogue boxes drawn with a custom font now show the translation inside the box, keeping per-word colors.
- Floating-text nameplates, such as NPC names, are translated. Requires Minecraft 1.19.4 or newer.
- **Translation manager**: search, edit, restore or delete saved translations on the Advanced settings page. Hand-edited translations are never overwritten by automatic translation and survive clearing the cache.
- **Keep titles and dialogue in chat**: a new switch that keeps fleeting title and dialogue translations in your chat, visible only to you.
- Fixed translated player chat showing the sender's name twice on Fabric 1.19.3 and newer.
- While the original is shown with `G`, no new translation requests are sent.
- Custom fonts that lack Chinese characters now fall back to the game's built-in glyphs instead of showing boxes.
- Text that appears letter by letter is translated once, after it finishes, which greatly reduces requests and token use.
- Icons in item names and descriptions no longer turn into boxes after translation.
- Retranslating the whole interface now also retranslates nameplates, and text that is already translated is no longer sent again as if it were the original.
- On Minecraft 1.17 through 1.21.8 the default key for translating the screen is now `\` instead of `P`, which is also the vanilla Social Interactions key; newer versions keep `P`, and key bindings you have already saved are unchanged.
- The Claude provider, with its thinking-strength and Fast mode settings, is now available on every build for Minecraft 1.17 and newer.
- Fixed a crash on startup on Fabric 1.17 through 1.19.4 with Fabric Loader 0.16.0 through 0.16.13.
- Fixed a crash on startup on Fabric 26.3 with Fabric Loader 0.19.3 and 0.19.4.
- Fixed NeoForge 1.20.1, which did not work correctly, and Fabric 1.17, which could not start.
- Fixed Forge 1.13.2, which could not start.
- Plain action bar text is now translated on Fabric 1.17 through 1.19.
- Held item names on NeoForge 1.20.1 through 1.21.8, and the scoreboard on some of those versions, are now translated.
- Tooltips that do not belong to an item are now translated on Fabric 1.17 through 1.19.4.
- Typing in the settings search box no longer triggers hotkeys on Minecraft 1.20 through 1.20.4.
- A tooltip containing an empty line can no longer switch tooltip translation off for the rest of the session.

## Version limits

The legacy targets (Fabric 1.14.4-1.16.5 and Forge 1.12.2-1.13.2, which covers Minecraft 1.12.2, 1.13.2, 1.14.4, 1.15.2 and 1.16.5) include only the chat input translation improvements. The Translation manager and the other new features are not supported there.

Includes **62 Minecraft/loader builds**: 37 Fabric, 23 NeoForge and 2 Forge. Install only the JAR matching your game version and loader; Fabric also requires Fabric API. See the [download table](https://github.com/DragonMeow1012/NyanLex#下載).

Hands-on play testing was done on Minecraft 26.1.2 / Fabric, and automated in-world tests also ran on Fabric 1.17.1, 1.18.2, 1.19.4, 1.20.1, 1.21.1, 1.21.11, 26.2 and 26.3 and on NeoForge 1.21.1, 26.2 and 26.3. The other builds passed build, static and startup checks, but were not tested in a world.

## 繁體中文

### v1.0.1 優化

- 聊天輸入翻譯支援指令：聊天框以 `/` 開頭（例如隊伍聊天指令）也能使用，譯文會插入游標位置，不會覆蓋已輸入的內容。
- 伺服器以自訂字型畫出的劇情對話框，現在會直接在框內顯示譯文，並保留逐詞顏色。
- 浮空文字的名牌（例如 NPC 名稱）會翻譯，需要 Minecraft 1.19.4 以上。
- **譯文管理**：在設定的「進階」頁搜尋、修改、還原或刪除已存的譯文；手動修改的譯文不會被自動翻譯覆蓋，清除快取時也會保留。
- 新開關 **標題與對話留在聊天欄**：把一閃而過的標題與對話譯文留在聊天欄，只有自己看得到。
- 修正 Fabric 1.19.3 以上，玩家聊天的譯文把發言者名稱顯示兩次。
- 按 `G` 顯示原文期間，不送出新的翻譯請求。
- 自訂字型缺少中文字時，改用遊戲內建字形顯示，不再出現方框。
- 逐字出現的文字只在打完後翻譯一次，大幅減少請求與 token 用量。
- 物品名稱與說明裡的圖示，翻譯後不再變成方框。
- 「全介面重新翻譯」現在也會重翻名牌，已翻譯的文字不會再被當成原文重送。
- Minecraft 1.17～1.21.8 的翻譯畫面鍵預設改為 `\`（`P` 與原版「社交互動」同鍵），1.21.9 以上維持 `P`；已存的按鍵設定不變。
- Claude 服務商與它的思考強度、快速模式設定，補齊到 Minecraft 1.17 以上的所有版本。
- 修正 Fabric 1.17～1.19.4 搭配 Fabric Loader 0.16.0～0.16.13 時啟動即崩潰。
- 修正 Fabric 26.3 搭配 Fabric Loader 0.19.3、0.19.4 時啟動即崩潰。
- 修正 NeoForge 1.20.1 無法正常運作，以及 Fabric 1.17 無法啟動。
- 修正 Forge 1.13.2 無法啟動。
- Fabric 1.17～1.19 現在會翻譯一般的 action bar 文字。
- NeoForge 1.20.1～1.21.8 的手持物品名稱，以及其中部分版本的計分板，現在會翻譯。
- Fabric 1.17～1.19.4 現在會翻譯不屬於物品的懸浮提示。
- Minecraft 1.20～1.20.4 在設定頁搜尋框打字，不再觸發快捷鍵。
- 說明欄含空行時，不會再讓該場的說明欄翻譯停用。

### 版本限制

舊版目標（Fabric 1.14.4～1.16.5、Forge 1.12.2～1.13.2，即 Minecraft 1.12.2、1.13.2、1.14.4、1.15.2、1.16.5）只包含聊天輸入翻譯的改進，不支援譯文管理與其餘新功能。

包含 **62 個 Minecraft／Loader 版本**：Fabric 37 個、NeoForge 23 個、Forge 2 個。請只安裝符合遊戲版本與 Loader 的單一 JAR；Fabric 另需 Fabric API。完整清單見[下載表](https://github.com/DragonMeow1012/NyanLex#下載)。

親自遊玩確認的是 Minecraft 26.1.2／Fabric，另有 Fabric 1.17.1、1.18.2、1.19.4、1.20.1、1.21.1、1.21.11、26.2、26.3 與 NeoForge 1.21.1、26.2、26.3 做過自動化的進世界測試；其餘版本通過建置、靜態驗證與啟動測試，沒有進世界測試。
