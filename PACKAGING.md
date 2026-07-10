# 1.0.2 建置與打包

專案包含六個獨立 loader／Minecraft 版本。任何核心修改後，先同步共用碼：

```powershell
powershell -ExecutionPolicy Bypass -File .\sync-core.ps1
```

## Java 21：Minecraft 1.20.1／1.21.1

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\.gradle-local\gradle-8.10\bin\gradle.bat clean build --no-daemon
.\.gradle-local\gradle-8.10\bin\gradle.bat -p .\fabric120 clean build --no-daemon
.\.gradle-local\gradle-8.10\bin\gradle.bat -p .\neoforge clean build --no-daemon
.\.gradle-local\gradle-8.13\bin\gradle.bat -p .\neoforge120 clean build --no-daemon
```

## Java 25：Minecraft 26.2

Fabric 26.2／NeoForge 26.2 使用 Gradle 9.5.0：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\.gradle-local\gradle-9.5.0\bin\gradle.bat -p .\fabric26 clean build --no-daemon
.\.gradle-local\gradle-9.5.0\bin\gradle.bat -p .\neoforge26 clean build --no-daemon
```

## 發佈目錄

六個正式 jar 必須放在：

```text
mods-jar/1.0.2/fabric/
  mctranslator-1.0.2-Fabric-1.20.1.jar
  mctranslator-1.0.2-Fabric-1.21.1.jar
  mctranslator-1.0.2-Fabric-26.2.jar
mods-jar/1.0.2/neoforge/
  mctranslator-1.0.2-NeoForge-1.20.1.jar
  mctranslator-1.0.2-NeoForge-1.21.1.jar
  mctranslator-1.0.2-NeoForge-26.2.jar
```

Fabric 26.2 的本機安裝位置：

```text
%APPDATA%\.minecraft\mods\mctranslator-1.0.2-Fabric-26.2.jar
```

打包後應比對 SHA-256，並確認 jar 內含 loader metadata、
`TranslationCache.class` 與 `TranslationTemplate.class`。遊戲執行期間可以覆蓋 jar，
但新版本只會在完整重啟 Minecraft 後載入。
