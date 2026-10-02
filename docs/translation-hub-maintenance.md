# NyanLex 翻譯倉庫（translation-hub）資料製作與維護教學

> 讀者：專案維護者 DragonMeow，以及之後新開對話的 AI 助手。
> 目標：不必重新研究，照著做就能**製作、更新、驗證、發佈、下架**倉庫資料。
> 基準：分支 `release/nyanlex-1.0.0` @ `64cec54`，2026-10-02 驗證。倉庫當時有 17 個 mod 來源（合計 9,160 列）加 1 個伺服器來源（55,734 列）。之後依「只收簡單授權」規則移除了 9 個來源（見 §4.1），現在是 8 個 mod 來源（合計 561 列）加同一個伺服器來源。
> 本文件自己也遵守鐵則：範例一律用 `<modId>`、`<slug>`、`<host>` 這類代號，不寫真實模組名稱。

## 目錄

0. 先讀這裡（約定、環境、驗證標記）
1. 概觀
2. 鐵則清單
3. 流程 A：伺服器資料（從玩家 AI 快取匯出）
4. 流程 B：模組與光影包
5. 已知限制
6. 更新、下架、清除
7. 發佈
8. 委派範本（給未來 AI 助手）
9. 疑難排解
- 附錄 A：腳本全文（可直接存檔使用）
- 附錄 B：指令驗證狀態總表

---

## 0. 先讀這裡

### 0.1 一頁摘要

```
流程 A（伺服器）  玩家 AI 快取副本 ──hubExport──▶ servers/<host>/zh-tw.json ─▶ 檢查 ─▶ 提交
流程 B（模組）    Modrinth ─▶ 抽取 lang ─▶ 只翻缺的 ─▶ 自檢 ─▶ langPackBuild ─▶ 檢查 ─▶ 遊戲內驗證 ─▶ 提交
發佈              先問使用者 ─▶ 推 main ─▶ 等 5 分鐘 ─▶ 用 raw.githubusercontent.com 對 index
```

倉庫只存「`sha256(key)` → 譯文」，不存任何原文；所以**中間檔（含英文原文）、jar、zip、玩家快取副本永遠不進 repo**。

### 0.2 驗證標記

每個指令區塊都標一個狀態：

| 標記 | 意思 |
|---|---|
| **【已實測】** | 在我自己開的拋棄式 worktree（`hubdoc`，分支 `release/nyanlex-1.0.0` @ `64cec54`）用假資料或唯讀資料跑過，輸出符合文件所寫 |
| **【依程式碼】** | 沒有實際執行（例如要開遊戲、要推上 GitHub、要改 repo），但已逐行對照過程式碼 |

沒有標記的敘述，來源會寫在句子裡（程式檔名、報告名）。

### 0.3 路徑與環境約定

| 代號 | 意思 | 這台機器的值 |
|---|---|---|
| `<repo>` | 主 repo | `C:/Users/DragonMeow/Documents/vsc/MinecraftTranslator` |
| `<wt>` | **你自己開的**拋棄式 worktree（不要用別人正在整合的 worktree） | 例如 `<repo>/.claude/worktrees/hubwork` |
| `<scratch>` | 暫存資料夾（在 repo 外；放 jar、中間檔、重產輸出） | 隨意，但不得在 repo 內 |
| `<tools>` | 附錄 A 的腳本存檔位置 | `docs/hub-tools/`（repo 內，11 支腳本已存好；在你的 worktree 裡就是 `<wt>/docs/hub-tools`） |
| `<sources>` | **永久保存**的翻譯輸入檔（含英文原文的中間 JSON、詞彙表、各組報告），重產倉庫檔靠它 | `C:/Users/DragonMeow/Documents/vsc/NyanLex-hub-sources`（`out/g1..g5/`、`vanilla-glossary.md`）。在 repo 外，不得放進 repo |
| `<hub>` | 倉庫資料夾 | `<wt>/translation-hub` |

路徑一律用大寫磁碟機 `C:/`（避免 Git Bash 與 Windows 路徑混用出錯）。

**Gradle 啟動方式**（這台機器的固定做法；系統預設的 Java 25 會讓 Gradle 8.10 炸掉）：

```bash
# 【已實測】
export JAVA_HOME="C:\Program Files\Java\jdk-21"
GRADLE="<repo>/.gradle-local/gradle-8.10/bin/gradle"

# 開自己的拋棄式 worktree（detached，不動任何分支）
git -C <repo> worktree add --detach <wt> release/nyanlex-1.0.0

# 確認工具 task 存在（第一次約 20 秒，之後更快；一律 --offline）
$GRADLE -p <wt> tasks --group "translation hub" --offline
```

實測輸出：

```
Translation hub tasks
---------------------
hubExport - Author-only: export validated AI-cache rows into translation-hub/ repository files.
langPackBuild - Author-only: convert language-file pairs into translation-hub mod files.
```

用完收掉：

```bash
# 【已實測】
git -C <repo> worktree remove --force <wt>
```

注意：

- `hubExport`、`langPackBuild` 只存在於**根目錄那棵樹**（`src/`，Fabric 1.21.1 的 canonical 樹）。它們放在 `hub/tool/` 套件，`sync-core.ps1` 不會鏡像到其他 16 棵樹，`build.gradle` 也把 `hub/tool/**` 排除在 jar 之外。
- 含 `;` 的參數（`-Pin`）在 bash 要整個用引號包住；PowerShell 要把整個 `"-Pin=a;b"` 包起來，否則 `;` 會被當成指令分隔（PowerShell 寫法【依程式碼】，bash 寫法【已實測】）。
- Python 腳本在 Windows 主控台預設編碼是 cp932，附錄 A 的腳本都已 `reconfigure(encoding="utf-8")`；自己另寫腳本要記得加。

---

## 1. 概觀

### 1.1 倉庫是什麼

`translation-hub/` 是主 repo 裡的一個資料夾，由 `raw.githubusercontent.com` 直接供應，網址常數在 `HubPaths.DEFAULT_BASE_URL`：

```
https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/translation-hub
```

玩家端的 NyanLex 只會對它做 GET（`index.json` 和玩家確認要下載的檔案），**從不上傳任何東西**。內容由維護者整理，模組不接受玩家投稿。

### 1.2 資料夾結構

```
translation-hub/
  index.json                       每個來源、每個語言一筆：rows / bytes / sha256 / updatedAt
  LICENSE                          資料授權（CC BY-NC-SA 4.0），只管這個資料夾
  README.md                        中英對照的倉庫說明與下架方式
  servers/<host>/<lang>.json       伺服器（<host> 是「可註冊網域」，如 example.net；mc.example.co.uk 會被收斂成 example.co.uk）
  modpacks/<slug>/<lang>.json      模組包（<slug> 由 HubSlug 產生：小寫、只留 a-z 0-9 與 -，最長 64）
  mods/<modId>/<lang>.json         單一模組（<modId> 必須與載入器回報的 id 完全相同，區分大小寫）
.github/ISSUE_TEMPLATE/hub-takedown.md   下架申請範本（在 repo 根目錄的 .github 裡，不在 translation-hub 內）
```

- 目前只有 `zh-tw` 一種語言（檔名 `zh-tw.json`，小寫、連字號）。
- 目前 `servers/` 底下只有**一個**來源（資料夾名稱就是該伺服器的網域，見 `index.json`）；本文件稱它為「伺服器那份檔案」。它是從使用者的 AI 快取匯出的，不能用現有的輸入重新產生，所以任何重產流程都不得動到它（§2 第 9 條）。
- 光影包**沒有自己的分類**：它的設定畫面只有在「載入光影的那個 mod」存在時才看得到，所以光影翻譯併進 index.json 裡**收錄光影翻譯的那一項** mod（轉換時用 `-PshaderTarget=<modId>` 指定）。
- 伺服器路徑與玩家端用同一個 `ServerHostNormalizer`：IP、`localhost`、`*.local`、單一標籤主機都不是合法來源；多段後綴（`co.uk`、`com.tw`、`co.jp` 等）會保留三段。

### 1.3 檔案格式（schema 2）

```json
{"schema":2,"format":"hub-hash-v1","hash":"sha256","language":"zh-tw","rows":1234,"entries":{
"<64 位小寫十六進位>":"譯文"
,"<64 位小寫十六進位>":"譯文"}}
```

- **只存 `sha256(正規化 key)` → 譯文**，沒有原文欄位，也沒有玩家名或範例句。看檔案還原不出原文。
- 列依雜湊排序、每列一行（`HubFile.write`），所以 git diff 一列一列看得懂。
- 譯文裡的 `⟦MT0⟧`、`⟦CS0⟧`、`⟦PB0⟧` 等是程式內部的保護標記（數字、色段、段落換行），**不要手改**。
- schema 1（含原文）的舊檔，現行客戶端一律拒收（`Unsupported hub file schema: 1`）。
- 單檔上限 100,000 列／32 MiB（`HubPaths.MAX_FILE_ROWS`、`MAX_FILE_BYTES`）；單列譯文最長 16,384 字元、不得含網址（`HubFile.plausibleValue`）。

`index.json`（schema 1，這個 schema 號碼與檔案的 schema 2 無關）：

```json
{"schema":1,"generatedAt":"","servers":{"<host>":{"zh-tw":{"rows":55734,"bytes":15537268,"sha256":"…","updatedAt":"2026-10-02T07:55:06.722020200Z"}}},"modpacks":{},"mods":{"<modId>":{"zh-tw":{"rows":…,"bytes":…,"sha256":"…","updatedAt":"…"}}}}
```

- 單行、緊湊 JSON，各區塊依鍵排序（`HubIndex.write`）。`generatedAt` 目前是空字串，沒用到。
- `bytes`、`sha256` 是**git blob（LF 位元組）**的大小與雜湊。玩家端靠它判斷「已是最新」。

### 1.4 key 是怎麼來的（為什麼不准自己重寫正規化）

hub key = `SHA-256( UTF-8( masked.text() ) )`。`masked.text()` 是畫面上實際要顯示的字串，經過 `NameMasker`（玩家名、不翻譯詞遮罩）之後的結果：

- **執行時**：`TranslationService.hubCachedValue(maskedKey)` → `TranslationCache.lookupExternal`。
- **LangPackBuilder**：用同一份 `FabricTextStyle.renderTranslated` 把英文丟進一個真正的 `TranslationService`（hub 查詢換成錄音機），錄下它**會去問倉庫的 key**，所以 key 與遊戲內 100% 一致。
- **HubExportTool**：直接用玩家 AI 快取裡已存在的 key。

用 LangPackBuilder 實測得到的 key 形態（**【已實測】**：我用 `sha256` 對照了輸出檔裡的雜湊）：

| 英文（lang 值，`%d` 以範例數字代入） | 實際進倉庫的 key |
|---|---|
| `Movement Speed` | `Movement Speed` |
| `§cDanger zone`（整行單色） | `Danger zone`（無標記） |
| `Range: %d blocks` | `Range: ⟦MT0⟧ blocks`（數字正規化成 `⟦MT#⟧`） |
| 光影選項 `option.*` 的 `Shadow Quality` | 兩列：`Shadow Quality` 與 `Shadow Quality: `（尾端有空白，譯文是 `陰影品質： `，全形冒號加空白） |
| `Hold shift to see⏎more details here`（第二行小寫開頭） | `Hold shift to see more details here`（視為續句，用空格接起來；譯文也被壓成一行） |

依 `INFRA-REPORT` 的 probe 與程式碼，其他規則是：

- 多色（`§c…§a…`）整行才會包 `⟦CS#⟧`；同一段內的硬換行用 `⟦PB#⟧` 接成一個 key；空行把段落切開，各段是獨立 key。
- 前後空白原樣保留；純數字、百分比、時間（`100%`、`12345`、`24h`）不查倉庫。
- `%s` 的內容無法在建置時得知（可能是名字、物品或數字），這類字串直接跳過並計數（見 §5）。

### 1.5 授權與下架

- 倉庫資料（譯文）採 **CC BY-NC-SA 4.0**（`translation-hub/LICENSE`；程式碼仍是 repo 根目錄的 MIT）。署名方式：「Nyanlex Translation Hub contributors」加 repo 連結。
- 原文的著作權屬原權利人（遊戲、伺服器、模組作者）；倉庫不收原文、不散布原文。
- **來源專案的授權**：倉庫只收簡單授權的專案（MIT、Apache-2.0、BSD-2-Clause、BSD-3-Clause、ISC、Zlib、CC0-1.0、Unlicense、CC-BY-3.0、CC-BY-4.0），外加使用者核准的一個例外 Polyform Shield。GPL、LGPL、AGPL、MPL，以及所有帶 SA、NC 或 ND 的 CC 授權一律不收（細節見 §4.1）。
- **下架管道**：權利人在 GitHub repo 開 Issue，標題 `[Takedown] <伺服器網域或模組 id> <語言>`（範本在 `.github/ISSUE_TEMPLATE/hub-takedown.md`）。處理方式見 §6.2。

### 1.6 玩家端怎麼偵測、下載、查詢

**偵測**（只讀 `index.json`，一次 GET，沒有任何玩家資料外送）：

- 入口有兩個：快速設定最後一頁（「你已安裝的模組中有 N 個有現成翻譯包，要現在下載嗎？」）、設定畫面「翻譯包」分類的「偵測並下載翻譯包」。**沒有每次啟動的自動檢查**（`hubStartupPromptDisabled` 是舊欄位，讀進來就丟掉、不再寫回）。注意：截至 2026-10-02，`README.md`／`README.zh-TW.md` 的倉庫介紹段仍寫「啟動時安靜檢查（可在設定關閉）」，與程式不符，以程式為準。
- 偵測的對象：目前連線的伺服器（`ServerHostNormalizer` 收斂成可註冊網域；單機、區網、Realms 沒有）、偵測到的模組包（`ModpackDetector`：依序看 `minecraftinstance.json`、`modrinth.index.json`、`instance.cfg`+`mmc-pack.json`、`pack.toml`、`instance.json`，都沒有就用已載入 mod id 集合雜湊成 `modset-<12 位十六進位>`）、**每一個**已載入的 mod id（`loadedModIds()`；index 沒列的 mod 會顯示「沒有翻譯包」）。

**下載**（`HubDownloader`）：

1. 確認畫面列出每個來源的筆數／大小、預計總大小、存放位置，按「下載」才會抓。
2. 只抓 `hasContent && !upToDate` 的項目；`upToDate` 靠本機的 `nyanlex-hub-state.json`（記每個「來源＋語言」上次下載的 sha256）。
3. 抓取順序與合併優先權：**伺服器 > 模組包 > 單一 mod**；同一個雜湊先到先贏，不覆蓋。
4. 檔案語言必須等於目前目標語言，否則整檔 0 列合併（`HubLocalCache.mergeFromFile`）。
5. 結果存進本機 `config/nyanlex-hub-cache-<lang>.json`（`{"schema":2,"language":…,"rows":{"<雜湊>":{"v":譯文,"s":"mod:<modId>"}}}`），與玩家自己的 AI／機翻快取**分開存放**，永遠不會被匯出或誤當成自己的翻譯。

**查詢**：

- 畫面文字要翻譯時，先查玩家自己的快取；沒中才問倉庫；再沒中才考慮送翻譯請求（若總開關開著）。倉庫命中時 0 請求。
- **不分翻譯服務**：`TranslationService.setHubLookup` 的說明寫「Consulted for both engines」，AI 引擎與機翻（Google 等）兩邊的快取未命中都會查倉庫；「線上翻譯」總開關關著時倉庫命中仍會顯示（說明書第 6 節也這樣寫）。
- 查詢順序（`TranslationCache.lookupExternal`）：原字串 → 去頭尾空白的字串 → 數字正規化的 key（`⟦MT#⟧`）；後者命中時，用**這次畫面上的數字**填回去。
- 命中當下還會用本機真實的 key 驗證一次（`HubImportValidator.acceptsOnHit`：保護標記的個數與形狀、重塑標記、版面、不得多出網址）；不合的列直接丟棄並從本機快取刪掉，不會顯示出來。
- 單色碼字串（`§fName`）：用純文字查，譯文外面再包回同一組色碼（`TranslationService.translateScreenString`）。

---

## 2. 鐵則清單

違反任何一條都是做錯。每條都附「怎麼檢查」。

| # | 鐵則 | 怎麼檢查 |
|---|---|---|
| 1 | **不存原文**。倉庫只有 `sha256(key)` → 譯文 | `hub_check.py`（欄位白名單、key 皆 64 位小寫十六進位）；`--originals` 做洩漏檢查 |
| 2 | **伺服器資料不收聊天**。`hubExport` 預設就排除聊天；`-PincludeChat` 只在使用者明確要求公開聊天時才用（見 §3.2） | 匯出輸出的 `droppedChat` 不是 0；`hub_subset.py` 對照上一版 |
| 3 | **授權只收簡單授權**（`LangPackBuilder.licenseAccepted`：解析 SPDX 運算式後，逐項**精確**比對）：MIT、Apache-2.0、BSD-2-Clause、BSD-3-Clause、ISC、Zlib、CC0-1.0、Unlicense、CC-BY-3.0、CC-BY-4.0，外加使用者核准的例外 Polyform-Shield（含 Modrinth 的寫法 `LicenseRef-Polyform-Shield-1.0.0`）。**一律不收**：GPL、LGPL、AGPL、MPL（任何版本）、所有帶 SA、NC 或 ND 的 CC 授權、All Rights Reserved、自訂授權、查不到授權，以及其餘所有 `LicenseRef-*`。不在清單的就是不收，沒有人工裁定的通道 | `langPackBuild` 輸出的 `LICENSE ACCEPT/REFUSE` 行；`hub_modrinth.py info`（見 §4.1） |
| 4 | **jar、zip、英文原文、使用者快取副本都不得進 repo**；快取副本用完立刻刪除 | `git status`、`git diff --cached --stat`；`hub_check.py` 會對 `translation-hub/` 內任何非資料檔報 FAIL |
| 5 | **不讀含 API 金鑰的設定檔**（玩家 config 資料夾的 `nyanlex.json` 就有） | 匯出只複製**單一檔案** `nyanlex-ai-cache-<lang>.json` 到暫存資料夾再處理（§3.1） |
| 6 | **key 一律用執行時同一套程式產生**，不准自己重寫正規化 | 只用 `langPackBuild`／`hubExport`；要查某個 key 的雜湊只能 `printf %s "<key>" \| sha256sum`（連結尾空白都要一樣） |
| 7 | **程式、註解、文件、commit 不得出現其他模組名稱**（資料檔與資料夾名稱除外）；第三方名稱過濾名單只存雜湊 | `git diff --cached` 逐行看；commit 訊息用數量（「16 mods」）不寫名字 |
| 8 | **不得出現使用者 email 前綴的那個個人識別字串**（任何檔案內容、套件名、commit 訊息、文件；全域守則有寫明，這份文件為了遵守它，連字面都不寫）；公開身分一律 `DragonMeow`（套件 `com.dragonmeow.*`）；email 只供 git 辨識，不寫進內容 | `git grep -i -E 'bor[w]en'` 必須 0 命中（正規表示式刻意拆開寫，這樣這行字本身不會命中） |
| 9 | **重產時伺服器那份檔案不能被意外改動**：`hubExport` 是**整檔覆寫**，不是增量合併；`langPackBuild` 不碰 `servers/` | 重產一律輸出到 `<scratch>` 的副本，再用 `hub_apply.py` 只升級真正有變的檔（§6.1） |
| 10 | **推上 main 前一定要先問使用者**；不 force push、不用 `git stash`、不跳過 hook | §7 |
| 11 | **不送任何翻譯請求**：驗證時 `translationRequestsEnabled=false`，並把 `aiBaseUrl` 指到不通的本機位址 | §4.9 |
| 12 | 這份文件本身也要遵守以上各條 | 文件裡的範例只用代號 |

