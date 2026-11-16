# 巴哈姆特文章草稿

## 建議標題

【自製模組】不用再切出去查英文！NyanLex Translator 即時翻譯聊天、物品、任務與介面

## 正文

【首圖：`docs/brand/nyanlex-banner.svg`】

大家好，我是 **NyanLex Translator** 的作者。

玩國外模組包或多人伺服器時，我最常遇到的不是打不過怪，而是看不懂眼前那一大段文字：物品能力、任務說明、書本線索，還有隊友正在聊什麼。為了查一句話一直切出遊戲，久了真的很打斷遊玩節奏。

所以我做了 NyanLex Translator——一個純客戶端的 Minecraft 翻譯模組，讓翻譯直接出現在原本的遊戲畫面裡。

伺服器與一起玩的朋友都不需要安裝。

---

## 第一次使用，跟著快速設定就能開始

第一次進入設定時，NyanLex Translator 會用四個步驟帶玩家完成基本選擇，不需要一開始就研究所有進階選項；之後也能隨時從「翻譯設定」重新調整。

【流程插圖 1：歡迎使用 NyanLex Translator／開始快速設定】

先選擇遊戲內容要翻成的語言，再決定使用機器翻譯、AI，或暫時不開啟線上翻譯。

【流程插圖 2：選擇機翻、AI 或先不要】

選擇 AI 時，可以使用 Gemini、OpenAI、DeepSeek、Claude、自訂／本機模型，或 ChatGPT／Codex 登入；模型名稱可手動輸入，也可以直接讀取帳號目前可用的模型清單。

【流程插圖 3：AI 設定與模型選擇】

OpenAI API、ChatGPT／Codex 與 Claude 可另外開啟快速模式。第一次啟用時會先提醒：快速模式可能增加 API 帳單費用或消耗更多帳號額度。

【流程插圖 4：快速模式費用／額度提醒】

最後可以分別選擇聊天、物品、記分板、Boss 血條、標題與其他區域要顯示原文、譯文或雙語，以及使用機翻或 AI。

【流程插圖 5：各區域的顯示方式與翻譯服務】

設定完成頁會再次顯示翻譯物品、翻譯畫面、切換原文三個核心快捷鍵；不習慣預設鍵位也能立即修改。

【流程插圖 6：設定完成與快捷鍵說明】

---

## 它可以翻什麼？

- 收到的聊天訊息，也能保留原文和譯文一起看
- 物品名稱、能力與長篇提示框
- 模組包任務、書本、告示牌與一般遊戲介面
- 記分板、Boss Bar、Title、Action Bar、名牌與部分 HUD
- 伺服器以自訂字型畫出的劇情對話框，直接在框內顯示譯文並保留逐詞顏色
- 浮空文字的名牌（例如 NPC 名稱），需要 Minecraft 1.19.4 以上
- 自己輸入的聊天內容，可先翻成對方的語言再填回聊天欄；開頭是 `/` 的指令也能用

