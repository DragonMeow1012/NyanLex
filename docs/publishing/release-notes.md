# NyanLex Translator 1.0.0

The first release under the NyanLex Translator name brings together bilingual chat, item and screen translation, reusable translations and the new chat composer.

## Write in your language. Join the conversation.

Enable **Chat input translation**, open chat, and write in the floating panel. Choose a target language (English by default), then select **Translate & fill**. Review or edit the result in the normal chat bar and press Enter when you are ready to send.

- Drag the panel by its title bar; its position is remembered.
- The outgoing language is independent of the language used to read game content.
- Uses your selected chat translation service and existing request pacing. Google rate-limit protection still applies.
- Drafts are sent for translation only when you submit them in the panel. They are not written to translation caches or exports.
- Editing the draft, changing the service, or closing chat discards stale results. Failed or overlong translations leave your draft available to edit and retry.
- Updated the English and Traditional Chinese README with feature cards and clearer explanations of the benefits.

Also included: Google, Gemini, OpenAI, DeepSeek, OpenAI-compatible services and ChatGPT/Codex sign-in; translation import/export; and, on modern versions, downloadable translation packs and full-content warmup with selectable categories. Completed translations remain reusable after switching AI providers or models.

Includes all **18 Minecraft/loader builds**: 12 Fabric, 4 NeoForge and 2 Forge. Install only the JAR matching your game version and loader; Fabric also requires Fabric API. Chat input translation starts disabled and requires online translation to be enabled.

Validation covers automated tests, all-target builds, packaged classes and metadata, and SHA-256 checks. It does not include an in-game playthrough of every target.

## 繁體中文

這是改名為 NyanLex Translator 後的首版，整合雙語聊天、物品與介面翻譯、既有譯文沿用，以及聊天輸入翻譯浮窗。

### 用自己的語言，自在回話

開啟 **聊天輸入翻譯** 後，打開聊天框，在小浮窗寫下想說的話。選擇目標語言（預設英文），按 **翻譯並填入**，譯文就會出現在下方聊天欄；確認或修改後，再按 Enter 送出。

- 拖曳標題列即可移動浮窗，下次開啟會記住位置。
- 發話語言與閱讀遊戲內容的翻譯語言分開設定。
- 沿用聊天的翻譯服務、送出間隔與限流保護；Google 的 429 保護持續生效。
- 只有主動提交浮窗草稿時才會要求翻譯，草稿不寫入翻譯快取或匯出檔。
- 修改草稿、切換服務或關閉聊天後，過時結果不會覆蓋聊天欄；翻譯失敗或超過聊天長度時會保留草稿，方便修改重試。
- 中英文 README 加入特色卡片，以遊玩情境介紹功能與好處。

同時提供 Google、Gemini、OpenAI、DeepSeek、OpenAI 相容服務與 ChatGPT／Codex 登入，以及翻譯匯入／匯出。現代版本另有翻譯包下載與可選分類的全內容預熱；切換 AI 服務或模型後，已完成的譯文仍可沿用。

包含 **18 個 Minecraft／Loader 版本**：Fabric 12 個、NeoForge 4 個、Forge 2 個。請只安裝符合遊戲版本與 Loader 的單一 JAR；Fabric 另需 Fabric API。聊天輸入翻譯預設關閉，使用時也需開啟線上翻譯。

驗證範圍包含自動化測試、全版本建置、成品 class 與 metadata、SHA-256 檢查；未宣稱所有版本均完成實機遊玩測試。