補充：

- 輸入檔（含英文原文的中間 JSON）與 jar 要放在 **repo 外的永久資料夾**備份：byte-identical 重產靠它們（§6.1）。放在 session 暫存區會隨 session 消失。
- 轉換器對輸入檔 `excluded` 欄位**只當參考，不影響判斷**；授權由 `license` 欄位決定。要排除某檔，就別把它放進 `-Pin` 的路徑。

---

## 3. 流程 A：伺服器資料（從玩家 AI 快取匯出）

### 3.1 取得快取副本（唯一碰玩家資料的一步）

1. 找一個**只在目標伺服器玩過**的設定檔（快取沒有「來源」標記，匯出會把**整份快取**全標成你指定的來源）。快取混了別的伺服器的字串，就會把別處的列放進這個伺服器的檔案。
2. 只複製**單一檔案**到空的暫存資料夾，不要複製整個 `config/`（裡面有 API 金鑰）：

```bash
# 【依程式碼】玩家快取檔名：nyanlex-ai-cache-<lang>.json（<lang> 是小寫，如 zh-tw）
mkdir -p <scratch>/cache-copy
cp "<玩家 config 資料夾>/nyanlex-ai-cache-zh-tw.json" <scratch>/cache-copy/
```

3. 匯出工具本身也會再把這個檔案複製到系統暫存資料夾、只解析複本、結束時刪掉（`HubExportTool.copyAiCache`），所以原檔絕不會被壓縮改寫。
4. **用完立刻刪掉 `<scratch>/cache-copy/`**。

### 3.2 執行匯出

```bash
# 【已實測】用假快取（8 列）、輸出到暫存副本，輸出見下
$GRADLE -p <wt> hubExport --offline -q \
  "-PcacheDir=<scratch>/cache-copy" -Plang=zh-TW \
  -Pserver=<host> "-Pout=<scratch>/hub-out" -PmergeIndex
```

先把 `<hub>` 整個複製到 `<scratch>/hub-out`（含 `index.json`），輸出才會與既有來源並存：

```bash
cp -r <wt>/translation-hub <scratch>/hub-out
```

參數（`build.gradle` 的 `hubExport` task → `HubExportTool` 旗標）：

| Gradle 屬性 | 旗標 | 說明 | 狀態 |
|---|---|---|---|
| `-PcacheDir=<dir>` | `--cache-dir` | 放 `nyanlex-ai-cache-<lang>.json` 的資料夾（必填） | 已實測 |
| `-Plang=zh-TW` | `--lang` | 目標語言（必填；會正規化成 `zh-tw`） | 已實測 |
| `-Pserver=<host>` | `--server` | 伺服器位址，自動收斂成可註冊網域（`mc.example.co.uk` → `example.co.uk`）；`localhost`、IP 會報錯結束（exit 2） | 已實測 |
| `-Pmodpack=<name>` | `--modpack` | 模組包，經 `HubSlug`（`Example Pack!` → `example-pack`） | 已實測 |
| `-Pmod=<modId>` | `--mod` | 單一 mod，必須完全等於載入器 id（`[A-Za-z0-9_-]{1,64}`），不會被改寫 | 已實測 |
| `-Pout=<dir>` | `--out` | 倉庫根目錄（即 `translation-hub/` 本身，必填） | 已實測 |
| `-PmergeIndex` | `--merge-index` | 一併更新 `<out>/index.json` 該來源的 rows／bytes／sha256／updatedAt | 已實測 |
| `-PincludeChat` | `--include-chat` | **公開聊天列**（預設關，見下） | 已實測 |
| `-PmaxRows=<n>` | `--max-rows` | 1..100000：超過時依雜湊排序**取前 n 列**（明確同意才用） | 已實測 |

`--server`／`--modpack`／`--mod` **三選一**，缺了或多給會報 `Exactly one of --server / --modpack / --mod is required`（exit 2，已實測）。（`--drop-chat` 仍被接受，但現在是預設，等於沒作用。）

實測輸出（假快取：8 列，其中 2 列聊天、1 列原樣回聲、1 列含外部網址、1 列含第三方名稱）：

```
EXPORT_OK source=server:example.net lang=zh-tw totalRows=8 droppedChat=2 rejectedValidation=1 rejectedForeignUrl=1 rejectedUnmaskedName=0 rejectedThirdParty=1 nameConversionSucceeded=0 duplicateKeyGroups=0 duplicateRowsDropped=0 exportedRows=3 truncated=0 bytes=340 sha256=56db0f47… file=<scratch>\hub-out\servers\example.net\zh-tw.json
```

欄位意思：

| 欄位 | 意思 |
|---|---|
| `totalRows` | 快取裡的列數（暫定列、`\0` 開頭的內部標記列已先剔除，不計入） |
| `droppedChat` | 被聊天分類器丟掉的列（預設開啟） |
| `rejectedValidation` | 沒過批次轉移驗證（原樣回聲、標記壞掉、版面不符、亂碼…） |
| `rejectedForeignUrl` | 譯文含網址／網域（schema 2 檔案不能帶任何網址） |
| `rejectedUnmaskedName` | key 裡還有沒遮罩的玩家名，而且無法安全轉成遮罩形式 |
| `nameConversionSucceeded` | 原本含未遮罩玩家名、但已成功就地轉成 `⟦n⟧` 的列（仍會繼續過其他檢查） |
| `rejectedThirdParty` | 原文或譯文提到其他 mod 的名字（見 §3.3） |
| `duplicateKeyGroups` / `duplicateRowsDropped` | 名字遮罩後 key 相同的列：取最常見的譯文，平手取快取中較後面的 |
| `exportedRows` / `truncated` | 實際寫出的列數／被 `-PmaxRows` 截掉的列數 |
| `bytes` / `sha256` | 寫出檔案的大小與雜湊（也寫進 index） |

逐列的判斷順序（`HubExportTool.classifyOneRow`）：第三方名稱 → 未遮罩玩家名 → 批次轉移驗證 → 外部網址 → 值不可公開（含網址、過長）→ 聊天分類 → 保留。快取先經過「舊整段列拆成交易／屬性列」的展開（`splitLegacyWholeRows`），所以 `totalRows` 可能比快取多。

**聊天為什麼預設排除，`--include-chat`（`-PincludeChat`）的風險：**

- 聊天是**別的玩家的訊息**。公開出去的譯文會帶著對話內容與玩家名（我用假資料實測：私訊列 `來自 Bob：你在線上嗎` 在加了 `-PincludeChat` 後被寫進檔案，名字是未遮罩的，因為私訊前綴的名字沒有遮罩規則）。
- 分類器（`ChatLineClassifier`）是依字串形狀的啟發式，原則是「判不準一律當聊天刪掉」：階級標籤、私訊 `From/To`、`Guild/Party >` 頻道前綴、`⟦0⟧:` 遮罩名加冒號、橫幅式多行公告、以及任何其他帶遮罩名的字串。
- 結論：公開倉庫**一律不加**；只有使用者明確說「這次要公開聊天」才用，且每次都要重新確認。

**列數與大小上限**：超過 100,000 列或 32 MiB 是硬錯誤（`ERROR row cap exceeded` / `byte cap exceeded`），工具不會自動分檔（倉庫格式一來源一語言一檔）。解法是縮小來源、清掉快取，或明確用 `-PmaxRows`（依雜湊排序取前 n 列，等於隨機丟列，少用）。

### 3.3 第三方名稱過濾（`ThirdPartyModFilter`）

別的 mod 印進聊天的訊息或前綴不是伺服器內容，絕不進倉庫。規則：

- 原文或譯文只要提到名單內的名字就整列丟掉（`REJECTED_THIRD_PARTY`）。
- 名單**只存 SHA-256 雜湊**（`NAME_HASHES`，目前 22 個，含本專案自己的名字；`BRACKET_HASHES` 1 個，只在方括號內才算，例如 `[abc]`），程式碼裡不出現任何名字。
- 比對方式：把文字切成英數字詞（連續 ASCII 字母數字），取所有 1~3 個詞的組合，正規化（轉小寫、只留 ASCII 字母數字）後算雜湊；所有格 `'s` 先去掉。所以**超過 3 個詞的名字永遠比不到**，**沒有任何 ASCII 英數字的名字正規化後是空字串**（腳本會拒絕）。

**新增一個名稱**：

```bash
# 【已實測】算雜湊、重算現有名單的校驗碼、印出新的排序陣列與新校驗碼（附錄 A.4）
python <tools>/hub_hashname.py \
  --java <wt>/src/main/java/com/dragonmeow/nyanlex/hub/tool/ThirdPartyModFilter.java \
  "<名稱>"            # 短縮寫（容易撞到一般單字）加 --bracket
```

沒給名稱時只驗證現有名單與 `NAME_HASHES_CHECKSUM` 相符（目前輸出 `NAME_HASHES: 22 entries; checksum OK`）。給了名稱就印出新陣列內容與新校驗碼。接著手動：

1. 把新雜湊放進 `ThirdPartyModFilter.java` 的 `NAME_HASHES`（維持排序；`--bracket` 的放 `BRACKET_HASHES`）。
2. 更新 `NAME_HASHES_CHECKSUM`（只有 `NAME_HASHES` 算校驗碼，`BRACKET_HASHES` 不算）。
3. 更新 `ThirdPartyModFilterTest.realHashListIsUnchanged` 裡寫死的**數量**（22、22）和**校驗碼字面值**。
4. 跑測試：

```bash
# 【已實測】hub 套件全部測試（17 個類別、184 項，0 失敗）
$GRADLE -p <wt> test --tests "com.dragonmeow.nyanlex.hub.*" --offline
```

5. 重新匯出，並用 §3.4 的子集比對確認：新檔是舊檔的子集，被刪的列就是含該名稱的列。
6. 名稱本身不得出現在程式、註解、commit 訊息、PR、文件任何地方；commit 訊息只寫「add one name to the third-party filter」。

Python 與 Java 的正規化已交叉驗證（用一個放在 `hub.tool` 套件的小 Java 程式呼叫 package-private 的 `ThirdPartyModFilter.hashName`，三個測試名稱的雜湊與腳本逐字相同，【已實測】）。

### 3.4 子集比對（`hub_subset.py`）

同一份快取重新匯出（例如改成預設排除聊天、或新增過濾名稱）時，新檔必須是舊檔的**子集**，而且同一個 key 的譯文不能變：

```bash
# 【已實測】比對 git 歷史上的兩版（附錄 A.2，用 git blob，不看工作目錄）
python <tools>/hub_subset.py --repo <wt> \
  --path translation-hub/servers/<host>/zh-tw.json <舊 rev> [<新 rev>=HEAD]
```

實測輸出（舊版含聊天的那次匯出 → 現行版）：

```
schema old/new: 2 2
rows old/new: 65313 55734
new keys not in old (must be 0 when the same cache was re-exported without chat): 0
removed (old keys missing in new): 9579
same key, different translation: 0
```

判讀：`new keys not in old` 與 `same key, different translation` 必須是 0（腳本據此回傳 exit 1）；`removed` 的數量要能解釋（聊天、第三方名稱、新增的驗證規則）。

### 3.5 合併 index、核對筆數與 sha256

`-PmergeIndex` 已由工具更新 `<out>/index.json`（讀入、換掉該來源該語言一筆、緊湊寫回；其他來源的資料原樣保留，已實測重新序列化後位元組相同）。注意 `updatedAt` 每次匯出都會變成現在時間，**即使檔案內容完全沒變**。

核對一律用 **git blob**：

> Windows 上 `core.autocrlf=true`（`C:/Program Files/Git/etc/gitconfig`），工作目錄的資料檔在 checkout 後會是 CRLF，大小與 sha256 都跟 index 記的不一樣（例如伺服器檔工作目錄 15,593,002 位元組，index 記 15,537,268，差的正好是列數）。index 記的是 LF 版本（也是 raw.githubusercontent.com 供應的版本）。

```bash
# 【已實測】附錄 A.1：每個資料檔 schema/format/hash/language/rows、key 格式、值非空、
# index 的 rows/bytes/sha256 與 git blob 相符、index 與實際檔案一一對應、倉庫內沒有非資料檔
python <tools>/hub_check.py --repo <wt> [--rev HEAD]
# 預期最後一行：RESULT PASS（任何 FAIL 會逐行列出並回傳 exit 1）
```

手動核對單一檔案也可以：

```bash
git -C <wt> show HEAD:translation-hub/servers/<host>/zh-tw.json | sha256sum
git -C <wt> show HEAD:translation-hub/servers/<host>/zh-tw.json | wc -c
```

### 3.6 收尾

1. `rm -rf <scratch>/cache-copy`（快取副本立刻刪）。
2. 把輸出升級進 repo：用 §6.1 的 `hub_apply.py`（只動真的有變的檔與 index 條目），不要直接把 `-Pout` 指向 `<hub>`。
3. 提交與發佈見 §7。

---

## 4. 流程 B：模組與光影包

整體順序：選項目並查授權 → 下載到暫存 → 抽取（只翻缺的）→ 翻譯 → 自我檢查 → `langPackBuild` → 驗證 → 遊戲內驗證 → 提交。

### 4.1 選項目、用 Modrinth API 查授權

選項目的原則：常用的客戶端模組與光影包、授權在下面的「簡單授權」清單內、確實有可翻的介面字串。

```bash
# 【已實測】附錄 A.6：查專案授權與各 MC／loader 的最新版本（只送 GET，每秒不超過 2 個請求）
python <tools>/hub_modrinth.py info <slug> [<slug> ...] [--mc 1.21.1] [--loader fabric]
```

實測輸出（形狀）：

```
<slug>: title='…' project_type=mod license.id='MIT' license.name='MIT License' license.url=None
  version=… type=release mc=['1.21', '1.21.1'] loaders=['fabric', 'quilt'] file=….jar (… bytes)
```

**以 `license.id` 為準判斷**（`LangPackBuilder.licenseAccepted`，輸入檔的 `license` 欄位會直接走這個閘門）。倉庫**只收簡單授權**：

| 結果 | `license.id` 的樣子 |
|---|---|
| **收** | `MIT`、`Apache-2.0`、`BSD-2-Clause`、`BSD-3-Clause`、`ISC`、`Zlib`、`CC0-1.0`、`Unlicense`、`CC-BY-3.0`、`CC-BY-4.0`；另有使用者明確核准的一個例外：`Polyform-Shield`（Modrinth 的寫法 `LicenseRef-Polyform-Shield-1.0.0` 視為同一個授權，是唯一被放行的 `LicenseRef`） |
| **不收** | GPL、LGPL、AGPL、MPL（任何版本，含 `-only`、`-or-later`）；所有帶 SA、NC 或 ND 的 CC 授權（`CC-BY-SA-*`、`CC-BY-NC-*`、`CC-BY-NC-SA-*`、`CC-BY-ND-*`、`CC-BY-NC-ND-*`）；`LicenseRef-All-Rights-Reserved`、`All Rights Reserved`、`ARR`；其餘所有 `LicenseRef-*`；空白或 `LicenseRef-`（查不到授權）；不是合法 SPDX 運算式的字串（例如 `Some Mod License (LicenseRef-Some-Mod-License)`）；以及任何不在上面「收」那一列的授權 |

沒有「要人判斷」這一類：不在清單的一律不收，閘門不讀授權文字，也不接受人工改寫 `license` 欄位來放行。要改清單，必須改程式並經使用者同意。

閘門的注意事項（讀 `LangPackBuilder.licenseAccepted`）：

- 它**解析 SPDX 運算式**：支援 `AND`、`OR`、括號與 `WITH <例外>`；`AND`／`OR`／`WITH` **大小寫都認**（`and`、`And` 都行），`AND` 優先於 `OR`。`A AND B` 兩邊都要在清單內，`A OR B` 任一邊在清單內即可（作者本來就提供寬鬆的那一邊）。
- 每個授權 id **逐項與清單精確比對**（不分大小寫），不是子字串、也不是前綴：`AGPL-3.0` 不會因含 `gpl` 被收，`limited`、`permit` 也不會因含 `mit` 被收；沒有版本號的簡寫（`BSD`、`CC-BY`、`CC0`）不是 SPDX id，不收；`BSD-3-Clause-Clear`、`CC-BY-3.0-IGO`、`CC-BY-2.0` 這類不在清單上的變體也不收。
- 不是合法運算式的字串（括號不成對、懸空的 `AND`、自由文字的授權名稱）一律不收。
- `LICENSE ACCEPT` 行一律要人眼對照 Modrinth 的 `license.id` 複核；與預期不同就停下來回報，不要自己改 `license` 欄位。

**選到的項目要記下**：slug、真正的 mod id、版本、授權、既有 zh_tw 狀態、翻了幾條。不收的項目也要留紀錄（含原因）。已翻完才發現授權不收的，輸出檔可以留在暫存區，但**不要放進 `-Pin` 的路徑**。

### 4.2 下載到暫存資料夾