翻譯不會直接覆蓋原文。按 `G` 可以切換原文／譯文；指著物品按 `R` 可翻譯或重新翻譯，按翻譯畫面鍵（Minecraft 1.17～1.21.8 預設是 `\`，其餘版本是 `P`）則可處理目前畫面可見的介面與 HUD。

設定的「進階」頁有「譯文管理」，可以搜尋、修改、還原或刪除已存的譯文，手動修改的不會被自動翻譯覆蓋。「顯示」頁的「標題與對話留在聊天欄」開關，則會把一閃而過的標題與對話譯文留在聊天欄，只有自己看得到。

不同 Minecraft 舊版的快捷鍵與功能會有少量差異，完整表格放在 GitHub 說明頁。

---

## 實際遊戲畫面

### 聊天原文與譯文並列

【插圖：`docs/images/promo/better-mc-bilingual-chat.png`】

保留原句的好處是遇到人名、伺服器術語或不自然的翻譯時，仍然能直接對照，不會只剩下一段不知道原文是什麼的機翻。

### 物品提示翻譯前／後

【左右對照插圖：
`docs/images/promo/better-mc-item-original.png`
`docs/images/promo/better-mc-item-translated.png`】

同一件模組物品翻譯前後，名稱與說明的顏色會盡量保留。長段落也會依原本結構重新排版，不只是把所有文字擠成一行。

### 模組包任務翻譯前／後

【左右對照插圖：
`docs/images/promo/modpack-quest-en.png`
`docs/images/promo/modpack-quest-zh-TW.png`】

任務名稱、說明與原有的彩色關鍵字可以一起處理。已翻過的內容會存進本機快取，下次看到時不必重複送出。

### 用中文打字，再翻成對方的語言

【插圖：`docs/images/promo/better-mc-chat-composer.png`】

聊天浮窗可以自由拖曳。輸入中文、選擇目標語言後，譯文只會填入 Minecraft 原本的聊天欄，**不會替玩家自動送出**；確認或修改完成後，再由自己按 Enter。

---

## 翻譯來源可以自己選

NyanLex Translator 不綁定單一服務，目前支援：

- 不需 API Key 的 Google 機器翻譯
- Gemini、OpenAI、DeepSeek、Claude（Anthropic API）
- ChatGPT／Codex 登入
- OpenRouter 與其他 OpenAI 相容服務
- Ollama、LM Studio 等本機模型

【插圖：`docs/images/promo/better-mc-translation-settings.png`】

API 服務可讀取目前帳號可用的模型清單，也保留手動輸入模型名稱的方式。OpenAI API、ChatGPT／Codex 與 Claude 另有可選的「快速模式」，預設關閉；第一次啟用時會提醒可能增加帳單費用或帳號額度消耗。

如果不想把文字傳到線上，新安裝時可以保持關閉；已有的本機快取與翻譯包仍可離線顯示。

---

## 支援版本

目前 1.0.0 共提供 **62 個對應檔案**：

- **Fabric：37 個**——Minecraft 1.14.4、1.15.2，以及 1.16.5～26.3 的所有正式版本
- **NeoForge：23 個**——Minecraft 1.20.1～26.3 的所有正式版本
- **Forge：2 個**——Minecraft 1.12.2、1.13.2

請只下載與 Minecraft 版本及 Loader 完全相符的單一 JAR；Fabric 版另需對應版本的 Fabric API。

舊版目標採用較精簡的設定介面，沒有翻譯包下載與全內容預熱，但核心翻譯功能仍可使用。在 1.0.1 的新功能中，舊版目標只包含聊天輸入翻譯的改進，不支援譯文管理與其餘新功能。

---

## 隱私與費用提醒

- 首次安裝時，線上翻譯預設關閉。
- 開啟後，選定內容會送往玩家自己選擇的服務；聊天可能包含私訊。
- API Key 只保存在本機設定，輸入欄會遮罩顯示。
- AI、API 或快速模式是否收費，取決於各服務商與帳號方案。
- 翻譯結果不保證完全正確；交易、規則等重要內容建議保留原文確認。
- 第三方模組的繪製方式很多，少數特殊介面仍可能無法完整擷取或保留版面，歡迎附截圖回報。

---

## 下載與回報

**Modrinth**  
https://modrinth.com/mod/nyanlex-translator

**CurseForge**  
https://www.curseforge.com/minecraft/mc-mods/nyanlex-translator

**GitHub Release（也可一次下載全部版本）**  
https://github.com/DragonMeow1012/NyanLex/releases/tag/v1.0.0

**完整說明與原始碼**  
https://github.com/DragonMeow1012/NyanLex

**問題回報**  
https://github.com/DragonMeow1012/NyanLex/issues

這是 NyanLex Translator 的第一個正式版本。希望它能讓大家少切幾次視窗、多看懂幾段任務，也更容易和不同語言的玩家一起玩。

如果你願意，也歡迎留言告訴我最想拿它翻譯哪個模組包，或哪些介面還需要加強！

---

## 發文前清單（不要貼入正文）

- 等 CurseForge 最新 62 個檔案全部通過審核並公開可下載後才發布。
- 忽略後台兩筆舊的 Fabric 1.19.2 重複退件紀錄；只核對最新 62 個正式檔案。
- 發文前重新確認 GitHub、Modrinth、CurseForge 三個下載連結。
- 將首圖與實機圖上傳到巴哈圖庫，依本文占位順序插入。
- 圖片順序：首圖 → 快速設定 6 張 → 聊天 → 物品原文／譯文 → 任務原文／譯文 → 聊天輸入 → 設定頁。
- 快速設定使用附件中的歡迎頁、翻譯方式、AI 設定、快速模式提醒、顯示方式、完成頁；略過兩張重複圖、選項入口和一般設定總覽。
- 檢查截圖沒有 API Key、帳號憑證、私人聊天或除錯資訊。
- 在真正按下巴哈「發表」前，先讓作者確認標題、首圖、圖片順序與最終預覽。

## 備選標題（不要貼入正文）

1. 【自製模組】讓 Minecraft 聊天、物品、任務都看得懂｜NyanLex Translator
2. 【Mod 分享】玩國外模組包不用一直查翻譯了｜NyanLex Translator
3. 【自製】讀懂模組包，也讓國外隊友讀懂你｜NyanLex Translator 1.0.0
