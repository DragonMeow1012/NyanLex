# 打包流程

## Fabric 26.2

`fabric26` 需要 Fabric Loom 1.17.x，目前解析到的 plugin 需要 Gradle plugin API `9.5.0`，所以不要用根目錄 `gradle/wrapper/gradle-wrapper.properties` 裡的 Gradle 8.10 來打包 26.2。

### 環境

- JDK 25: `C:\Program Files\Java\jdk-25`
- Gradle 9.5.0: 建議放在專案根目錄的 `.gradle-local\gradle-9.5.0`

### 第一次準備 Gradle 9.5.0

在專案根目錄執行：

```powershell
$distDir = Join-Path $PWD ".gradle-local"
$version = "9.5.0"
$zip = Join-Path $distDir "gradle-$version-bin.zip"
New-Item -ItemType Directory -Force -Path $distDir | Out-Null
curl.exe -L --fail --retry 3 --output $zip "https://services.gradle.org/distributions/gradle-$version-bin.zip"
tar -xf $zip -C $distDir
```

### 打包指令

在專案根目錄執行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-25"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location .\fabric26
..\.gradle-local\gradle-9.5.0\bin\gradle.bat clean build --no-daemon
Set-Location ..
Copy-Item -Force .\fabric26\build\libs\mctranslator-1.0.0-Fabric-26.2.jar .\mods-jar\fabric\mctranslator-1.0.0-Fabric-26.2.jar
```

### 輸出位置

- 建置輸出：`fabric26\build\libs\mctranslator-1.0.0-Fabric-26.2.jar`
- 發佈用複本：`mods-jar\fabric\mctranslator-1.0.0-Fabric-26.2.jar`

### 驗證

```powershell
& "C:\Program Files\Java\jdk-25\bin\jar.exe" tf .\mods-jar\fabric\mctranslator-1.0.0-Fabric-26.2.jar |
  Select-String -Pattern "fabric.mod.json|MctranslatorFabric26|TranslationCache|TextFilter|Fabric26TextStyle"
```

成功時應看到 `fabric.mod.json`、`MctranslatorFabric26.class`、`TranslationCache.class`、`TextFilter.class`、`Fabric26TextStyle.class` 等項目。