```bash
# 【已實測】附錄 A.6：下載「最新正式版」的 primary 檔案，驗 sha512，存到 <scratch>/jars/<slug>/
python <tools>/hub_modrinth.py get <slug> --dest <scratch>/jars --mc 1.21.1 --loader fabric
# 光影包：不加 loader 篩選
python <tools>/hub_modrinth.py get <slug> --dest <scratch>/jars --mc 1.21.1 --loader none
```

它背後呼叫的是官方 API（可用 `curl` 自己驗證，【依程式碼】）：

```
GET https://api.modrinth.com/v2/project/<slug>
GET https://api.modrinth.com/v2/project/<slug>/version?loaders=["fabric"]&game_versions=["1.21.1"]   # 參數是 URL 編碼的 JSON 陣列；光影包不加 loaders
```

規則：

- **User-Agent** 一律帶 `DragonMeow/NyanLex-hub-builder (github.com/DragonMeow1012/NyanLex)`。
- **節流**：每秒不超過 2 個請求（腳本在請求之間睡 0.6 秒）；出錯就停下來，不要重試轟炸。
- **版本選擇**：回傳是新到舊。優先選 MC 1.21.1 的 fabric 版；沒有就選 neoforge 版；兩者都沒有就選最新正式版（MC 版本不限），並把實際 `mcVersion`、`loader` 寫進輸出檔。不要選 beta／alpha（腳本只有在完全沒有 release 時才退而求其次，並印警告）。找不到可用版本的項目跳過，寫進報告。
- 檔案**只放 `<scratch>/jars/<slug>/`**，jar、zip 絕對不進 repo。

### 4.3 抽取 lang：只翻缺的

```bash
# 【已實測】附錄 A.7：讀 jar／zip，輸出「要翻譯」的骨架（zh_tw 留空）
python <tools>/hub_extract_lang.py <scratch>/jars/<slug>/<file>.jar \
  --slug <slug> --license "<Modrinth 的 license.id>" --version <版本> --mc 1.21.1 --out <scratch>/todo
# 光影包（zip）：
python <tools>/hub_extract_lang.py <scratch>/jars/<slug>/<file>.zip --kind shaderpack --pack-name "<名稱>" \
  --slug <slug> --license "<license.id>" --version <版本> --mc 1.21.1 --out <scratch>/todo
```

它做的事（你手動做也一樣）：

- **mod id 取真的**：`fabric.mod.json` 的 `id`；NeoForge／Forge 用 `META-INF/neoforge.mods.toml`／`mods.toml` 的 `modId`；舊版用 `mcmod.info` 的 `modid`。**不要用 Modrinth 的 slug 當 mod id**（兩者常常不同，例如 slug 是 `a-b` 而 id 是 `a_b`）。倉庫的資料夾名稱與玩家端比對都是 mod id。
- 讀 `assets/*/lang/en_us.json` 與 `zh_tw.json`；**一個 jar 可能有多個 namespace，都要讀**。檔名大小寫與連字號不一定（`en_US.lang`、`zh-TW.json`），比對時不分大小寫、`-` 等同 `_`。
- 光影包讀 zip 內的 `shaders/lang/en_US.lang` 與 `zh_TW.lang`（`key=value`，`#` 開頭是註解）。
- **只翻缺的**：
  - 已有完整 zh_tw → 整個跳過，報告記「已有 zh_tw」（腳本印 `nothing to translate`，不寫檔）。
  - 只有一部分 → 只翻 zh_tw 沒有的 key。
  - zh_tw 值**等於英文**的（英文佔位，曾有 mod 的 zh_tw 有上千條這種）腳本會標 `"existing":"placeholder"`，要人判斷：真的是專有名詞或搜尋關鍵字就保持原樣（轉換時會被當 `UNCHANGED` 略過），否則要翻。
  - `_comment*`、`//` 開頭的 key 與空字串 spacer 不翻。
- jar 內**完全沒有 lang 檔**（例如設定頁字串只在程式裡）：先確認真的沒有其他形式的語言資源；沒有的話才走下面的 class 抽取；jar 連玩家看得到的字串都沒有就記錄原因並跳過。

#### 4.3.1 沒有 lang 檔：從 class 的 annotation 抽取

有些 mod 把設定畫面的標籤寫在註解裡，例如 `@SomeOption(name = "…", desc = "…")`。這些字串在 class 檔的 `RuntimeVisibleAnnotations`（常數池）裡，可以不執行任何程式、不 `javap` 直接讀出來：

```bash
# 【已實測】附錄 A.8：用真實 jar 驗證過，抽出的 5,938 條與當初翻譯組的 5,937 條完全一致
#                    （差的那 1 條是來源端 § 碼筆誤、當初刻意略過的）
python <tools>/hub_annotations.py <scratch>/jars/<slug>/<file>.jar \
  --prefix <套件路徑>/config/ --annotations <註解簡名1>,<註解簡名2> --out <scratch>/todo/<modId>-raw.json
# 第一次先不加 --annotations，只看註解型別統計
```

- 輸出每筆 `{ns:"config", key:"class:<類名>#<序號>", field, role:"name"|"desc", en}`。`key` 是 `class:` 加相對於 `--prefix` 的類名（用點分隔）加該類內的流水號，**序號依欄位順序、name 先 desc 後**；之後翻譯檔沿用這個 key。
- 先**不加 `--annotations`** 跑一次：它只在 stderr 印出該前綴底下「看到的每種註解型別與數量」，據此決定該抽哪幾種（`--annotations` 給簡名、逗號分隔）。
- **只抽玩家在設定畫面看得到的字串**，不碰程式邏輯、不抄程式碼；指令語法、內部 id、除錯開關要在審閱時剔掉。
- 抽不到的：下拉選單的選項名稱（enum 的顯示名稱）、寫在程式常數裡的字串、聊天訊息、指令回饋。這些在遊戲內仍是英文，報告要寫明未涵蓋範圍。
- 抽不出可靠結果就記錄原因並跳過。

### 4.4 翻譯規則

#### 4.4.1 用語：先建原版對照表

用語**以 Minecraft 原版的繁中為準**。開工前先做對照表：

```bash
# 【已實測】附錄 A.10：查原版用詞（en_us 來自 Loom 快取的 minecraft-client.jar，zh_tw 來自資產索引）
python <tools>/hub_vanilla_glossary.py --mc 1.21.1 find "render distance" "biome" "chunk"
python <tools>/hub_vanilla_glossary.py --mc 1.21.1 --out <scratch>/vanilla.json     # 整份 {key:{en,zh_tw}}
```

原版語言檔在哪裡（**【已實測】，且與舊版 GUIDE 的說法不同**）：

- `zh_tw`：`%USERPROFILE%/.gradle/caches/fabric-loom/assets/indexes/<mc>-<n>.json` 的 `objects["minecraft/lang/zh_tw.json"].hash` → `.../assets/objects/<前兩碼>/<hash>`。
- `en_us`：**不在資產索引裡**（索引只有 `en_au`、`en_gb` 等變體），它在遊戲 jar：`%USERPROFILE%/.gradle/caches/fabric-loom/<mc>/minecraft-client.jar` 內的 `assets/minecraft/lang/en_us.json`。

已用腳本對照過 1.21.1 的核心用詞（`hub_vanilla_glossary.py` 輸出）：

| 英文 | 原版 zh_tw | 備註 |
|---|---|---|
| Render Distance | 顯示距離 | 不寫「繪製／渲染距離」 |
| Simulation Distance | 模擬距離 | |
| Entity Distance | 實體顯示距離 | |
| chunk | 區塊 | 只指世界區塊；`%s 個區塊` |
| Graphics | 畫質 | 不寫「繪圖（設定）」 |
| Fancy／Fast／Fabulous! | 精緻／流暢／極致！ | |
| Mipmap Levels | Mipmap 等級 | |
| Max Framerate | 最大 FPS | 不寫「影格率／幀率」；`%s fps` 保留 |
| Smooth Lighting | 柔和光源 | |
| Particles | 粒子密度 | |
| Biome | 生態域 | 不寫「生物群系」 |
| Clouds／Brightness／Fullscreen | 雲／亮度／全螢幕 | |
| VSync | 垂直同步 | |
| GUI Scale | 介面大小 | 不寫「GUI 縮放」 |
| FOV | 視角廣度 | |
| Video Settings | 顯示設定 | |
| Controls | 按鍵設定 | |
| Resource Packs | 資源包 | |
| Enchant／Glint | 附魔／附魔光效 | |
| Item／Block／Entity／Mob | 物品／方塊／實體／生物 | |
| Overworld／Nether／The End | 主世界／地獄／終界 | |
| Settings／Default | 設定／預設 | 不寫「設置」 |
| Enabled／Disabled | 已啟用／已停用 | 開關用 開啟／關閉 |
| Reset | 重設 | |
| Done／Back／Cancel | 完成／返回／取消 | |

原版沒有的詞，用台灣常用說法：設定（不是設置）、預設、啟用／停用、影像、品質。

過去翻譯後被抓到、事後統一修正過的詞（驗收時請特別 grep）：生物群系→生態域、繪製／渲染距離→顯示距離、繪圖（設定）→畫質（設定）、影格率→FPS、GUI 縮放→介面大小、「華麗」（Fancy 預設組）→精緻。

#### 4.4.2 格式符號與色碼：一律保留，不准增減，也不准改順序

- `%s`、`%d`、`%1$s`、`%.1f`、`%%` 這類格式符號；`§` 色碼；`\n`；前後空白。
- 中文語序需要調換時，改用**有編號**的參數（`%1$s`、`%2$s`）。沒編號的符號順序必須與英文相同。
- `{變數}`、`&&` 這類模組自己的佔位符也要原樣保留（自檢腳本會比對）。
- 英文裡**字面的 `%`**（例如 "% is"）不是格式符號，保留即可。
- 來源端有無效色碼（例如 `§NONE:§r`、`§P…`）：原文如此的，譯文照原樣保留；如果翻譯一定會改動 `§` 序列，就**略過那一條**並在報告註明（過去有 1 條這樣略過）。

#### 4.4.3 專有名詞不翻

模組名、光影包名、作者名、API／技術名稱（OpenGL、Vulkan、FXAA、SSAO、TAA 等）、遊戲內的 Boss／物品／分類大寫標籤（例如整組以全大寫呈現的分類名稱），保持英文。譯文與原文相同的列，轉換時會以 `UNCHANGED` 略過（正常現象）。

#### 4.4.4 簡短與風格

- 按鈕與選項名稱要和英文差不多長（介面空間有限）；說明文字（tooltip、comment）可以完整翻譯。
- 不加 emoji、不加譯註、不用簡體字。
- **不准抄**其他翻譯專案或社群翻譯包的現成譯文，一律自己翻。
- 截斷句（英文以空白結尾、由程式接續的片段）譯文結尾同樣保留空白，語序需遊戲內確認。

#### 4.4.5 行對齊的書本與圖鑑

有些 mod 的書本／圖鑑是純文字檔（每行大約 30 字元），**每行是畫面上的一個獨立字串**。處理方式（過去做過一個 353 行的圖鑑）：

- 一行一個 entry，`key = "<檔名>#<行號>"`，`en` 就是畫面上那一行（連行首空白都原樣保留），`zh_tw` 是對應行的譯文。
- 每個檔、每段的行數必須與原文相同；指令行、註解行、空行（版面留白）不翻、不放進 entries。
- 中文每行要比英文窄（當時用「字數不超過英文字元數的 55%」控制，僅極短的專有名詞行略超過）。
- 這種資料能不能命中取決於 mod 是不是**逐行呼叫繪製**；倉庫端是「每個繪製呼叫查一次」，所以逐行的資料可以命中，但**沒有在遊戲內驗證過**（見 §5）。

#### 4.4.6 大型內容模組

物品、方塊、生物名稱和說明很多的模組：全部都要翻。

- 開工前建立**這個模組自己的詞彙表**（`<modId>-glossary.md`），同一個詞前後要用同一種譯法；物品與方塊名稱要簡潔一致。
- 每翻 300 條就寫一次輸出檔，避免中斷後全部重來。
- 譯者自己的專有名詞譯名（地名、生物名）要與已收錄的同模組檔案一致。

### 4.5 中間 JSON 格式

每個模組或光影包一個檔，UTF-8，放在 `<scratch>/out/<group>/<modId 或 slug>.json`（**含英文原文，只能放 repo 外**）：

```json
{
  "kind": "mod",
  "modId": "<實際 mod id>",
  "slug": "<modrinth slug>",
  "version": "<版本號>",
  "mcVersion": "1.21.1",
  "loader": "fabric",
  "license": "<Modrinth 的 license.id，原樣照抄>",
  "existingZhTw": "none | partial | full",
  "entries": [
    { "ns": "<lang 的 namespace>", "key": "<lang key>", "en": "<英文原文，含 %s>", "zh_tw": "<譯文，含 %s>" }
  ]
}
```

- 光影包：`"kind": "shaderpack"`、`"modId": null`、另加 `"packName"`；`ns` 填 `"shader"`；`key` 保留原本的 `option.*`／`screen.*`（**轉換器靠 `option.` 開頭判斷要不要多產一列 `X: ` 形式**）。
- class annotation 來源：`key` 用 `class:<類名>#<序號>`，可另加 `field`、`role`。
- 行對齊來源：`key` 用 `<檔名>#<行號>`。
- 可有的額外欄位（如 `existing`、`pack`、`field`、`role`、`lineAligned`）轉換器都忽略。
- **轉換器實際讀的欄位**只有：`kind`、`modId`、`license`、`excluded`（只當參考）、`entries[].en`、`entries[].zh_tw`、`entries[].key`。其他都是給人看的。
- 同一個 `modId` 的多個輸入檔會合併成一個倉庫檔（例如 mod 本體＋圖鑑）；`kind:"shaderpack"` 併進 `-PshaderTarget` 指定的 mod。

### 4.6 自我檢查

每完成一個檔就跑一次，全部通過才能交給轉換：

```bash
# 【已實測】附錄 A.9：對真實翻譯組輸出 0 個問題；對故意做壞的檔案正確回報 5 種問題（exit 1）
python <tools>/hub_selfcheck.py <scratch>/out/<group>      # 可給檔案或資料夾
```

檢查項目（每筆 entry）：`zh_tw` 非空；`%s %d %1$s %.1f %%` 符號集合相同，且沒編號的順序相同；`{變數}`／`&&` 相同；`§` 色碼序列相同；換行數相同；前後空白相同；`zh_tw` 沒有英文裡沒有的 emoji 或網址；同一檔內 `(ns,key)` 不重複。`zh_tw` 與 `en` 完全相同的只列為 INFO（只有專有名詞可以）。

另外人工檢查：沒有簡體字殘留、用語符合 §4.4.1、沒有空譯文。

### 4.7 執行 LangPackBuilder

```bash
# 【已實測】一次轉多個輸入、光影併進 <modId> 那一項、輸出到 <scratch> 的倉庫副本
cp -r <wt>/translation-hub <scratch>/hub-out
$GRADLE -p <wt> langPackBuild --offline -q \
  "-Pin=<scratch>/out/g1;<scratch>/out/g2;<scratch>/out/g3" \
  "-Pout=<scratch>/hub-out" \
  -PshaderTarget=<modId> -PmergeIndex \
  "-Preport=<scratch>/report.txt" "-Pskips=<scratch>/skips.tsv"
```

全部參數（`build.gradle` 的 `langPackBuild` → `LangPackBuilder` 旗標）：

| Gradle 屬性 | 旗標 | 說明 | 狀態 |
|---|---|---|---|
| `-Pin=<a;b;c>` | `--in`（每段一個） | 輸入：JSON 檔，或資料夾（遞迴找 `*.json`，不是中間格式的 JSON 會被默默忽略）；用 `;` 分隔多個（必填） | 已實測 |
| `-Pout=<dir>` | `--out` | 倉庫根目錄（`translation-hub/` 本身，必填） | 已實測 |
| `-Plang=zh-TW` | `--lang` | 目標語言，預設 `zh-TW`；寫入 `mods/<modId>/zh-tw.json` | 依程式碼（預設值即實測用的值） |
| `-PshaderTarget=<modId>` | `--shader-target` | 光影包的列併進這個 mod 的檔案。**沒給就完全忽略光影包輸入**（連授權判定行都不印） | 已實測 |
| `-PmergeIndex` | `--merge-index` | 對每個寫出的檔更新 `<out>/index.json` | 已實測 |
| `-Preport=<file>` | `--report` | 把統計文字也寫成檔 | 已實測 |
| `-Pskips=<file>` | `--skips` | 寫出每個被略過的字串（TSV：`modId、原因、英文、譯文`；**含原文，只能放暫存區，不可 commit**） | 已實測 |

行為：

- **每次全量重建（冪等）**：輸出檔只依本次的輸入決定。所以**重建某個 mod 時必須帶齊它所有的輸入檔**（本體、圖鑑、光影包…）；少帶一個，那一個的列就會從輸出消失。
- 只寫出「有輸入且授權通過」的 mod 檔；沒出現在輸入裡的 mod 與 `servers/` 完全不動。
- **授權閘門**：每個輸入檔印一行 `LICENSE ACCEPT|REFUSE <kind> <modId> [<license>] <檔名>`；REFUSE 的整檔不轉換。判斷邏輯與例外見 §4.1。
- 逐條轉換：把英文代入範例數字（`%d`→12、34、56…，各位置不同）、經 `FabricTextStyle` 管線算出 key；譯文用同一管線算請求字串；段落換行數與英文不同時，多的壓平、少的依英文行長度比例在標點處補回（`alignBreaks`）；最後以 `HubImportValidator.acceptsOnHit`（與玩家端命中時完全相同的檢查）驗證。
- 輸出 `mods/<modId>/zh-tw.json`（schema 2，列依雜湊排序）與 `index.json` 的 mods 區塊。

實測輸出（假資料：2 個 mod 檔＋1 個光影檔，其中一個 mod 是 All Rights Reserved）：

```
examplemod: entries=7 entriesWithRows=5 rows=6 skipped=2 {NON_NUMERIC_ARG=1, UNCHANGED=1} sources=[example-shader.json, examplemod.json]
LICENSE REFUSE mod arrmod [All Rights Reserved] arrmod.json
LICENSE ACCEPT shaderpack - [Apache-2.0] example-shader.json
LICENSE ACCEPT mod examplemod [MIT] examplemod.json
TOTAL rows=6 files=1
```

略過原因（`LangPackBuilder.Skip`，統計在 `skipped={…}`）：

