# Minecraft Translator 1.0.3 packaging

The release contains 16 JARs. Each JAR is tied to one Minecraft version and loader.

## Project matrix

| Loader | Minecraft | Java | Project |
| --- | --- | ---: | --- |
| Forge | 1.12.2 | 8 | `forge1122` |
| Forge | 1.13.2 | 8 | `forge1132` |
| Fabric | 1.14.4 | 8 | `fabric1144` |
| Fabric | 1.15.2 | 8 | `fabric1152` |
| Fabric | 1.16.5 | 8 | `fabric1165` |
| Fabric | 1.17.1 | 16 | `fabric1171` |
| Fabric | 1.18.2 | 17 | `fabric1182` |
| Fabric | 1.19.4 | 17 | `fabric1194` |
| Fabric | 1.20.1 | 17 | `fabric120` |
| NeoForge | 1.20.1 | 17 | `neoforge120` |
| Fabric | 1.21.1 | 21 | repository root |
| NeoForge | 1.21.1 | 21 | `neoforge` |
| Fabric | 1.21.11 | 21 | `fabric12111` |
| Fabric | 26.1.2 | 25 | `fabric2612` |
| Fabric | 26.2 | 25 | `fabric26` |
| NeoForge | 26.2 | 25 | `neoforge26` |

## Build

Use Gradle 8.10 for stable Loom projects, Gradle 8.13 for NeoForge 1.20.1/1.21.1, and Gradle 9.5 for Minecraft 1.21.11/26.x. Forge 1.12.2 and 1.13.2 use their included wrappers.

Examples:

```powershell
.\.gradle-local\gradle-8.10\bin\gradle.bat clean build
.\.gradle-local\gradle-8.10\bin\gradle.bat -p fabric120 clean build
.\.gradle-local\gradle-8.13\bin\gradle.bat -p neoforge120 clean build
.\.gradle-local\gradle-9.5.0\bin\gradle.bat -p fabric26 clean build
Push-Location forge1122; .\gradlew.bat clean build; Pop-Location
```

## Release folders

Generated binaries are ignored by Git and stored under `mods-jar/1.0.3`:

```text
mods-jar/1.0.3/
  fabric/
    1.14.4/mctranslator-1.0.3-Fabric-1.14.4.jar
    ...
    26.2/mctranslator-1.0.3-Fabric-26.2.jar
  neoforge/
    1.20.1/mctranslator-1.0.3-NeoForge-1.20.1.jar
    1.21.1/mctranslator-1.0.3-NeoForge-1.21.1.jar
    26.2/mctranslator-1.0.3-NeoForge-26.2.jar
  forge/
    1.12.2/mctranslator-1.0.3-Forge-1.12.2.jar
    1.13.2/mctranslator-1.0.3-Forge-1.13.2.jar
  MinecraftTranslator-1.0.3-Fabric.zip
  MinecraftTranslator-1.0.3-NeoForge.zip
  MinecraftTranslator-1.0.3-Forge.zip
  MinecraftTranslator-1.0.3-all-versions.zip
  SHA256SUMS.txt
```

GitHub Release assets are flat, so all 16 JARs are also uploaded individually for README direct-download links. The ZIP files preserve the loader/version directory structure.

## Verification

Before publishing:

- Build all 16 targets successfully.
- Confirm exactly 16 packaged JARs, 4 ZIPs, and `SHA256SUMS.txt`.
- Compare each packaged JAR SHA-256 with its matching `build/libs` output.
- Confirm loader metadata contains version 1.0.3 and the exact Minecraft range.
- Run `git diff --check` and core unit tests.
- Upload individual JARs plus the four ZIP files to tag `v1.0.3`.
