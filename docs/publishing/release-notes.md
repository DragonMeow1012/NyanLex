# NyanLex Translator 1.0.0

The first release under the NyanLex Translator name, with 18 Minecraft/loader builds: 12 Fabric, 4 NeoForge and 2 Forge. Install only the JAR matching your game version and loader. Fabric builds also require Fabric API.

- Translates chat, item tooltips and supported screen text, with reusable local translations. Supports Google machine translation, OpenAI-compatible AI providers and optional local Codex integration.
- Online translation starts disabled on new installations. Machine translation outside chat is requested manually with `R` or `P`; AI can translate automatically. Existing translations remain visible.
- Reuses completed AI translations across provider/model changes and downloaded translation packs, avoiding requests for text already translated.
- Modern builds include full-content warmup with selectable categories, including readable interface content loaded in the current world. Rate-limit pauses offer a manual continue action; Google's 429 protection remains in place.
- Adds translation import/export, optional translation packs, a redesigned settings screen and first-run setup. Legacy builds have a reduced interface and do not include translation-pack downloads or full-content warmup.
- Migrates settings and caches saved under earlier project names while preserving the originals.

See the project page for privacy details and `SHA256SUMS.txt` for file checksums. All 18 targets were built and their packaged metadata and hashes checked. This does not claim an in-game playthrough of every target.

## 繁體中文

這是改名為 NyanLex Translator 後的第一版，包含 18 個 Minecraft／Loader 版本：Fabric 12 個、NeoForge 4 個、Forge 2 個。請只安裝符合遊戲版本與 Loader 的單一 JAR；Fabric 另需 Fabric API。

- 翻譯聊天、物品提示與支援的介面文字，並重複利用本機已有譯文。支援 Google 機器翻譯、OpenAI 相容 AI 服務與選用的本機 Codex 整合。
- 首次安裝預設關閉連線翻譯。機器翻譯在聊天以外的內容由 `R`／`P` 手動觸發；AI 可自動翻譯，已有譯文仍會顯示。
- 切換 AI 服務／模型、使用下載翻譯包時沿用已完成譯文，避免重複送出已有翻譯。
- 現代版本提供可選分類的「全內容預熱」，包含目前世界已載入、可讀取的介面內容。限流暫停可手動繼續，Google 的 429 保護維持不變。
- 提供翻譯匯入／匯出、選用翻譯包、新設定介面與首次使用引導。舊版介面較精簡，不包含翻譯包下載與全內容預熱。
- 自動移轉舊專案名稱下的設定與快取，保留原檔。

隱私說明請見專案頁面；檔案雜湊見 `SHA256SUMS.txt`。18 個目標均完成建置、成品 metadata 與雜湊檢查；未宣稱全部完成實機遊玩測試。