| 原因 | 意思 |
|---|---|
| `NON_NUMERIC_ARG` | 含 `%s`：代入內容不可知，無法算出穩定 key（見 §5） |
| `UNCHANGED` | 譯文等於原文（專有名詞等），正常 |
| `REJECTED_VALIDATION` | 命中時驗證不過（譯文含網址、標記個數不合等） |
| `PARAGRAPH_MISMATCH` | 英文與譯文的段落數不同且無法安全補回 |
| `NOT_LOOKED_UP` | 遊戲根本不會拿這種字串去查倉庫（純符號、純數字、時間） |
| `COMPOSED_AT_RUNTIME` | 遊戲把這行拆成多個單元組合，沒有單一整行 key |
| `NUMBER_SLOT_MISMATCH` | 譯文的範例數字無法對回 key 的數字位置 |
| `FORMAT_MISMATCH` / `FORMAT_ERROR` | 英文與譯文的參數清單不同／格式字串不合法 |
| `EMPTY` | 英文或譯文為空 |
| `CONFLICT` | 兩個不同字串算出同一個 key 但譯文不同，留先到的 |

**絕對不要**把 `-Pout` 直接指向 `<hub>`（repo 工作目錄）：重建會改寫每個來源在 `index.json` 的 `updatedAt`，製造一堆沒有內容差異的變更。輸出到 `<scratch>` 副本，再用 §6.1 的 `hub_apply.py` 升級有變的檔。

### 4.8 驗證：key 格式、值不含原文、index 一致

```bash
# 【已實測】附錄 A.1：對 git 提交版本做全面檢查；--originals 是「值不含原文」的洩漏檢查
python <tools>/hub_check.py --repo <wt> --rev HEAD --originals <scratch>/out
```

預期（形狀；mod id 以代號表示）：

```
mods/<modId>/zh-tw.json: rows=… bytes=… no-CJK-values=…
…
servers/<host>/zh-tw.json: rows=55734 bytes=15537268 no-CJK-values=108
leak check: 8709 source strings (>=16 chars), 77 verbatim hits, 0 severe
   REVIEW (proper noun left in English?): <產品名> | …譯文片段…
RESULT PASS
```

- **key 格式**：全部 64 位小寫十六進位；schema／format／hash／language／rows 欄位正確；沒有多餘欄位。
- **值不含原文**：`--originals` 把輸入檔裡長度 ≥16 的英文原文拿去找「是否原樣出現在某個譯文裡」。`SEVERE`（值就是原文，或含 6 個詞以上的原文句子）會失敗；`REVIEW` 的幾乎都是產品名、活動名留在中文句子裡，逐筆看一眼即可。`no-CJK-values` 不為 0 的列也要抽看（專有名詞、純符號是正常的）。
- **index 一致**：每個檔案的 rows／bytes／sha256 與 **git blob** 相符，index 與實際檔案一一對應，倉庫內沒有別的檔案。
- 單元測試（【已實測】184 項全綠）：`$GRADLE -p <wt> test --tests "com.dragonmeow.nyanlex.hub.*" --offline`。其中 `LangPackBuilderTest` 驗證 key 與 live `TranslationService` 的 hub 查詢一致、輸出檔能被 `HubFile` 讀回並由 `HubLocalCache` 命中。
- 再抽 20 筆譯文肉眼看（用語、有沒有簡體、有沒有明顯翻錯）。

### 4.9 遊戲內驗證

目的：確認「畫面上的字串」真的命中倉庫列，而不是只有檔案格式正確。**全程 0 個翻譯請求。**

1. **開自己的拋棄式 worktree**（不要動主 repo、也不要動別人正在整合的樹），並確認沒有別的 Gradle 程序在用它。
2. **放入要驗證的 jar**：`<wt>/run/mods/`（只放 1.21.1 的 fabric 版；一次放 2~3 個有設定畫面的 mod 就夠）；要驗光影，zip 放 `<wt>/run/shaderpacks/`，並在 `<wt>/run/config/` 寫載入光影那個 mod 的設定檔指定該 zip。`run/` 已在 `.gitignore`。
3. **設定檔** `<wt>/run/config/nyanlex.json`（只含下面這些，**絕不放真的 API 金鑰**）：

```json
{
  "screenTextMode": "TRANSLATION", "tooltipMode": "TRANSLATION", "nameMode": "TRANSLATION",
  "aiScreenText": true, "aiTooltip": true, "aiName": true,
  "aiBaseUrl": "http://127.0.0.1:9/v1",
  "translationRequestsEnabled": false,
  "targetLang": "zh-TW", "followGameLanguage": false,
  "firstRunDone": true
}
```

   `translationRequestsEnabled=false` 確保不送請求（新安裝本來預設就是 false）；`aiBaseUrl` 指到不通的本機位址是雙保險；`firstRunDone=true` 免得標題畫面跳出首次啟動卡片。
4. **遊戲語言**：`<wt>/run/options.txt` 寫 `lang:zh_tw`（再各跑一輪 `lang:en_us`；`zh_tw` 時 mod 自己已有的譯文會直接顯示，倉庫只補它沒有的 key；`en_us` 時倉庫對所有英文都生效）。可順便加 `onboardAccessibility:false`、`tutorialStep:none`、`pauseOnLostFocus:false`。
5. **用倉庫檔做出本機倉庫快取**（等同「下載＋合併」，不必連網）：

```bash
# 【已實測】附錄 A.11：產生 <wt>/run/config/nyanlex-hub-cache-zh-tw.json；已用 HubLocalCache 讀回確認列數
python <tools>/hub_localcache.py --hub <wt>/translation-hub --out <wt>/run/config mod:<modId> [mod:<modId2> ...]
```

6. **（建議）記錄每次查詢的 HIT／MISS**：在拋棄式 worktree 裡把 `NyanLexFabric.java` 的 `service.setHubLookup(hubLocalCache::get);`（約第 1445 行）暫時改成：

```java
// 只在拋棄式 worktree，絕不 commit
service.setHubLookup(key -> {
    String v = hubLocalCache.get(key);
    LOGGER.info(v == null ? "HUBLOOKUP MISS {}" : "HUBLOOKUP HIT  {} => " + v, key);
    return v;
});
```

   之後從 `run/logs/latest.log` 抓 `HUBLOOKUP`。當初的一次性驅動程式（自動開畫面、截圖、記錄）曾放在拋棄式 worktree `hubverify`（未 commit，路徑 `src/main/java/ingametest/HubVerifyDriver.java`）；要不要重做都行，手動開畫面＋ F2 截圖就夠了。
7. **啟動**：

```bash
# 【依程式碼】runClient 這個 task 已確認存在（gradle tasks --all），但沒有在這份文件的撰寫過程中啟動遊戲
$GRADLE -p <wt> runClient --offline
```

8. 看什麼：有設定畫面的 mod 的選項標籤、tooltip；光影設定畫面的標籤（含 `X: ` 形式）；原版影像設定的數字列（要看「原文 MISS → 正規化 key HIT → 回填當下數字」這個過程）。上次驗證的結果：選項名稱與光影設定標籤命中。仍是英文的，多半是因為那些字串根本不在倉庫列內（mod 已有 zh_tw 的 key 沒有重翻、模組中繼資料描述不是 lang 資料），或是帶符號前綴的字串（例如 `◆ General`）。

**開發環境載入失敗**（最常見的原因）：

- **Fabric Loader 版本不夠**：開發樹的 `gradle.properties` 是 `loader_version=0.16.10`。有些 mod 的 `fabric.mod.json` 寫 `"fabricloader": ">=0.18"`（或 `>=0.19.x`），在這個環境載不起來，日誌會寫 `requires version 0.18 or later of fabricloader`。解法：在**拋棄式 worktree 的** `gradle.properties` 調高 `loader_version`（不要改進 repo），或改選要求較低的 mod。
- **MC 版本不符**：`mc` 依賴（如 `~26.2`）與 1.21.1 樹不合的 mod 不能在根目錄樹驗證；要用對應的樹（`fabric2612` 等，需另一套 JDK 與 Gradle，見記憶中的建置說明）。
- **缺依賴**：日誌會列出缺的 mod id（常見是設定庫、Fabric API 的某個模組）；把依賴 jar 一併放進 `run/mods/`。
- 看 `<wt>/run/logs/latest.log` 最前面的 `Incompatible mods found!` 區塊；Fabric Loader 開發環境會把 `run/mods` 裡的 jar 重新映射（結果在 `.fabric/processedMods/`，已 gitignore），第一次啟動比較慢。

---

## 5. 已知限制

1. **非數字的 `%s` 參數會跳過**（`NON_NUMERIC_ARG`）。`%s` 在執行時可能被代入名字、物品或數字，建置時無法知道，所以這類字串**不進倉庫**。實際占比：多數檔案 0~6%（一個大型內容模組 1,304 條中跳過 64 條）；條目很少的小檔占比可能很高（一個 22 條的檔跳過 7 條）。`%d`、`%.1f` 這類數值參數沒問題：以範例數字代入並以 `⟦MT#⟧` 正規化。
2. **介面標籤被截斷**：GUI 常常先把標籤截成固定寬度再補 `...` 才畫出來，畫圖掛鉤只看得到截斷後的片段，查不到。現行做法（commit `2053b34`，`TrimTranslation`＋`FontSplitMixin` 掛 `Font.plainSubstrByWidth(String,int)`）：在截字**之前**先把完整字串走一次介面文字管線（含倉庫查詢），有譯文就改截譯文。限制（【依程式碼】，未實機驗證）：
   - 只涵蓋走 `plainSubstrByWidth` 的截字；mod 自己寫的截字、走別的 API（`FormattedText` 版）的不涵蓋。
   - 文字輸入框（編輯框）一律排除，避免改到玩家正在打的字。
   - 譯文也太寬時仍會被截斷，由呼叫端補的 `...` 會殘留在中文後面；若查詢沒命中，畫面上殘留的是英文片段加 `...`。
   - 光影設定的標籤是 `標籤 + ": "` 一整串，`LangPackBuilder` 對 `option.*` 另外輸出 `X: ` 形式的列（譯文 `X： `，實測）以便命中。
   - 舊報告（`INFRA-REPORT`）寫「截斷是 mod 自己的行為，無解」已過時，以這個提交之後的行為為準。
3. **數字與色碼的查詢**（`TranslationCache.lookupExternal`、`TranslationService.translateScreenString`，commit `5f27fa6`）：
   - 查詢順序：原字串 → 去頭尾空白 → 數字正規化 key（`⟦MT#⟧`）；後者命中時以畫面當下的數字填回。所以含數字的畫面文字要靠 `⟦MT#⟧` 形式的列才命中（`LangPackBuilder` 對 `%d` 輸出的就是這種）。
   - 單色碼字串（`§f…`）：用純文字 key 查，譯文外面包回原色碼。
   - 多色碼的 Component：整行以 `⟦CS#⟧` 包色段的 key 查，各 run 的顏色保留。多色碼卻以**純字串**（不是 Component）畫出的，走原路徑，key 會帶著字面的 `§` 碼，倉庫裡沒有這種 key，所以不會命中。
   - 既有伺服器檔有 65.5% 的列含 `⟦MT#⟧`、約 55% 含 `⟦CS#⟧`（依報告），所以這兩條對命中率影響很大。
4. **書頁逐行查詢**：倉庫端是「每個繪製呼叫查一次」，**不會**把相鄰行先合併成段落；所以「每行一個 key、`en` 就是畫面上那一行」的行對齊譯文可以命中，前提是該 mod 真的逐行繪製。**未在遊戲內驗證過**（當時手上沒有可跑的版本）。例外：原版 `BookViewScreen` 在 `screenText(FormattedText)` 被明確排除（走書本模式的段落邏輯），不適用；同一個 Component 內含 `\n` 時才走段落合併（`⟦PB#⟧`）。
5. **mod 自己已有的 zh_tw 不會被覆蓋**：玩家用 zh_tw 時，mod 已有譯文的 key 直接顯示中文，倉庫列只補 mod 沒有的 key（所以資料只翻缺的）。
6. **執行時才組合的字串**（`COMPOSED_AT_RUNTIME`）、**不是 lang 的資料**（模組中繼資料描述）、**純符號或帶符號前綴的字串**、**註解抽取抓不到的下拉選項**：不在倉庫涵蓋範圍。
7. **一個來源一個語言一個檔**，上限 100,000 列／32 MiB；目前只有 `zh-tw`。
8. **命中時驗證不過的列會被丟棄**，且從本機快取刪掉：若 key 的形狀（數字、色碼段、段落換行的個數）與資料不合，該列永遠不會顯示。
9. **授權閘門只看輸入檔的 `license` 欄位字串**（§4.1）：它解析 SPDX 運算式並逐項精確比對簡單授權清單，但不讀授權文字；不在清單的一律不收，沒有人工裁定的通道。
10. **`core.autocrlf=true`** 讓工作目錄的資料檔變 CRLF。建議（**未實施，需改 repo 並先問使用者**）在 `.gitattributes` 加一行 `translation-hub/** -text`，讓資料檔永遠不被換行轉換，工作目錄與 blob 位元組相同。

---

## 6. 更新、下架、清除

### 6.1 重產後怎麼確認 byte-identical，以及只升級有變的檔

原則：**輸出一律先到 `<scratch>` 的倉庫副本；比對 git blob；只把真的有變的檔升級進 repo。**

```bash
# 1) 重產（用同一批輸入；入口與 §4.7 相同）。以下用實際做過的完整重產為例，
#    帶齊所有輸入檔（每個目標 mod 的本體、圖鑑、光影包都要在）
cp -r <wt>/translation-hub <scratch>/hub-out
$GRADLE -p <wt> langPackBuild --offline -q "-Pin=<所有輸入資料夾，以 ; 分隔>" \
  "-Pout=<scratch>/hub-out" -PshaderTarget=<modId> -PmergeIndex "-Preport=<scratch>/regen-report.txt"
# 2) 與 git blob 逐檔比對（IDENTICAL / DIFFERENT）
python <tools>/hub_check.py --repo <wt> --compare-dir <scratch>/hub-out
# 3) 乾跑：列出 NEW / CHANGED / SAME
python <tools>/hub_apply.py --repo <wt> --hub <wt>/translation-hub --scratch <scratch>/hub-out
# 4) 只把 NEW / CHANGED 的檔與它們的 index 條目寫進工作目錄
python <tools>/hub_apply.py --repo <wt> --hub <wt>/translation-hub --scratch <scratch>/hub-out --write
```

- **【已實測】** 以真實輸入（所有輸入檔，含授權被拒絕的項目，一併放進 `-Pin`）重產到空的暫存資料夾：`TOTAL rows=561 files=8`；8 個保留的檔與 git blob 逐檔 `cmp` **全部位元組相同**，其餘 9 個來源在 `LICENSE REFUSE` 行被閘門擋掉、沒有產出。伺服器那份檔案根本沒被碰（`langPackBuild` 不寫 `servers/`）。（採用簡單授權規則之前的紀錄：17 個檔、9,160 列，同樣全部相同。）
- 「byte-identical」只適用於**資料檔**。`index.json` 的 `updatedAt` 每次重產都會變（已實測：資料檔相同、index 只有被重產來源的 `updatedAt` 不同），這就是用 `hub_apply.py` 的原因：它只改真正有變的來源的 index 條目，其餘位元組不動（已實測：新增一個來源後，其他條目與原本位元組相同）。
- 比對的是 **git blob**（LF）；`--compare-dir` 讀暫存檔時會先把 CRLF 換成 LF。
- 變動的檔案要能解釋：新增的 mod、用語修正（例如把「生物群系」改成「生態域」）、授權變更。解釋不了的差異先別提交。
- `hubExport` 是**整檔覆寫**（§3）：伺服器檔重產只能用同一份（或更完整的）快取，且用同樣的方式做 `hub_subset.py` 比對。

### 6.2 收到下架要求（`[Takedown] <host 或 mod id> <語言>`）

1. **先確認**：申請人是不是該來源的權利人（伺服器營運者、模組作者），在 Issue 內請對方說明關係與範圍；有疑問先回覆，不要直接刪。要刪之前**先告知使用者**。
2. **刪檔案、改 index**（【已實測】附錄 A.5；刪除後 index 位元組回到沒有該來源的樣子）：

```bash
python <tools>/hub_remove.py --hub <wt>/translation-hub mods/<modId>/zh-tw.json     # 只刪某語言
python <tools>/hub_remove.py --hub <wt>/translation-hub servers/<host>              # 該來源的所有語言
git -C <wt> status --short                                                          # 確認只動了該來源與 index.json
python <tools>/hub_check.py --repo <wt>                                             # 提交後再跑一次
```

   - 要刪的是 `translation-hub/<servers|modpacks|mods>/<id>/<lang>.json`，以及 `index.json` 裡對應的那筆（來源沒有任何語言時連來源鍵一起刪）。`HubIndex` 沒有「刪除」API，所以必須用腳本或手動改。
   - 不要只刪 index 不刪檔案，也不要反過來：`hub_check.py` 會把兩邊對不上報 FAIL。
3. **商量範圍**：如果權利人要求的是整個 mod，也要確認同一 mod 檔裡併入的光影包翻譯是否一併受影響（光影併在載入光影的那個 mod 的檔裡）。
4. **提交訊息**：`hub: remove one <mod|server|modpack>'s translations (takedown request #<issue 號碼>)`。訊息裡不寫模組或伺服器的名稱，用 one mod／one server 代替；Issue 編號可以寫。見 §7.1。
5. **注意兩件事**：
   - 玩家已經下載的列還留在他們的本機快取，直到他們按「清除下載的翻譯包」；下架只保證之後不會再被下載。
   - 檔案仍在 git 歷史裡。資料只有雜湊與譯文、沒有原文，通常不構成問題；若權利人要求清掉歷史，那是改寫歷史（force push）的重大操作，**必須先問使用者**。
6. 到 Issue 回覆處理結果與 commit 連結後關閉。

### 6.3 玩家端的「清除下載的翻譯包」

