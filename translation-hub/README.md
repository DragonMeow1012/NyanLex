# Nyanlex Translation Hub / 翻譯倉庫

[繁體中文](#繁體中文) | [English](#english)

## 繁體中文

### 這是什麼

本資料夾收錄由社群與 AI 產生的**譯文**，供 Nyanlex 模組在使用者**主動按下下載**後取用。

- **不收錄、不散布任何原文。** 檔案只含「原文內容的 SHA-256 雜湊 → 譯文」，無法由檔案還原原文，也不含玩家名稱或範例句。原文的著作權屬各原權利人（遊戲、伺服器、模組作者）。
- **非官方。** 與 Mojang、Microsoft、任何伺服器（如 Hypixel）或模組作者**沒有**隸屬、合作或背書關係。
- **授權：** 本資料夾內的資料以 [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)（姓名標示－非商業性－相同方式分享）授權，詳見同目錄 `LICENSE`。程式碼仍為 repo 根目錄的 MIT 授權，不受影響。
- 譯文為機器／社群產生，可能有誤，不保證正確。
- **內容由維護者整理與更新。** 模組不會上傳玩家的翻譯，玩家端只有下載。

### 資料夾結構

```
translation-hub/
  index.json                      各檔的筆數、大小與 sha256（檔案本身的）
  servers/<host>/<lang>.json      伺服器（正規化網域，如 hypixel.net）
  modpacks/<slug>/<lang>.json     模組包
  mods/<modid>/<lang>.json        單一模組（模組 id 與載入器一致）
  LICENSE
```

### `<lang>.json` 格式（schema 2）

```json
{"schema":2,"format":"hub-hash-v1","hash":"sha256","language":"zh-tw","rows":1234,
 "entries":{"<64 位小寫十六進位>":"譯文", ...}}
```

| 欄位 | 說明 |
|---|---|
| `schema` | 固定 `2`。schema 1（含原文）的舊檔一律被客戶端拒收 |
| `format` | 固定 `hub-hash-v1` |
| `hash` | 雜湊演算法，`sha256` |
| `language` | 目標語言，如 `zh-tw` |
| `rows` | 筆數 |
| `entries` | 鍵為 `sha256(正規化快取 key)`（UTF-8），值為譯文；譯文內的 `⟦n⟧`、`⟦MT⟧` 等為程式內部保護 token，請勿改動 |

下載端只在查詢當下用本機的原文 key 計算雜湊比對，並在命中時驗證 token 結構與網址，不符的列直接丟棄。

### 下架與移除

權利人（伺服器營運者、模組作者等）可於本 GitHub repo 開 Issue，**標題格式**：

```
[Takedown] <伺服器網域或模組 id> <語言>
```

請提供：伺服器／模組、語言、您與該內容的關係、聯絡方式（可用 [下架申請範本](../.github/ISSUE_TEMPLATE/hub-takedown.md)）。我們會移除相關資料並自 `index.json` 取消列出。

## English

### What this is

This folder holds **translated text** produced by the community and by AI, fetched by the Nyanlex mod only after the user **explicitly presses download**.

- **No original text is stored or distributed.** Files contain only `SHA-256 hash of the source content -> translation`; the source cannot be recovered from a file, and no player names or sample sentences appear. Copyright in the original text belongs to its owners (the game, server and mod authors).
- **Unofficial.** Not affiliated with, endorsed by or associated with Mojang, Microsoft, any server (for example Hypixel) or any mod author.
- **License:** data in this folder is licensed under [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) (see `LICENSE` here). The program code remains MIT-licensed at the repository root and is unaffected.
- Translations are machine/community generated and may contain errors.
- **Content is curated and updated by the maintainers.** The mod never uploads a player's translations; the player side only downloads.

### Layout

```
translation-hub/
  index.json                      row count, size and sha256 (of the file itself) per file
  servers/<host>/<lang>.json      server (normalized host, e.g. hypixel.net)
  modpacks/<slug>/<lang>.json     modpack
  mods/<modid>/<lang>.json        single mod (same id the loader reports)
  LICENSE
```

### `<lang>.json` format (schema 2)

```json
{"schema":2,"format":"hub-hash-v1","hash":"sha256","language":"zh-tw","rows":1234,
 "entries":{"<64 lowercase hex>":"translation", ...}}
```

| Field | Meaning |
|---|---|
| `schema` | Always `2`. Schema 1 files (which contained source text) are rejected by the client |
| `format` | Always `hub-hash-v1` |
| `hash` | Hash algorithm, `sha256` |
| `language` | Target language, e.g. `zh-tw` |
| `rows` | Entry count |
| `entries` | Key is `sha256(normalized cache key)` (UTF-8); value is the translation. `⟦n⟧`, `⟦MT⟧` etc. are internal protection tokens, leave them intact |

The client hashes the local source key only at lookup time and, on a hit, validates token structure and URLs against that local key; non-matching rows are dropped.

### Takedown

Rights holders (server operators, mod authors, ...) can open a GitHub Issue in this repository with the **title format**:

```
[Takedown] <server host or mod id> <language>
```

Please include the server/mod, the language, your relationship to the content and a contact (you may use the [takedown template](../.github/ISSUE_TEMPLATE/hub-takedown.md)). We will remove the data concerned and delist it from `index.json`.
