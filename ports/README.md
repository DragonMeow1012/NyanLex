# Minecraft 版本移植

這裡補齊 44 個 Minecraft／Loader 建置組合：Fabric 25 個、NeoForge 19 個。既有 18 個組合仍由原本專案維護，完整矩陣共 62 個 JAR；各遊戲版本獨立編譯，不以放寬相容版本標籤代替移植。

目標清單只是建置宣告，不代表全部已驗證或已發布。發布時必須使用本次產生的驗證報告，不能只看 `targets.json`。

## 目標範圍

完整矩陣中的 Fabric 涵蓋以下正式版本：

- 1.16.5；1.17、1.17.1；1.18、1.18.1、1.18.2。
- 1.19、1.19.1、1.19.2、1.19.3、1.19.4。
- 1.20、1.20.1、1.20.2、1.20.3、1.20.4、1.20.5、1.20.6。
- 1.21、1.21.1、1.21.2、1.21.3、1.21.4、1.21.5、1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11。
- 26.1、26.1.1、26.1.2、26.2、26.3。

NeoForge 清單從 1.20.1 起，包含其後上述每一個版本；不替早期 Minecraft 宣告不存在的 NeoForge 組合。既有 Fabric 1.14.4／1.15.2 與 Forge 1.12.2／1.13.2 另行保留。

新增目標與依賴版本由三份清單管理：

| 建置專案 | 新增數量 | 範圍 |
| --- | ---: | --- |
| [fabric-legacy](fabric-legacy/targets.json) | 11 | 1.17、1.18、1.18.1、1.19、1.19.1、1.19.2、1.19.3、1.20、1.20.2、1.20.3、1.20.4 |
| [fabric-modern](fabric-modern/targets.json) | 14 | 1.20.5、1.20.6、1.21、1.21.2–1.21.10、26.1、26.1.1 |
| [neoforge](neoforge/targets.json) | 19 | 1.20.2–1.20.6、1.21、1.21.2–1.21.11、26.1、26.1.1、26.1.2 |

[完整矩陣檢查器](../verification/release_matrix.py) 會檢查缺少或重複的組合；它也統一提供 JAR 路徑給建置、驗證及發布流程。

## 共用原始碼

每個目標的 `sourceProject` 指向既有維護中的專案，直接共用翻譯核心與資源。`profile` 與平台轉接僅處理 Minecraft／Loader 的 API 差異，例如聊天簽章、語言清單、滑鼠捲動、畫面繪製及物品資料格式。

Fabric 的轉接規則會在建置目錄產生受影響的平台類別；規則找不到原始片段時會直接失敗，避免默默套用過時修改。NeoForge 依 API 世代共用平台 profile。不要為每個小版本複製整份翻譯核心，也不要靠停用 hook 讓編譯過關。

每個目標使用獨立的 `build/<Minecraft>/` 輸出與 `.gradle/targets/<Minecraft>/` 工作紀錄。工作紀錄必須由命令列的 `--project-cache-dir` 指定；在 `settings.gradle` 才修改此設定，無法可靠隔離 Gradle 已選定的紀錄。

## 建置

從專案根目錄使用唯一的新增版本建置入口：

```powershell
# 只列出 44 個目標及使用的工具，不修改檔案或執行建置。
.\verification\build-ports.ps1 -DryRun

# 建置指定組合；可傳入多個目標。
.\verification\build-ports.ps1 -Targets 'fabric/1.17','neoforge/1.20.4'

# 依序乾淨建置並驗證全部 44 個新增組合。
.\verification\build-ports.ps1
```

| Profile | 執行 Gradle 的 JDK | Gradle |
| --- | ---: | --- |
| Fabric legacy | 21 | 8.10 |
| Fabric modern | 25 | 9.5.0 |
| NeoForge 1.20.x／1.21.x | 21 | 8.13 |
| NeoForge 26.x | 25 | 9.5.0 |

執行建置的 JDK 不等於 JAR 的最低 Java 版本。`targets.json` 的 `java` 指定輸出位元碼／遊戲需求，`toolchain`（若有）指定編譯工具；Fabric modern 的 Java 21 目標仍使用 Java 21 toolchain。NeoForge 1.20.2、1.20.3、1.20.5 使用 NeoGradle，其餘目標使用 ModDev，選擇由 NeoForge 建置設定集中管理。

腳本使用現有 `.gradle-local/gradle-<版本>/bin/gradle.bat`，並尋找符合主版本的 `JAVA_HOME`、`Program Files/Java/jdk-<版本>` 或 `.jdks/jdk-<版本>`。執行期間的 Java 環境調整只作用於目前程序，結束或失敗時都會還原；不修改系統設定、不停止其他 Gradle daemon。若在 Git worktree 執行，工具與下載快取取自共用 Git 儲存庫所在目錄。

產物位於 `ports/<專案>/build/<Minecraft>/libs/`，紀錄位於同專案的 `build/logs/<Minecraft>.log`。遇到失敗會停止，不會發布任何檔案。既有 18 個組合繼續使用 [verify-release-matrix.ps1](../verification/verify-release-matrix.ps1)，不要把它的 18 組結果當成全部 62 組結果。

## 驗證與發布界線

成功建置後，入口會呼叫 [verify-port-artifacts.py](../verification/verify-port-artifacts.py)，檢查 JAR 完整性、精確 Minecraft／Loader 依賴、Java 位元碼、四種介面語系、主要功能結構及 Mixin 規則。Mixin 目標與呼叫位置會對照每個版本真正的映射後 Minecraft classpath，不只是檢查 Java 能否編譯。

完整新增矩陣的結果寫入 `build/port-artifacts.json`；指定部分目標時寫入 `build/selected-port-artifacts.json`。各目標另有 `build/<Minecraft>/port-verification.json` 提供本次 classpath 與編譯輸出位置。

這些是建置與靜態驗證，不等於實際進入遊戲測試。報告中的 `runtimeTested: false` 必須保留，直到另有實際啟動與功能測試證據。此入口不推送 Git、不建立 Release、不上傳 Modrinth；發布流程另行核對成功報告與 JAR 雜湊。