- **介面**（commit `dfbccd7`）：設定 → 「翻譯包」分類 → 「清除下載的翻譯包…」→ 確認 →「已清除從翻譯包下載的 N 筆譯文。」（沒下載過則顯示「目前沒有下載的翻譯包。」）。【依程式碼：`HubPackCleaner`、`TranslationConfigScreen`】
- 做了什麼：`HubPackCleaner.clear` 清掉**目前語言**的本機倉庫快取（`HubLocalCache.clearAll`），並把該語言所有來源的「上次下載 sha256」從 `nyanlex-hub-state.json` 忘掉（`HubDownloadState.forgetLanguage`），讓下一次「偵測並下載」重新抓。**玩家自己的翻譯（主快取）不受影響。**
- 手動做法（關遊戲後）：刪 config 資料夾裡的 `nyanlex-hub-cache-<lang>.json` **和** `nyanlex-hub-state.json`。**只刪前者會讓下載紀錄還在，下次偵測會顯示「已是最新」而不重新下載**（`HubDownloadState` 的設計）。
- 設定畫面「檔案位置」群組也列出這兩個檔案（`FileLocations`：「已下載的翻譯包」與「翻譯包下載紀錄」）。

---

## 7. 發佈

### 7.1 commit 怎麼切分、訊息怎麼寫

**切分**：一個 commit 一個目的，資料與程式分開，文件另外：

| 內容 | 範圍 | 訊息主旨範例（歷史上實際用過的形狀） |
|---|---|---|
| 工具、測試、`build.gradle` | `src/main/java/.../hub/tool/`、`src/test/...`、`build.gradle` | `feat: ...`／`fix: ...`／`perf: ...`／`chore: ...`（依慣例前綴） |
| 新增來源的資料 | `translation-hub/mods/<modId>/`、`translation-hub/index.json` | `hub: add zh-TW translation files for <N> mods and shader settings` |
| 用語統一 | 受影響的資料檔與 index | `hub: align translations with vanilla Traditional Chinese terms` |
| 單一來源更新 | 該來源的檔與 index 條目 | `hub: use the common term for <word> in the shader options` |
| 伺服器重產 | `servers/<host>/` 與 index | `hub: exclude chat by default, regenerate <host> zh-tw without chat` |
| 下架 | 被刪的檔與 index | `hub: remove one mod's translations (takedown request #<n>)` |
| 文件 | `docs/`、`translation-hub/README.md` | `docs: ...` |

**訊息規則**：

- **英文**；資料 commit 用 `hub:` 前綴；主旨祈使句、不超過約 72 字元；內文說明「為什麼、做了什麼、數量」，每行約 72 字元。
- **不出現其他模組的名稱**（用「16 mods」「one mod」代替）、不出現使用者 email 前綴的個人識別字串（§2 第 8 條）、不放 email。
- 結尾要有 Co-Authored-By 行，使用**目前這個 session 給你的那一行**（歷史上是 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 或 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`）。
- `git add` 一律指定路徑；不要在主 repo 用 `git add -A`（可能夾帶別人的工作）。不用 `git stash`、不用 `--no-verify`。

```bash
# 【依程式碼／慣例】在你自己的 worktree 裡（先 git switch -c hub/<topic> 建一個分支）
git -C <wt> add translation-hub/mods/<modId>/zh-tw.json translation-hub/index.json
git -C <wt> commit -F - <<'EOF'
hub: add zh-TW translation files for <N> mods

Rows are sha256(key) -> translation; no source text. Only mods and shader
packs under simple permissive licenses are included.

Co-Authored-By: Claude <model> <noreply@anthropic.com>
EOF
```

**提交前檢查清單**（全部通過才 commit）：

1. `python <tools>/hub_check.py --repo <wt>` → `RESULT PASS`（要先把資料 `git add` 或 commit 才會被檢查到，它讀的是 git blob）。
2. `$GRADLE -p <wt> test --tests "com.dragonmeow.nyanlex.hub.*" --offline` 全綠。
3. `git -C <wt> grep -i -E 'bor[w]en'` 0 命中；`git -C <wt> diff --cached` 沒有 jar／zip／英文原文／`skips.tsv`／快取副本。
4. `git -C <wt> diff --cached --stat` 的檔案清單與你預期的完全一致（伺服器那份檔案沒出現，除非本來就要動它）。
5. 新增的來源，授權都在簡單授權清單內（或是 Polyform Shield 這個例外）；授權寫在報告裡（commit 內文不寫模組名）。

**落點**：目前資料 commit 都在 `release/nyanlex-1.0.0`（由整合中的主 worktree 持有）。不要在別人持有的分支上直接 commit；在自己的 worktree 開分支提交，再交給使用者或整合者合併。

### 7.2 推上 main 前一定要先問使用者

玩家端讀的是 `main` 分支的 raw 檔（`HubPaths.DEFAULT_BASE_URL`），所以**推 main 等於正式發佈給所有玩家**。**沒有使用者明確同意，不准推。**（也不 force push、不改寫歷史。）

問之前把這些備齊：

- 要推的 commit 清單，與 `git diff --stat origin/main..<branch> -- translation-hub`。
- 已跑的檢查結果（`hub_check.py`、測試、byte-identical 比對）。
- 授權與下架風險（新增或移除了哪些來源、授權為何）。
- **範圍提醒**：截至 2026-10-02，`origin/main` 在 `7ab9c92`（最後一個 hub 資料 commit），`release/nyanlex-1.0.0` 領先它 33 個 commit，兩者是祖先關係（fast-forward）。直接把 release 推成 main 會**連同所有程式碼 commit 一起發佈**；只想發佈資料時，要從 `origin/main` 開分支、只 cherry-pick 資料 commit。這個選擇也要問使用者。

```bash
# 【依程式碼／慣例】只在使用者明確同意之後
git -C <repo> push origin <分支>:main
```

### 7.3 推上去後用 raw.githubusercontent 檢查

`raw.githubusercontent.com` 有約 5 分鐘的快取（回應標頭 `Cache-Control: max-age=300`），推完等 5 分鐘再檢查。

```bash
git -C <wt> fetch origin main          # 讓 origin/main 指到剛推上去的版本
# 【已實測】附錄 A.1 的 --raw：先確認 raw 的 index.json 與 git 上的 blob 位元組相同，
# 再逐一下載每個資料檔，確認大小與 sha256 都與 index.json 記載相同（對目前的 origin/main 全部 OK）
python <tools>/hub_check.py --repo <wt> --rev origin/main \
  --raw https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/translation-hub
```

只檢查 index 的最小做法：

```bash
# 【已實測】兩行輸出要相同
curl -s https://raw.githubusercontent.com/DragonMeow1012/NyanLex/main/translation-hub/index.json | sha256sum
git -C <wt> show origin/main:translation-hub/index.json | sha256sum
```

最後在實際遊戲按「偵測並下載翻譯包」，確認確認畫面出現新的來源與筆數（【依程式碼】；需要新版 jar）。

---

## 8. 委派範本（給未來 AI 助手派工用）

派工前（使用者的全域守則）：同一時間最多 2 個 subagent，一律 `model: sonnet`，非必要不派 opus；委派 prompt **必須寫明「不准再派子 agent」**；高風險或要修改檔案的成果要由另一個全新 context 的 subagent 驗收，不能自己驗自己。把下面範本的 `<…>` 填好整段貼上。兩份範本都附了鐵則。

### 8.1 範本 1：翻譯組

```text
你是翻譯組 <group>。不准再派子 agent。

【任務】替下列客戶端模組／光影包做繁體中文（台灣，zh-TW）介面翻譯，成果之後會轉成 NyanLex 翻譯倉庫的資料。
項目（Modrinth slug）：<slug 清單>
工作資料夾（只能寫這裡）：<scratch>
工具（已存在於 <tools>，用 python 執行）：hub_modrinth.py、hub_extract_lang.py、hub_annotations.py、
hub_vanilla_glossary.py、hub_selfcheck.py

【步驟】
1. 查授權並下載（只放 <scratch>/jars/<slug>/，jar、zip 絕不進 repo）：
   python <tools>/hub_modrinth.py info <slug>      # 看 license.id、可用版本
   授權不在白名單就停手，寫進報告，不要翻譯（白名單：MIT、Apache-2.0、BSD-2-Clause、BSD-3-Clause、ISC、Zlib、CC0-1.0、
   Unlicense、CC-BY-3.0、CC-BY-4.0，外加使用者核准的例外 Polyform Shield；其餘一律不收：GPL／LGPL／AGPL／MPL、
   所有帶 SA／NC／ND 的 CC 授權、All Rights Reserved、自訂授權、查不到授權、所有其他 LicenseRef-*；不要自己裁定，回報即可）
   python <tools>/hub_modrinth.py get <slug> --dest <scratch>/jars --mc 1.21.1 --loader fabric
   （光影包用 --loader none。沒有 1.21.1 fabric 版：改選 neoforge 版；兩者都沒有就選最新正式版，並把實際的
   mcVersion 與 loader 寫進輸出檔。找不到可用版本就跳過並寫進報告。）
2. 抽取（只翻缺的）：
   python <tools>/hub_extract_lang.py <jar 或 zip> --slug <slug> --license "<license.id>" --version <v> --mc <mc> --out <scratch>/todo
   - 已有完整 zh_tw → 整個跳過，報告註明「已有 zh_tw」。只有一部分 → 只翻 zh_tw 沒有的 key。
   - 標 "existing":"placeholder" 的（zh_tw 只是英文）：專有名詞或搜尋關鍵字保持原樣，其餘要翻。
   - 沒有 lang 檔：先確認真的沒有其他語言資源，再用 hub_annotations.py 從 class 的註解抽（key 用
     "class:<類名>#<序號>"）；只抽玩家在設定畫面看得到的字串，不碰程式邏輯；抽不出可靠結果就記錄原因並跳過。
3. 建原版用語對照表：python <tools>/hub_vanilla_glossary.py --mc 1.21.1 find "<英文詞>" ...
   大型內容模組（物品、方塊、生物很多）另建 <scratch>/out/<group>/<modId>-glossary.md，全部都要翻，
   同一個詞前後同一譯法；每翻 300 條寫一次輸出檔。
4. 翻譯規則：
   - 用語以 Minecraft 原版繁中為準（顯示距離、畫質、生態域、區塊、最大 FPS、柔和光源、粒子密度…）；
     原版沒有的詞用台灣常用說法：設定（不是設置）、預設、啟用／停用、影像、品質。
   - 一律保留、不准增減、不准改順序：%s、%d、%1$s、%.1f、%%、{變數}、&&、§ 色碼、\n、前後空白。
     中文語序需要調換時改用 %1$s 這類有編號的參數。字面的 % 不是格式符號。
   - 品牌與專有名詞不翻：模組名、光影包名、作者名、API／技術名稱（OpenGL、Vulkan、FXAA、SSAO、TAA…）。
   - 按鈕與選項名稱要和英文差不多長；tooltip／說明可完整翻譯。
   - 不准抄其他翻譯專案或社群翻譯包的現成譯文，一律自己翻。不加 emoji、不加譯註、不用簡體字。
   - 行對齊的書本／圖鑑：一行一個 entry（key＝"<檔名>#<行號>"，en 就是畫面上那一行，行首空白原樣保留），
     行數與原文相同，指令行、註解行、空行不翻、不放進 entries。
5. 輸出（每個模組或光影包一個檔，UTF-8）：<scratch>/out/<group>/<modId 或 slug>.json
   {"kind":"mod","modId":"<真正的 mod id，取自 fabric.mod.json／mods.toml，不是 slug>","slug":"…","version":"…",
    "mcVersion":"…","loader":"…","license":"<license.id>","existingZhTw":"none|partial|full",
    "entries":[{"ns":"…","key":"…","en":"…","zh_tw":"…"}]}
   光影包：kind="shaderpack"、modId=null、加 "packName"、ns="shader"、key 保留原本的 option.* 等。
6. 自我檢查（每完成一個檔就跑，不符的修好）：
   python <tools>/hub_selfcheck.py <scratch>/out/<group>      # 要 RESULT PASS
7. 回報：寫 <scratch>/out/<group>/REPORT.md：每個項目的 mod id、版本、授權、既有 zh_tw 狀態、翻了幾條；
   跳過的項目與原因（含授權不在白名單的）；自我檢查結果；任何你覺得需要人裁定的事（翻不準的詞）。

【鐵則（違反任一條就是做錯）】
- 不准再派子 agent。只能寫 <scratch>；不修改 repo、不 commit、不 push、不用 git stash。
- 不讀、不印、不複製任何含 API 金鑰的設定檔，也不複製使用者的翻譯快取。
- 不啟動 Minecraft；不送任何翻譯請求（不呼叫翻譯 API、不用翻譯網站）。
- Modrinth API 只用 hub_modrinth.py：每秒不超過 2 個請求，出錯就停下來，不要重試轟炸；
  User-Agent 已內建 DragonMeow/NyanLex-hub-builder (github.com/DragonMeow1012/NyanLex)。
- 不殺任何不是你啟動的行程。
- jar、zip、英文原文、使用者快取副本都不得進 repo。
- 程式、註解、文件、commit 不得出現其他模組名稱（你的輸出檔與報告可以有，它們只在 <scratch>）；不得出現使用者 email 前綴的個人識別字串（檢查：git grep -i -E 'bor[w]en' 必須 0 命中）。
- 授權只收簡單授權白名單（§4.1）；GPL／LGPL／MPL、帶 SA／NC／ND 的 CC、ARR、自訂授權、查不到授權的不翻。
```

### 8.2 範本 2：轉換與驗證組

```text
你是轉換與驗證組 <group>。不准再派子 agent。

【任務】把翻譯組的中間 JSON 轉成倉庫資料並驗證，不 commit、不 push。
輸入資料夾（只讀）：<scratch>/out/<group...>（翻譯組輸出；含英文原文，不得進 repo）
工作資料夾（只能寫這裡）：<scratch>；工具在 <tools>（hub_selfcheck.py、hub_check.py、hub_apply.py、hub_localcache.py）
環境：export JAVA_HOME="C:\Program Files\Java\jdk-21"；GRADLE=<repo>/.gradle-local/gradle-8.10/bin/gradle；
自己開拋棄式 worktree：git -C <repo> worktree add --detach <wt> release/nyanlex-1.0.0（用完 git worktree remove）。
不要在別人正在用的 worktree 裡跑 gradle。

【步驟】
1. 自檢輸入：python <tools>/hub_selfcheck.py <輸入資料夾>      # 必須 RESULT PASS，否則退回翻譯組
2. 重產到暫存副本（絕不把 -Pout 指向 repo 的 translation-hub）：
   cp -r <wt>/translation-hub <scratch>/hub-out
   $GRADLE -p <wt> langPackBuild --offline -q "-Pin=<輸入資料夾 1>;<輸入資料夾 2>…" "-Pout=<scratch>/hub-out" \
     -PshaderTarget=<modId> -PmergeIndex "-Preport=<scratch>/report.txt" "-Pskips=<scratch>/skips.tsv"
   - 每個目標 mod 的所有輸入檔（本體、圖鑑、光影包…）都要帶齊；-PshaderTarget 沒給，光影輸入會被整個忽略。
   - 逐行看 "LICENSE ACCEPT|REFUSE"：授權閘門解析 SPDX 運算式後精確比對簡單授權白名單（只有 Polyform Shield 這個例外；GPL／LGPL／AGPL／MPL、帶 SA／NC／ND 的 CC、所有其他 LicenseRef-* 一律 REFUSE），ACCEPT 的仍要對照 Modrinth 的 license.id 複核；
     與預期不同就停下來回報，不要自己改 license 欄位。
   - skips.tsv 含英文原文，只能留在 <scratch>，用完刪除。
3. 比對與驗證：
   python <tools>/hub_check.py --repo <wt> --compare-dir <scratch>/hub-out --originals <輸入資料夾>
   python <tools>/hub_apply.py --repo <wt> --hub <wt>/translation-hub --scratch <scratch>/hub-out     # 乾跑，列 NEW/CHANGED/SAME
   - 乾跑結果與預期不符（該新增的沒新增、不該動的在 CHANGED）就停下來回報。
   - 要寫入才加 --write；寫入後 `python <tools>/hub_check.py --repo <wt>` 需 RESULT PASS（它讀的是 git blob，
     所以要先 git add 再檢查，或交給我提交後檢查）。
   - 值不含原文：--originals 的 SEVERE 必須是 0；REVIEW 逐筆看是不是專有名詞。
   - 另外抽 20 筆譯文看（用語、簡體字、明顯誤譯）。
4. 測試：$GRADLE -p <wt> test --tests "com.dragonmeow.nyanlex.hub.*" --offline     # 要全綠
5.（需要時）遊戲內驗證：照文件 §4.9，只在你自己的拋棄式 worktree 做；0 個翻譯請求；run/ 不進 repo。
6. 回報：寫 <scratch>/REPORT.md：每個 mod 的 entries／rows／skipped（含各原因）、授權判定表（ACCEPT/REFUSE 與依據）、
   hub_check／測試結果、NEW/CHANGED/SAME 清單、需要人裁定的事。最後移除自己開的 worktree 與 <scratch> 內的快取副本。

【鐵則（違反任一條就是做錯）】
- 不准再派子 agent。只能寫 <scratch> 與你自己的拋棄式 worktree；不修改別人的 worktree、不 commit、不 push、不用 git stash。
- 不讀、不印、不複製任何含 API 金鑰的設定檔，也不複製使用者的翻譯快取；匯出伺服器資料時只複製單一檔案
  nyanlex-ai-cache-<lang>.json 到暫存資料夾，用完立刻刪除。
- 不送任何翻譯請求（驗證時 translationRequestsEnabled=false，aiBaseUrl 指向不通的本機位址）。
- key 只能由 langPackBuild／hubExport 產生，不准自己重寫正規化。
- hubExport 是整檔覆寫：不要對既有的伺服器來源重新匯出，除非明確要求；任何時候都不得改動伺服器那份檔案。
- 不殺任何不是你啟動的行程。
- jar、zip、英文原文、skips.tsv、使用者快取副本都不得進 repo。
- 程式、註解、文件、commit 不得出現其他模組名稱（資料檔與資料夾名稱除外）；不得出現使用者 email 前綴的個人識別字串（檢查：git grep -i -E 'bor[w]en' 必須 0 命中）。
- 授權只收簡單授權白名單（§4.1）；GPL／LGPL／MPL、帶 SA／NC／ND 的 CC、ARR、自訂授權、查不到授權的不轉換。
```

---

## 9. 疑難排解

### 9.1 「Unsupported translation file schema」

意思：**玩家用的是舊版 jar**。

- 舊版（倉庫檔還是含原文的 schema 1 時期的 hub 初版 jar，改名前的建置）的下載器用 `TranslationFile.read` 讀倉庫檔，只認 `"schema": 1`，遇到現在倉庫的 schema 2 就丟出這個訊息，下載畫面／通知會顯示「翻譯包下載失敗：Unsupported translation file schema」。
- 現行版本用 `HubFile.read`，訊息不同：schema 不是 2 會是 `Unsupported hub file schema: <n>`。看到 `Unsupported translation file schema` 就代表對方不是現行版。
- 解法：請玩家**更新 mod jar** 到現行版本。**不要**把倉庫改回 schema 1：那會重新散布原文，違反鐵則 1。
- 舊版 jar 內建的倉庫網址是改名前的 repo 名稱；目前舊網址仍能取得相同內容（【已實測】）。

現行版本其他訊息的意思：

| 訊息 | 意思 | 處理 |
|---|---|---|
| `Unsupported hub file schema: 1` | 檔案是舊的 schema 1（含原文） | 不該出現在倉庫；用 `hub_check.py` 找出來 |
| `Unsupported hub file format` | `format` 不是 `hub-hash-v1` | 重新產生 |
| `Invalid hub row` | 某個 key 不是 64 位小寫十六進位，或值不是字串 | `hub_check.py` 會指出 |
| `Hub file exceeds … bytes` / `Invalid hub rows` | 超過 32 MiB／100,000 列 | §3.2 的上限說明 |
| `Invalid hub index` | `index.json` 壞了 | 用 `hub_check.py` 檢查，從 git 還原 |

### 9.2 下載了卻沒顯示：依序檢查

1. **目標語言對得上嗎？** 倉庫檔的 `language`（`zh-tw`）必須等於玩家目前的目標語言，否則整檔 0 列合併（`mergeFromFile` 回傳 languageRejected）。
2. **本機快取真的有列嗎？** 看 `config/nyanlex-hub-cache-<lang>.json` 的 `rows`，每列的 `s` 標籤會寫來源（`mod:<modId>`／`server:<host>`）。沒有列 → 看下載結果的「新增／略過／拒收」數字；結果列「新增 0 筆、略過 N 筆」代表同一個雜湊早就由更高優先權的來源或先前下載佔住了。
3. **該顯示面的顯示方式是不是「翻譯」？** 倉庫列只在該 surface（介面文字、物品提示、名牌…）設為顯示譯文時才會用；設成「只顯示原文」就永遠不會顯示。**翻譯服務（AI 或機翻）選哪個無關**，總開關「線上翻譯」關著也會顯示倉庫列。
4. **畫面是不是在下載完成前就開著？** 下載完成後會清掉渲染記憶（`FabricTextStyle.clearRenderMemo()`），但重開那個畫面最保險。
5. **key 對得上嗎？** 倉庫列只在「畫面字串經遮罩後的 key」完全相同時命中。常見不命中原因：字串被截斷、畫面字串有符號前綴（`◆ …`）、含 `%s` 的字串（根本沒進倉庫）、mod 自己組合字串、玩家用 zh_tw 且 mod 自己已有該 key 的譯文。用 §4.9 步驟 6 的 HIT／MISS 記錄看實際查了什麼 key。
6. **列被丟掉了？** 命中時驗證不過（標記個數不合、含網址）的列會被刪掉；若本機快取的列數無故減少，就是這個。
7. **下載紀錄與快取不同步？** 手動刪了 `nyanlex-hub-cache-<lang>.json` 但沒刪 `nyanlex-hub-state.json`，偵測會顯示「已是最新」而不再下載 → 刪兩個檔，或用「清除下載的翻譯包…」。
8. **index 有列出嗎？** mod id 區分大小寫、必須與載入器回報的完全相同；`index.json` 沒列的 mod 在確認畫面只會顯示「沒有翻譯包」。剛推上去的資料要等 raw 快取（約 5 分鐘）。
9. **還是不行**：把 `hub_check.py --rev origin/main --raw …` 跑一遍，確認線上的檔案與 index 一致。

### 9.3 Google 429 是 IP 被封，和倉庫無關

- 倉庫查詢**完全不連 Google**：它只讀本機快取檔；下載倉庫只對 `raw.githubusercontent.com` 發 GET。
- Google 機翻（未官方的免金鑰端點）回 429 是 Google 對你這個 **IP** 的異常流量封鎖（用 `curl` 直接打也會被擋，不是 HTTP/2 問題）。解法是等待、換網路，或改用 AI／官方 API 服務；程式端已有全域 429 退避閘門與請求節流，閘門關著時不會送出新請求（見 `GoogleFreeTranslator`、`MachineTranslationGateTest`）。
- 判斷方式：先用 `curl` 對照同一個端點，再下結論，不要先懷疑倉庫。倉庫命中的字串不受影響。

### 9.4 開發環境模組載入失敗

見 §4.9 最後一段：Fabric Loader 版本不夠（開發樹是 `loader_version=0.16.10`，要求 `>=0.18` 的 mod 載不起來）、MC 版本不符、缺依賴、Java 版本。第一手資料是 `<wt>/run/logs/latest.log` 最前面的 `Incompatible mods found!` 區塊。

### 9.5 其他常見狀況

| 症狀 | 原因與處理 |
|---|---|
| Gradle 啟動就炸 | `JAVA_HOME` 沒設成 JDK 21（系統預設 Java 25 搭 Gradle 8.10 會出錯）；一律 `--offline`，缺快取時（例如某些樹要連網下載 MC）不是程式問題 |
| `Process … finished with non-zero exit value 2` | `hubExport` 的用法錯誤（上面一行會印原因，如 `Exactly one of --server / --modpack / --mod is required`、`Missing --cache-dir`、`--server is not a public registrable host`）。`hubExport` 的 exit 1 是 `ERROR …`（超過列數／大小上限、檔案寫不出）；**`langPackBuild` 的用法錯誤也是 exit 1**（印 `ERROR --in and --out are required`、`ERROR Unknown flag …`，已實測） |
| `NOTHING_TO_EXPORT` | `-PcacheDir` 裡沒有 `nyanlex-ai-cache-<tag>.json`（檔名與語言標籤要對），或所有列都被濾掉 |
| `langPackBuild` 沒有輸出某個 mod | 授權被 REFUSE、`modId` 空白、光影輸入沒給 `-PshaderTarget`、或輸入檔沒有 `entries` 陣列（被默默忽略）；看 `LICENSE …` 行 |
| 某個 mod 的列重產後變少 | 少帶了它的某個輸入檔（本體／圖鑑／光影），§4.7 的「全量重建」 |
| `hub_check.py` 說 bytes／sha256 與 index 不符 | 通常是拿工作目錄的 CRLF 檔算；這個腳本讀 git blob，所以代表 index 真的過期了，用 `hub_apply.py`／重產更新 |
| `git status` 顯示 `LF will be replaced by CRLF` | `core.autocrlf=true` 的正常警告，blob 仍是 LF |
| 中文輸出變亂碼 | Python 在 Windows 預設 cp932；用附錄的腳本（已 reconfigure），或設 `PYTHONIOENCODING=utf-8` |

---

## 附錄 A：腳本全文

以下 11 支腳本都是**只用 Python 標準庫**、可以直接存成同名檔案使用（建議放進 repo 的 `docs/hub-tools/`，或存在 `<scratch>/tools/`）。全部在 2026-10-02 於 `hubdoc` worktree 以假資料或唯讀資料實測過（見附錄 B）。腳本內的所有範例都用代號，不含模組名稱。

| 編號 | 檔名 | 用途 |
|---|---|---|
| A.1 | `hub_check.py` | 倉庫一致性檢查（讀 git blob）、重產比對、洩漏檢查、raw 線上檢查 |
| A.2 | `hub_subset.py` | 同一檔兩個版本的子集比對 |
| A.3 | `hub_apply.py` | 只把有變的檔與其 index 條目升級進 repo |
| A.4 | `hub_hashname.py` | 第三方名稱過濾名單：算雜湊、驗校驗碼 |
| A.5 | `hub_remove.py` | 下架：刪檔案並從 index 移除 |
| A.6 | `hub_modrinth.py` | 查授權、下載（UA、節流、sha512 驗證） |
| A.7 | `hub_extract_lang.py` | 從 jar／zip 抽取要翻譯的骨架 |
| A.8 | `hub_annotations.py` | 沒有 lang 檔時從 class 註解抽取字串 |
| A.9 | `hub_selfcheck.py` | 翻譯組中間檔自我檢查 |
| A.10 | `hub_vanilla_glossary.py` | 原版用語對照表 |
| A.11 | `hub_localcache.py` | 把倉庫檔轉成本機倉庫快取（遊戲內驗證用） |

### A.1 `hub_check.py`

```python
#!/usr/bin/env python3
"""Consistency check for translation-hub/ data, computed from GIT BLOBS (LF bytes).

Why blobs: on Windows core.autocrlf=true turns every LF in a checked-out data file into CRLF,
so sha256/size of the working-tree file differ from index.json (which records the LF form that
raw.githubusercontent.com serves).  Always hash `git cat-file blob`, never the checked-out file.

Usage (run from anywhere; --repo is the git work tree that contains translation-hub/):
  python hub_check.py --repo <repo> [--rev HEAD] [--prefix translation-hub]
                      [--compare-dir <scratch translation-hub dir>]   # regenerated output vs blobs
                      [--originals <dir of translation-team input json>]  # leak check (optional)
                      [--raw https://raw.githubusercontent.com/<owner>/<repo>/main/translation-hub]
                          # after pushing: every published file must hash to what index.json says

Exit code 0 = every check passed, 1 = at least one FAIL.
"""
import argparse, hashlib, json, os, re, subprocess, sys

try:  # the Windows console may be cp932/cp1252; values contain CJK and the marker brackets
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

HEX64 = re.compile(r"^[0-9a-f]{64}$")
CJK = re.compile(r"[一-鿿]")
ALLOWED_TOP = {"index.json", "LICENSE", "README.md"}
fails = []


def fail(msg):
    fails.append(msg)
    print("FAIL", msg)


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], capture_output=True, check=True).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--prefix", default="translation-hub")
    ap.add_argument("--compare-dir")
    ap.add_argument("--originals")
    ap.add_argument("--raw")
    a = ap.parse_args()

    files = git(a.repo, "ls-tree", "-r", "--name-only", a.rev, a.prefix).decode().split()
    rel = [f[len(a.prefix) + 1:] for f in files]
    index = json.loads(git(a.repo, "show", f"{a.rev}:{a.prefix}/index.json").decode("utf-8"))

    # 1. nothing but data in the repository folder
    data_files = []
    for r in rel:
        parts = r.split("/")
        if r in ALLOWED_TOP:
            continue
        if len(parts) == 3 and parts[0] in ("servers", "modpacks", "mods") and parts[2].endswith(".json"):
            data_files.append(r)
        else:
            fail(f"unexpected file in {a.prefix}/: {r}")

    # 2. every data file: schema 2, hashed keys, index agrees (rows / bytes / sha256 of the blob)
    seen = set()
    values = []
    for r in data_files:
        kind, ident, fname = r.split("/")
        lang = fname[:-5]
        blob = git(a.repo, "show", f"{a.rev}:{a.prefix}/{r}")
        d = json.loads(blob.decode("utf-8"))
        entries = d.get("entries", {})
        if d.get("schema") != 2 or d.get("format") != "hub-hash-v1" or d.get("hash") != "sha256":
            fail(f"{r}: not schema 2 / hub-hash-v1 / sha256")
        if d.get("language") != lang:
            fail(f"{r}: language field {d.get('language')!r} != file name {lang!r}")
        if d.get("rows") != len(entries):
            fail(f"{r}: rows field {d.get('rows')} != {len(entries)} entries")
        bad = [k for k in entries if not HEX64.match(k)]
        if bad:
            fail(f"{r}: {len(bad)} keys are not 64 lowercase hex")
        empty = [k for k, v in entries.items() if not isinstance(v, str) or not v or len(v) > 16384]
        if empty:
            fail(f"{r}: {len(empty)} empty / non-string / over-long values")
        if set(d) - {"schema", "format", "hash", "language", "rows", "entries"}:
            fail(f"{r}: unexpected top-level fields {sorted(set(d) - {'schema','format','hash','language','rows','entries'})}")
        section = {"servers": "servers", "modpacks": "modpacks", "mods": "mods"}[kind]
        st = index.get(section, {}).get(ident, {}).get(lang)
        seen.add((section, ident, lang))
        if st is None:
            fail(f"{r}: not listed in index.json")
        else:
            if st["rows"] != len(entries):
                fail(f"{r}: index rows {st['rows']} != {len(entries)}")
            if st["bytes"] != len(blob):
                fail(f"{r}: index bytes {st['bytes']} != blob {len(blob)}")
            if st["sha256"] != hashlib.sha256(blob).hexdigest():
                fail(f"{r}: index sha256 != sha256(blob)")
        no_cjk = sum(1 for v in entries.values() if not CJK.search(v))
        print(f"{r}: rows={len(entries)} bytes={len(blob)} no-CJK-values={no_cjk}")
        values.extend(entries.values())

    # 3. index lists nothing that has no file
    for section in ("servers", "modpacks", "mods"):
        for ident, langs in index.get(section, {}).items():
            for lang in langs:
                if (section, ident, lang) not in seen:
                    fail(f"index.json lists {section}/{ident}/{lang} but the file is not in the repo")

    # 4. optional: regenerated scratch output must equal the blobs byte for byte
    if a.compare_dir:
        for r in data_files:
            p = os.path.join(a.compare_dir, *r.split("/"))
            if not os.path.isfile(p):
                print(f"compare: {r} not regenerated (skipped)")
                continue
            scratch = open(p, "rb").read().replace(b"\r\n", b"\n")  # normalise a checked-out copy
            blob = git(a.repo, "show", f"{a.rev}:{a.prefix}/{r}")
            print(f"compare {r}: {'IDENTICAL' if scratch == blob else 'DIFFERENT'}")
            if scratch != blob:
                fail(f"{r}: regenerated output differs from the committed blob")

    # 5. optional leak check: a source (English) string of the input files must not sit verbatim in a value
    if a.originals:
        needles = set()
        for root, _, names in os.walk(a.originals):
            for n in names:
                if not n.endswith(".json"):
                    continue
                try:
                    obj = json.load(open(os.path.join(root, n), encoding="utf-8"))
                except Exception:
                    continue
                for e in obj.get("entries", []) if isinstance(obj, dict) else []:
                    en = (e.get("en") or "").strip()
                    if len(en) >= 16 and re.search(r"[A-Za-z]{4}", en):
                        needles.add(en)
        hits = [(n, v) for v in values for n in needles if n in v]
        # FAIL: the value IS the source text, or contains a 6+ word source sentence.
        # Everything else (a product / event / item name left in English inside a Chinese sentence) is REVIEW.
        severe = [h for h in hits if h[1].strip() == h[0] or len(h[0].split()) >= 6]
        print(f"leak check: {len(needles)} source strings (>=16 chars), {len(hits)} verbatim hits, "
              f"{len(severe)} severe")
        for n, v in hits[:10]:
            print("   REVIEW (proper noun left in English?):", n[:50], "|", v[:50])
        for n, v in severe[:10]:
            print("   SEVERE:", n[:80], "|", v[:80])
        if severe:
            fail(f"{len(severe)} values contain the source text itself or a 6+ word source sentence")

    # 6. optional: what GitHub actually serves (raw.githubusercontent.com caches for ~5 minutes)
    if a.raw:
        import time, urllib.request
        def fetch(rel):
            time.sleep(0.3)
            req = urllib.request.Request(a.raw.rstrip("/") + "/" + rel, headers={"User-Agent": "hub-check"})
            return urllib.request.urlopen(req, timeout=120).read()
        got = fetch("index.json")
        want = git(a.repo, "show", f"{a.rev}:{a.prefix}/index.json")
        print("raw index.json:", "same as the blob" if got == want else "DIFFERENT from the blob (cache lag?)")
        if got != want:
            fail("raw index.json differs from the committed blob")
        raw_index = json.loads(got.decode("utf-8"))
        for section in ("servers", "modpacks", "mods"):
            for ident, langs in raw_index.get(section, {}).items():
                for lang, st in langs.items():
                    data = fetch(f"{section}/{ident}/{lang}.json")
                    ok = len(data) == st["bytes"] and hashlib.sha256(data).hexdigest() == st["sha256"]
                    print(f"raw {section}/{ident}/{lang}.json: {'OK' if ok else 'MISMATCH'}")
                    if not ok:
                        fail(f"raw {section}/{ident}/{lang}.json does not match index.json")

    print("RESULT", "PASS" if not fails else f"FAIL ({len(fails)})")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
```

### A.2 `hub_subset.py`

```python
#!/usr/bin/env python3
"""Subset comparison of ONE hub data file between two git revisions.

Typical use: after regenerating a server file WITHOUT chat (the default), every new key must already
exist in the older file that was exported from the same cache (new keys not in old = 0), and no
translation text may have changed behind the same key.  Compare git blobs, never working-tree files.

  python hub_subset.py --repo <repo> --path translation-hub/servers/<host>/zh-tw.json <old-rev> [<new-rev>=HEAD]
"""
import argparse, json, subprocess, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def load(repo, rev, path):
    raw = subprocess.run(["git", "-C", repo, "show", f"{rev}:{path}"], capture_output=True, check=True).stdout
    data = json.loads(raw.decode("utf-8"))
    entries = data.get("entries")
    if not isinstance(entries, dict):  # schema 1 / unknown layout: fall back to the biggest object field
        entries = max((v for v in data.values() if isinstance(v, dict)), key=len, default=data)
    return data, entries


ap = argparse.ArgumentParser()
ap.add_argument("--repo", required=True)
ap.add_argument("--path", required=True)
ap.add_argument("old")
ap.add_argument("new", nargs="?", default="HEAD")
a = ap.parse_args()

old_meta, old = load(a.repo, a.old, a.path)
new_meta, new = load(a.repo, a.new, a.path)
print("schema old/new:", old_meta.get("schema"), new_meta.get("schema"))
print("rows old/new:", len(old), len(new))
extra = set(new) - set(old)
print("new keys not in old (must be 0 when the same cache was re-exported without chat):", len(extra))
print("removed (old keys missing in new):", len(set(old) - set(new)))
changed = sum(1 for k in new if k in old and new[k] != old[k])
print("same key, different translation:", changed)
sys.exit(1 if extra or changed else 0)
```

### A.3 `hub_apply.py`

```python
#!/usr/bin/env python3
"""Copy ONLY the changed data files (and only their index.json entries) from a scratch regeneration
into the repository working tree.

Why: the converters always rewrite index.json's "updatedAt" for every source they touch, even when the
data file came out byte-identical.  Writing straight into the repo would put timestamp noise on every
regenerated source (and a stray hubExport run could overwrite an unrelated server file).  So regenerate
into a scratch copy, then use this to promote just what really changed.

  python hub_apply.py --repo <repo> --hub <repo>/translation-hub --scratch <scratch translation-hub dir>
                      [--rev HEAD] [--write]

Without --write it only reports NEW / CHANGED / SAME per file.  With --write it copies NEW and CHANGED data
files (LF bytes) into --hub and replaces/inserts exactly those sources' entries in --hub/index.json
(compact JSON like the tools write it, sections sorted by key).  SAME files and their index entries
are left alone, so an unchanged source (the existing server file included) can never be touched.
"""
import argparse, hashlib, json, os, subprocess, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def git_blob(repo, rev, path):
    r = subprocess.run(["git", "-C", repo, "show", f"{rev}:{path}"], capture_output=True)
    return r.stdout if r.returncode == 0 else None


def dump(obj):
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--hub", required=True)
    ap.add_argument("--scratch", required=True)
    ap.add_argument("--rev", default="HEAD")
    ap.add_argument("--prefix", default="translation-hub")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    scratch_index = json.load(open(os.path.join(a.scratch, "index.json"), encoding="utf-8"))
    index_path = os.path.join(a.hub, "index.json")
    index_text = open(index_path, encoding="utf-8").read()
    index = json.loads(index_text)
    if dump(index) != index_text:
        sys.exit("refusing to edit: index.json is not in the compact form this script reproduces exactly")

    todo = []
    for section in ("servers", "modpacks", "mods"):
        for ident, langs in scratch_index.get(section, {}).items():
            for lang, st in langs.items():
                rel = f"{section}/{ident}/{lang}.json"
                p = os.path.join(a.scratch, *rel.split("/"))
                if not os.path.isfile(p):
                    continue
                data = open(p, "rb").read().replace(b"\r\n", b"\n")
                if hashlib.sha256(data).hexdigest() != st["sha256"] or len(data) != st["bytes"]:
                    sys.exit(f"{rel}: scratch file does not match its own index entry; regenerate it")
                old = git_blob(a.repo, a.rev, f"{a.prefix}/{rel}")
                state = "NEW" if old is None else ("SAME" if old == data else "CHANGED")
                print(f"{state:8} {rel}  rows={st['rows']} bytes={st['bytes']}")
                if state != "SAME":
                    todo.append((section, ident, lang, rel, data, st))
    if not todo:
        print("nothing to apply: every regenerated file is byte-identical to the committed blob")
        return
    if not a.write:
        print(f"dry run: {len(todo)} file(s) would be written (add --write)")
        return
    for section, ident, lang, rel, data, st in todo:
        target = os.path.join(a.hub, *rel.split("/"))
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as f:
            f.write(data)
        index.setdefault(section, {}).setdefault(ident, {})[lang] = st
    for section in ("servers", "modpacks", "mods"):
        index[section] = {k: dict(sorted(v.items())) for k, v in sorted(index.get(section, {}).items())}
    with open(index_path, "w", encoding="utf-8", newline="") as f:
        f.write(dump(index))
    print(f"wrote {len(todo)} file(s) and their index entries")


if __name__ == "__main__":
    main()
```

### A.4 `hub_hashname.py`

```python
#!/usr/bin/env python3
"""Add a third-party name to ThirdPartyModFilter (hash only; the name itself never enters the repo).

The Java side normalises a name to lower-case ASCII letters+digits (everything else removed) and
stores sha256(normalised).  A name only matches text made of 1-3 words, so a name of more than 3
words is useless, and a name with no ASCII letters/digits normalises to "" (refuse it).

  python hub_hashname.py --java <repo>/src/main/java/com/dragonmeow/nyanlex/hub/tool/ThirdPartyModFilter.java
                         [--bracket] "Some Name" "Other Name"

  - no names given: only re-verifies NAME_HASHES against NAME_HASHES_CHECKSUM
  - --bracket: the names only count when written inside square brackets, e.g. "[abc]"
    (use it for short abbreviations that would otherwise collide with ordinary words)
Prints the new sorted array body and the new checksum; paste them into the Java file by hand,
then update ThirdPartyModFilterTest.realHashListIsUnchanged (the expected count and the checksum literal).
"""
import argparse, hashlib, re, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def norm(name):
    return "".join(c for c in name.lower() if c in "abcdefghijklmnopqrstuvwxyz0123456789")


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def block(src, array):
    m = re.search(r"static final String\[\] " + array + r" = \{(.*?)\};", src, re.S)
    return re.findall(r'"([0-9a-f]{64})"', m.group(1))


ap = argparse.ArgumentParser()
ap.add_argument("--java", required=True)
ap.add_argument("--bracket", action="store_true")
ap.add_argument("names", nargs="*")
a = ap.parse_args()
src = open(a.java, encoding="utf-8").read()
names = block(src, "NAME_HASHES")
brackets = block(src, "BRACKET_HASHES")
declared = re.search(r'NAME_HASHES_CHECKSUM\s*=\s*"([0-9a-f]{64})"', src).group(1)
current = sha("\n".join(sorted(set(names))))
print(f"NAME_HASHES: {len(names)} entries; checksum {'OK' if current == declared else 'MISMATCH'} ({declared[:12]}...)")
print(f"BRACKET_HASHES: {len(brackets)} entries")
target = set(brackets if a.bracket else names)
for n in a.names:
    k = norm(n)
    if not k:
        sys.exit(f"refused: {n!r} has no ASCII letters or digits")
    words = len(re.findall(r"[A-Za-z0-9]+", n))
    if words > 3 and not a.bracket:
        print(f"warning: {n!r} is {words} words; only 1-3 word sequences are ever matched")
    h = sha(k)
    print(f"{n!r} -> normalised {k!r} -> {h}  {'(already listed)' if h in target else '(new)'}")
    target.add(h)
if a.names:
    new = sorted(target)
    print("\nnew array body:")
    print(",\n".join(f'            "{h}"' for h in new))
    if not a.bracket:
        print("\nnew NAME_HASHES_CHECKSUM:", sha("\n".join(new)))
        print("new count (for the test):", len(new))
```

### A.5 `hub_remove.py`

```python
#!/usr/bin/env python3
"""Takedown helper: delete data files and delist them from index.json (compact form, sorted).

  python hub_remove.py --hub <repo>/translation-hub mods/<modId>/zh-tw.json [servers/<host>/zh-tw.json ...]
  python hub_remove.py --hub <repo>/translation-hub mods/<modId>          # every language of that source

Then review `git status` / `git diff --stat`, and commit (see the guide: "hub: remove <what> (takedown request #<issue>)").
The index file is rewritten only after every named file was found.
"""
import argparse, json, os, shutil, sys

ap = argparse.ArgumentParser()
ap.add_argument("--hub", required=True)
ap.add_argument("targets", nargs="+")
a = ap.parse_args()
index_path = os.path.join(a.hub, "index.json")
text = open(index_path, encoding="utf-8").read()
index = json.loads(text)
if json.dumps(index, ensure_ascii=False, separators=(",", ":")) != text:
    sys.exit("refusing: index.json is not in the compact form the tools write")
plan = []
for t in a.targets:
    parts = t.strip("/").split("/")
    if parts[0] not in ("servers", "modpacks", "mods") or len(parts) not in (2, 3):
        sys.exit(f"not a source path: {t}")
    section, ident = parts[0], parts[1]
    langs = index.get(section, {}).get(ident)
    if langs is None:
        sys.exit(f"{t}: not listed in index.json")
    wanted = [parts[2][:-5]] if len(parts) == 3 else list(langs)
    for lang in wanted:
        if lang not in langs:
            sys.exit(f"{t}: language {lang} not listed")
        plan.append((section, ident, lang))
for section, ident, lang in plan:
    path = os.path.join(a.hub, section, ident, lang + ".json")
    if os.path.isfile(path):
        os.remove(path)
    del index[section][ident][lang]
    if not index[section][ident]:
        del index[section][ident]
        folder = os.path.join(a.hub, section, ident)
        if os.path.isdir(folder) and not os.listdir(folder):
            shutil.rmtree(folder)
    print("removed", f"{section}/{ident}/{lang}.json")
with open(index_path, "w", encoding="utf-8", newline="") as f:
    f.write(json.dumps(index, ensure_ascii=False, separators=(",", ":")))
```

### A.6 `hub_modrinth.py`

```python
#!/usr/bin/env python3
"""Look up licence + download a mod / shader pack from the official Modrinth API (hub-builder helper).

  python hub_modrinth.py info <slug> [<slug> ...]
  python hub_modrinth.py get  <slug> --dest <scratch dir> [--mc 1.21.1] [--loader fabric|neoforge|forge|none]

Rules baked in (see the maintenance guide): User-Agent identifies us, at most 2 requests per second
(sleep 0.6 s between requests), stop on the first HTTP error instead of retrying in a loop, and the
download goes ONLY to the scratch folder you pass in --dest (never into the repository).
`--loader none` is for shader packs (no loader filter).  The newest *release* is preferred; a beta/alpha
is used only when no release matches and is printed with a warning.
"""
import argparse, hashlib, json, os, sys, time, urllib.parse, urllib.request

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

API = "https://api.modrinth.com/v2"
UA = "DragonMeow/NyanLex-hub-builder (github.com/DragonMeow1012/NyanLex)"
_last = [0.0]


def request(url, binary=False):
    wait = 0.6 - (time.time() - _last[0])
    if wait > 0:
        time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        _last[0] = time.time()
        data = r.read()
    return data if binary else json.loads(data.decode("utf-8"))


def project(slug):
    return request(f"{API}/project/{urllib.parse.quote(slug)}")


def versions(slug, mc, loader):
    q = {}
    if loader != "none":
        q["loaders"] = json.dumps([loader])
    if mc:
        q["game_versions"] = json.dumps([mc])
    url = f"{API}/project/{urllib.parse.quote(slug)}/version"
    if q:
        url += "?" + urllib.parse.urlencode(q)
    return request(url)


def pick(vs):
    for v in vs:  # the API returns newest first
        if v.get("version_type") == "release":
            return v, True
    return (vs[0], False) if vs else (None, False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["info", "get"])
    ap.add_argument("slugs", nargs="+")
    ap.add_argument("--dest")
    ap.add_argument("--mc", default="1.21.1")
    ap.add_argument("--loader", default="fabric")
    a = ap.parse_args()
    for slug in a.slugs:
        p = project(slug)
        lic = p.get("license") or {}
        print(f"{slug}: title={p.get('title')!r} project_type={p.get('project_type')} "
              f"license.id={lic.get('id')!r} license.name={lic.get('name')!r} license.url={lic.get('url')!r}")
        vs = versions(slug, a.mc, a.loader)
        v, is_release = pick(vs)
        if v is None:
            print(f"  no version for mc={a.mc} loader={a.loader} (try another loader/MC, or skip and note it in the report)")
            continue
        f = next((x for x in v["files"] if x.get("primary")), v["files"][0])
        print(f"  version={v['version_number']} type={v['version_type']}{'' if is_release else '  (NOT a release: warning)'} "
              f"mc={v['game_versions'][:4]} loaders={v['loaders']} file={f['filename']} ({f['size']} bytes)")
        if a.cmd == "get":
            if not a.dest:
                sys.exit("--dest is required for get")
            folder = os.path.join(a.dest, slug)
            os.makedirs(folder, exist_ok=True)
            data = request(f["url"], binary=True)
            want = f["hashes"].get("sha512")
            if want and hashlib.sha512(data).hexdigest() != want:
                sys.exit(f"sha512 mismatch for {f['filename']}")
            path = os.path.join(folder, f["filename"])
            open(path, "wb").write(data)
            print(f"  saved {path} (sha512 verified)")


if __name__ == "__main__":
    main()
```

### A.7 `hub_extract_lang.py`

```python
#!/usr/bin/env python3
"""Step 3 helper: read a mod jar / shader-pack zip and write the "to translate" skeleton.

  python hub_extract_lang.py <file.jar|file.zip> --slug <modrinth slug> --license "<Modrinth licence id>"
         --version <version> --mc <mc version> [--kind mod|shaderpack] [--pack-name "<name>"]
         [--loader fabric] --out <scratch dir>

What it does (and the translation rule it enforces: ONLY translate what the mod itself lacks):
  * mod id: fabric.mod.json "id", else META-INF/neoforge.mods.toml / mods.toml modId, else mcmod.info modid
  * reads every assets/<ns>/lang/en_us.{json,lang} and zh_tw.{json,lang} (file names are matched
    case-insensitively and '-' == '_': en_US.lang, zh-TW.json ...); a jar may hold several namespaces
  * shader pack: shaders/lang/en_us.lang and zh_tw.lang (key=value, '#' starts a comment)
  * a key is "missing" when zh_tw lacks it, or when the zh_tw value is just the English text again
    (an English placeholder: marked "existing":"placeholder")
  * existingZhTw: none (no zh_tw keys) | partial (some English keys missing) | full (nothing missing -> no file)
  * keys starting with "_comment" / "//" and empty English values are dropped
Output: <out>/<modId or slug>.json in the translation-team intermediate format, with "zh_tw":"" left for the
translators.  Nothing is ever written into the repository.
"""
import argparse, json, os, re, sys, zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

LANG_NAME = re.compile(r"^(en_us|zh_tw)\.(json|lang)$")


def canon(name):
    return name.lower().replace("-", "_")


def parse_lang(raw, ext):
    text = raw.decode("utf-8-sig", "replace")
    if ext == "json":
        data = json.loads(text)
        return {k: v for k, v in data.items() if isinstance(v, str)}
    out = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.replace("\\n", "\n")
    return out


def mod_id(z):
    names = set(z.namelist())
    if "fabric.mod.json" in names:
        return json.loads(z.read("fabric.mod.json").decode("utf-8-sig")).get("id")
    for toml in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
        if toml in names:
            m = re.search(r'modId\s*=\s*"([^"]+)"', z.read(toml).decode("utf-8", "replace"))
            if m:
                return m.group(1)
    if "mcmod.info" in names:
        try:
            info = json.loads(z.read("mcmod.info").decode("utf-8-sig"))
            info = info.get("modList", info) if isinstance(info, dict) else info
            return info[0].get("modid")
        except Exception:
            pass
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("archive")
    ap.add_argument("--slug", required=True)
    ap.add_argument("--license", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--mc", required=True)
    ap.add_argument("--kind", default="mod", choices=["mod", "shaderpack"])
    ap.add_argument("--pack-name")
    ap.add_argument("--loader", default="fabric")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    z = zipfile.ZipFile(a.archive)
    en, zh = {}, {}  # (ns, key) -> value
    for name in z.namelist():
        parts = name.split("/")
        if a.kind == "mod" and len(parts) == 4 and parts[0] == "assets" and parts[2] == "lang":
            ns, fname = parts[1], parts[3]
        elif a.kind == "shaderpack" and len(parts) == 3 and parts[0] == "shaders" and parts[1] == "lang":
            ns, fname = "shader", parts[2]
        else:
            continue
        m = LANG_NAME.match(canon(fname))
        if not m:
            continue
        target = en if m.group(1) == "en_us" else zh
        for k, v in parse_lang(z.read(name), m.group(2)).items():
            target[(ns, k)] = v

    ident = None if a.kind == "shaderpack" else mod_id(z)
    if a.kind == "mod" and not ident:
        sys.exit("could not read the mod id (fabric.mod.json / mods.toml / mcmod.info)")
    entries, total, have = [], 0, 0
    for (ns, k), v in en.items():
        if k.startswith("_comment") or k.startswith("//") or not v.strip():
            continue
        total += 1
        z_val = zh.get((ns, k))
        if z_val is None:
            entries.append({"ns": ns, "key": k, "en": v, "zh_tw": ""})
        elif z_val == v and re.search(r"[A-Za-z]{3}", v):
            entries.append({"ns": ns, "key": k, "en": v, "zh_tw": "", "existing": "placeholder"})
        else:
            have += 1
    state = "none" if not zh else ("full" if not entries else "partial")
    print(f"id={ident or '-'} english keys={total} already translated={have} to translate={len(entries)} existingZhTw={state}")
    if not entries:
        print("nothing to translate: record it in the REPORT (existingZhTw=full / no lang file) and skip")
        return
    out = {"kind": a.kind, "modId": ident, "slug": a.slug, "version": a.version, "mcVersion": a.mc,
           "loader": a.loader, "license": a.license, "existingZhTw": state, "entries": entries}
    if a.kind == "shaderpack":
        out["packName"] = a.pack_name or a.slug
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, (ident or a.slug) + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("wrote", path)


if __name__ == "__main__":
    main()
```

### A.8 `hub_annotations.py`

```python
#!/usr/bin/env python3
"""Extract player-visible strings from annotations when a mod ships NO lang file.

Some mods write their settings screen text into annotations, e.g.
    @SomeConfigOption(name = "Label", desc = "Longer description")
    public boolean someField;
Those strings sit in the class file (RuntimeVisibleAnnotations).  This reads them straight from the
jar with a small class-file parser (no javap, nothing is executed, no code is copied).

  python hub_annotations.py <mod.jar> --prefix <package/path/of/the/config/classes/> \
         [--annotations <SimpleName1>,<SimpleName2>] [--elements name,desc] [--out entries.json]

Run it first WITHOUT --annotations: it only prints (to stderr) every annotation type seen under the prefix with
its count, so you can pick the ones that carry player-visible text.

Output: a JSON list of {"ns","key","field","role","en"}; key = "class:<class name relative to the
prefix, dots>#<running index inside that class>", role = the annotation element the text came from.
Feed it to the translators as the `en` side; the final input file gets "zh_tw" added per entry.
Only text that is shown to the player belongs here: leave anything that merely looks like code,
command syntax, internal ids or debug switches out when you review the list.
Limits: only string-valued elements of the annotations you name are read (enum constants, dropdown
display names and strings that live in code constants are NOT captured); the jar's modified UTF-8 is
decoded as plain UTF-8, so a character outside the BMP (an emoji) may come out garbled: check such lines by hand.
"""
import argparse, json, struct, sys, zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def parse(b):
    """Return (fields, class_annotations); a field is (name, descriptor, [annotation...]) and an
    annotation is (type descriptor, {element: string | list of strings | None})."""
    if b[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    p = 8
    n = struct.unpack(">H", b[p:p + 2])[0]
    p += 2
    cp = [None] * n
    i = 1
    while i < n:
        tag = b[p]
        p += 1
        if tag == 1:
            ln = struct.unpack(">H", b[p:p + 2])[0]
            p += 2
            cp[i] = b[p:p + ln].decode("utf-8", "replace")  # modified UTF-8; fine for plain text
            p += ln
        elif tag in (3, 4):
            p += 4
        elif tag in (5, 6):
            p += 8
            i += 1  # long/double take two slots
        elif tag in (7, 8, 16, 19, 20):
            p += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            p += 4
        elif tag == 15:
            p += 3
        else:
            raise ValueError(f"unknown constant tag {tag}")
        i += 1
    utf = lambda idx: cp[idx]
    p += 6  # access_flags, this_class, super_class
    p += 2 + 2 * struct.unpack(">H", b[p:p + 2])[0]  # interfaces

    def attributes(p):
        out = {}
        count = struct.unpack(">H", b[p:p + 2])[0]
        p += 2
        for _ in range(count):
            name_idx, length = struct.unpack(">HI", b[p:p + 6])
            p += 6
            out.setdefault(utf(name_idx), []).append(b[p:p + length])
            p += length
        return out, p

    def element_value(d, q):
        tag = chr(d[q])
        q += 1
        if tag == "s":
            return utf(struct.unpack(">H", d[q:q + 2])[0]), q + 2
        if tag in "BCDFIJSZ":
            return None, q + 2
        if tag == "e":
            return None, q + 4
        if tag == "c":
            return None, q + 2
        if tag == "@":
            _, _, q = annotation(d, q)
            return None, q
        if tag == "[":
            count = struct.unpack(">H", d[q:q + 2])[0]
            q += 2
            items = []
            for _ in range(count):
                v, q = element_value(d, q)
                items.append(v)
            return items, q
        raise ValueError(f"unknown element tag {tag!r}")

    def annotation(d, q):
        type_desc = utf(struct.unpack(">H", d[q:q + 2])[0])
        count = struct.unpack(">H", d[q + 2:q + 4])[0]
        q += 4
        values = {}
        for _ in range(count):
            name = utf(struct.unpack(">H", d[q:q + 2])[0])
            v, q = element_value(d, q + 2)
            values[name] = v
        return type_desc, values, q

    def annotations(attrs):
        found = []
        for blob in attrs.get("RuntimeVisibleAnnotations", []):
            count = struct.unpack(">H", blob[:2])[0]
            q = 2
            for _ in range(count):
                t, v, q = annotation(blob, q)
                found.append((t, v))
        return found

    fields = []
    field_count = struct.unpack(">H", b[p:p + 2])[0]
    p += 2
    for _ in range(field_count):
        _access, name_idx, desc_idx = struct.unpack(">HHH", b[p:p + 6])
        p += 6
        attrs, p = attributes(p)
        fields.append((utf(name_idx), utf(desc_idx), annotations(attrs)))
    method_count = struct.unpack(">H", b[p:p + 2])[0]
    p += 2
    for _ in range(method_count):  # methods are not needed: skip their attributes
        p += 6
        _, p = attributes(p)
    class_attrs, p = attributes(p)
    return fields, annotations(class_attrs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jar")
    ap.add_argument("--prefix", required=True, help="class path prefix inside the jar, ending with '/'")
    ap.add_argument("--annotations", default="", help="comma separated annotation simple names (empty: only list the types)")
    ap.add_argument("--elements", default="name,desc")
    ap.add_argument("--ns", default="config")
    ap.add_argument("--out")
    a = ap.parse_args()
    wanted = {x.strip() for x in a.annotations.split(",") if x.strip()}
    roles = [x.strip() for x in a.elements.split(",") if x.strip()]
    entries, counters, seen_types = [], {}, {}
    with zipfile.ZipFile(a.jar) as z:
        for name in sorted(z.namelist()):
            if not name.startswith(a.prefix) or not name.endswith(".class"):
                continue
            try:
                fields, class_anns = parse(z.read(name))
            except Exception as e:  # keep going; report at the end
                print("skip", name, e, file=sys.stderr)
                continue
            cls = name[len(a.prefix):-6].replace("/", ".")
            for field, _desc, anns in [("<class>", "", class_anns)] + fields:
                for type_desc, values in anns:
                    simple = type_desc.rstrip(";").split("/")[-1].split("$")[-1]
                    seen_types[simple] = seen_types.get(simple, 0) + 1
                    if simple not in wanted:
                        continue
                    for role in roles:
                        text = values.get(role)
                        if not isinstance(text, str) or not text:
                            continue
                        idx = counters.get(cls, 0)
                        counters[cls] = idx + 1
                        entries.append({"ns": a.ns, "key": f"class:{cls}#{idx}", "field": field,
                                        "role": role, "en": text})
    print(f"{len(entries)} entries; annotation types seen under the prefix: {seen_types}", file=sys.stderr)
    text = json.dumps(entries, ensure_ascii=False, indent=1)
    if a.out:
        open(a.out, "w", encoding="utf-8").write(text)
    else:
        print(text)


if __name__ == "__main__":
    main()
```

### A.9 `hub_selfcheck.py`

```python
#!/usr/bin/env python3
"""Step 6 self-check for translation-team intermediate JSON files (every entry: en vs zh_tw).

  python hub_selfcheck.py <file.json | dir> [...]       exit 1 when any problem is found

Checks per entry (all must pass before the file goes to the converter):
  * zh_tw is not empty and differs from en unless it is a deliberate proper noun (reported as INFO "same as en")
  * format tokens  %s %d %f %1$s %.1f %%  : same tokens; unnumbered ones in the SAME order
                   (to reorder in Chinese use numbered %1$s %2$s)
  * {placeholders}  and  &&  : same multiset
  * section-sign colour codes (§ + one char): same sequence
  * newline count and leading / trailing whitespace identical
  * no emoji, no URL that the English text does not have
  * duplicate (ns, key) inside one file
"""
import json, os, re, sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

FMT = re.compile(r"%(?:(\d+)\$)?([\d.]*)([dfsS%])")
BRACE = re.compile(r"\{[A-Za-z0-9_]+\}|&&")
EMOJI = re.compile("[\U0001F300-\U0001FAFF\u2600-\u27BF\uFE0F]")
URL = re.compile(r"https?://|www\.", re.I)


def tokens(s):
    numbered = sorted(m.group(0) for m in FMT.finditer(s) if m.group(1))
    plain = [m.group(0) for m in FMT.finditer(s) if not m.group(1)]
    return numbered, plain


def check(path):
    problems, infos = [], []
    obj = json.load(open(path, encoding="utf-8"))
    seen = set()
    for e in obj.get("entries", []):
        k = (e.get("ns"), e.get("key"))
        en, zh = e.get("en") or "", e.get("zh_tw") or ""
        tag = f"{os.path.basename(path)}:{e.get('key')}"
        if k in seen:
            problems.append(f"{tag}: duplicate key")
        seen.add(k)
        if not zh.strip():
            problems.append(f"{tag}: empty zh_tw")
            continue
        if zh == en:
            infos.append(f"{tag}: same as en (ok only for a proper noun)")
        if tokens(en) != tokens(zh):
            problems.append(f"{tag}: format tokens differ {tokens(en)} vs {tokens(zh)}")
        if sorted(BRACE.findall(en)) != sorted(BRACE.findall(zh)):
            problems.append(f"{tag}: {{placeholder}}/&& differ")
        if re.findall(r"§.", en) != re.findall(r"§.", zh):
            problems.append(f"{tag}: colour code sequence differs")
        if en.count("\n") != zh.count("\n"):
            problems.append(f"{tag}: newline count {en.count(chr(10))} vs {zh.count(chr(10))}")
        if (en[:1].isspace(), en[-1:].isspace()) != (zh[:1].isspace(), zh[-1:].isspace()) or \
                en[:len(en) - len(en.lstrip())] != zh[:len(zh) - len(zh.lstrip())] or \
                en[len(en.rstrip()):] != zh[len(zh.rstrip()):]:
            problems.append(f"{tag}: leading/trailing whitespace differs")
        if EMOJI.search(zh) and not EMOJI.search(en):
            problems.append(f"{tag}: emoji in zh_tw")
        if URL.search(zh) and not URL.search(en):
            problems.append(f"{tag}: URL only in zh_tw")
    return len(obj.get("entries", [])), problems, infos


def main():
    files = []
    for arg in sys.argv[1:]:
        if os.path.isdir(arg):
            for r, _, ns in os.walk(arg):
                files += [os.path.join(r, n) for n in sorted(ns) if n.endswith(".json")]
        else:
            files.append(arg)
    bad = 0
    for f in files:
        try:
            n, problems, infos = check(f)
        except Exception as ex:
            print(f"{f}: not an intermediate file ({ex})")
            continue
        print(f"{f}: {n} entries, {len(problems)} problems, {len(infos)} info")
        for p in problems[:30]:
            print("   PROBLEM", p)
        bad += len(problems)
    print("RESULT", "PASS" if not bad else f"FAIL ({bad})")
    sys.exit(1 if bad else 0)


main()
```

### A.10 `hub_vanilla_glossary.py`

```python
#!/usr/bin/env python3
"""Build / query the vanilla zh_tw glossary from the game's own language files (offline).

Both sources are in the Fabric Loom cache that Gradle already filled:
  zh_tw : %USERPROFILE%/.gradle/caches/fabric-loom/assets/indexes/<mc>-<n>.json -> "minecraft/lang/zh_tw.json" hash
          -> assets/objects/<first 2 hex>/<hash>
  en_us : NOT in the asset index (it ships inside the game jar):
          %USERPROFILE%/.gradle/caches/fabric-loom/<mc>/minecraft-client.jar -> assets/minecraft/lang/en_us.json

  python hub_vanilla_glossary.py --mc 1.21.1 --out vanilla.json          # dump {key: {en, zh_tw}}
  python hub_vanilla_glossary.py --mc 1.21.1 find "render distance" chunk mipmap    # who uses these words?
"""
import argparse, glob, json, os, sys, zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def load(mc, lang):
    root = os.path.join(os.path.expanduser("~"), ".gradle", "caches", "fabric-loom")
    if lang == "en_us":
        jar = os.path.join(root, mc, "minecraft-client.jar")
        if not os.path.isfile(jar):
            sys.exit(f"{jar} not found (run a Loom build for {mc} once)")
        with zipfile.ZipFile(jar) as z:
            return json.loads(z.read("assets/minecraft/lang/en_us.json").decode("utf-8"))
    base = os.path.join(root, "assets")
    idx = sorted(glob.glob(os.path.join(base, "indexes", mc + "-*.json")))
    if not idx:
        sys.exit(f"no asset index for {mc} under {base}/indexes (run a Loom build for that version once)")
    objects = json.load(open(idx[-1], encoding="utf-8"))["objects"]
    h = objects[f"minecraft/lang/{lang}.json"]["hash"]
    return json.load(open(os.path.join(base, "objects", h[:2], h), encoding="utf-8"))


ap = argparse.ArgumentParser()
ap.add_argument("--mc", default="1.21.1")
ap.add_argument("--out")
ap.add_argument("find", nargs="*", help="'find' followed by lower-case English words to look up")
a = ap.parse_args()
en, zh = load(a.mc, "en_us"), load(a.mc, "zh_tw")
pairs = {k: {"en": en[k], "zh_tw": zh.get(k)} for k in en}
if a.out:
    json.dump(pairs, open(a.out, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(len(pairs), "keys written to", a.out)
if a.find and a.find[0] == "find":
    for word in a.find[1:]:
        hits = [(k, v) for k, v in pairs.items() if word.lower() in v["en"].lower() and v["en"].lower().count(" ") <= 3]
        print(f"== {word}: {len(hits)} short vanilla strings contain it")
        for k, v in hits[:12]:
            print(f"   {v['en']!r} -> {v['zh_tw']!r}   [{k}]")
```

### A.11 `hub_localcache.py`

```python
#!/usr/bin/env python3
"""Offline stand-in for "detect + download": turn repository files into the player's LOCAL hub cache.

The game keeps downloaded rows in <config dir>/nyanlex-hub-cache-<lang>.json:
  {"schema":2,"language":"zh-tw","rows":{"<64 hex>":{"v":"<translation>","s":"mod:<modId>"}, ...}}
(first source wins for a repeated hash; the real downloader merges server, then modpack, then mod files).
Use it in a THROWAWAY dev run folder to test lookups with every translation request switched off.

  python hub_localcache.py --hub <translation-hub dir> --out <run/config> [--lang zh-tw] mod:<modId> [mod:<modId> | server:<host> ...]
"""
import argparse, json, os, sys

ap = argparse.ArgumentParser()
ap.add_argument("--hub", required=True)
ap.add_argument("--out", required=True)
ap.add_argument("--lang", default="zh-tw")
ap.add_argument("sources", nargs="+")
a = ap.parse_args()
folder = {"server": "servers", "modpack": "modpacks", "mod": "mods"}
rows = {}
for src in a.sources:
    kind, _, ident = src.partition(":")
    path = os.path.join(a.hub, folder[kind], ident, a.lang + ".json")
    data = json.load(open(path, encoding="utf-8"))
    assert data["schema"] == 2 and data["language"] == a.lang, path
    for h, v in data["entries"].items():
        rows.setdefault(h, {"v": v, "s": src})
os.makedirs(a.out, exist_ok=True)
target = os.path.join(a.out, f"nyanlex-hub-cache-{a.lang}.json")
with open(target, "w", encoding="utf-8", newline="\n") as f:
    json.dump({"schema": 2, "language": a.lang, "rows": rows}, f, ensure_ascii=False)
print(f"{len(rows)} rows -> {target}")
```

---

## 附錄 B：指令驗證狀態總表

環境：`hubdoc` 拋棄式 worktree，分支 `release/nyanlex-1.0.0` @ `64cec54`（先在 `b01ebac` 驗證，分支前進後在 `64cec54` 重跑重產與比對，結果相同）；JDK 21、Gradle 8.10、`--offline`。所有測試只用假資料或唯讀資料，沒有使用真實使用者快取，沒有送出任何翻譯請求，沒有寫回主 repo。

| 指令／流程 | 狀態 | 說明 |
|---|---|---|
| `gradle tasks --group "translation hub"` | 已實測 | 列出 `hubExport`、`langPackBuild` |
| `gradle hubExport …`（`-PcacheDir -Plang -Pserver -Pout -PmergeIndex`） | 已實測 | 假快取 8 列：exported 3、chat 2、驗證 1、網址 1、第三方 1；輸出到暫存副本，伺服器檔位元組不變 |
| `hubExport -PincludeChat` | 已實測 | exported 5、`droppedChat=0`，私訊列連同未遮罩的名字被寫出 |
| `hubExport -Pmod / -Pmodpack / -PmaxRows` | 已實測 | `mod:<id>`、`modpack:example-pack`、`maxRows=2` → `truncated=1` |
| `hubExport` 缺來源／`-Pserver=localhost` | 已實測 | exit 2，訊息如 §9.5 |
| `gradle langPackBuild …`（`-Pin;` 多路徑、`-Pout -PshaderTarget -PmergeIndex -Preport -Pskips`） | 已實測 | 假資料：ARR 被 REFUSE、光影併入 `-PshaderTarget`、`X: ` 列、`%s` 被略過、`UNCHANGED` |
| `langPackBuild -Plang` | 依程式碼 | 預設 `zh-TW` 即實測用的值；沒有另外實測其他語言 |
| 真實輸入完整重產 | 已實測 | 簡單授權規則下 `TOTAL rows=561 files=8`；8 個檔與 git blob **全部位元組相同**，其餘 9 個來源被 REFUSE。（規則改前：`TOTAL rows=9160 files=17`、17 個檔相同，兩次：`b01ebac`、`64cec54`） |
| 重產冪等性 | 已實測 | 連跑兩次，資料檔相同，index 只有 `updatedAt` 不同 |
| `gradle test --tests "com.dragonmeow.nyanlex.hub.*"` | 已實測 | 17 個類別、184 項、0 失敗 |
| 附錄 A.1 `hub_check.py`（含 `--compare-dir`、`--originals`、`--raw`） | 已實測 | 對倉庫與線上 `origin/main` 皆 `RESULT PASS` |
| 附錄 A.2 `hub_subset.py` | 已實測 | 對 git 歷史兩版比對（見 §3.4） |
| 附錄 A.3 `hub_apply.py` | 已實測 | 新增一個來源／全部 SAME 兩種情境；index 寫回後其他條目位元組不變 |
| 附錄 A.4 `hub_hashname.py` | 已實測 | 校驗碼驗證通過；Python 與 Java `hashName` 對照相同 |
| 附錄 A.5 `hub_remove.py` | 已實測 | 刪除後 index 位元組回到原狀 |
| 附錄 A.6 `hub_modrinth.py info / get` | 已實測 | 各查 1 個 mod 與 1 個光影包、下載 1 個 jar（sha512 驗證）；總共約 7 個 GET |
| 附錄 A.7 `hub_extract_lang.py` | 已實測 | 對一個 jar（部分 zh_tw）與一個光影 zip（`en_US.lang`） |
| 附錄 A.8 `hub_annotations.py` | 已實測 | 對真實 jar：與翻譯組當初抽出的結果逐條相同 |
| 附錄 A.9 `hub_selfcheck.py` | 已實測 | 真實輸出 0 問題；故意做壞的檔 5 個問題全抓到 |
| 附錄 A.10 `hub_vanilla_glossary.py` | 已實測 | 與 §4.4.1 表一致 |
| 附錄 A.11 `hub_localcache.py` | 已實測 | 用 `HubLocalCache` 讀回，列數相符 |
| `curl` raw 與 `git show origin/main:…` 的 sha256 | 已實測 | index 與一個資料檔皆相同 |
| `git worktree add --detach … / remove` | 已實測 | 見 §0.3 |
| `gradle runClient`（遊戲內驗證） | 依程式碼 | task 存在已確認；遊戲內驗證結果出自 `INFRA-REPORT`（當時的 `hubverify` worktree） |
| `git commit` / `git push` 流程 | 依程式碼 | 不在驗證範圍內執行 |
| PowerShell 版的 `-Pin="a;b"` 寫法 | 依程式碼 | 只驗證了 bash 寫法 |
