# Minecraft Translator 1.0.5 packaging

The release contains 18 JARs. Each JAR is tied to one Minecraft version and loader.

Minecraft 26.3 is included in the main release (18 total). After rebuilding the current
screen retranslation and translation-sharing changes, run
`python verification/verify-translation-features.py` before packaging. This checks
the feature entry points, native file picker, language labels, legacy Java 8 class
versions, and Forge font hooks in each built JAR.

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
| Fabric | 26.3 | 25 | `fabric263` |
| NeoForge | 26.3 | 25 | `neoforge263` |

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

Generated binaries are ignored by Git and stored under `mods-jar/1.0.5`:

```text
mods-jar/1.0.5/
  fabric/
    1.14.4/mctranslator-1.0.5-Fabric-1.14.4.jar
    ...
    26.2/mctranslator-1.0.5-Fabric-26.2.jar
  neoforge/
    1.20.1/mctranslator-1.0.5-NeoForge-1.20.1.jar
    1.21.1/mctranslator-1.0.5-NeoForge-1.21.1.jar
    26.2/mctranslator-1.0.5-NeoForge-26.2.jar
  forge/
    1.12.2/mctranslator-1.0.5-Forge-1.12.2.jar
    1.13.2/mctranslator-1.0.5-Forge-1.13.2.jar
  MinecraftTranslator-1.0.5-Fabric.zip
  MinecraftTranslator-1.0.5-NeoForge.zip
  MinecraftTranslator-1.0.5-Forge.zip
  MinecraftTranslator-1.0.5-all-versions.zip
  SHA256SUMS.txt
```

GitHub Release assets are flat, so all 18 JARs are also uploaded individually for README direct-download links. The ZIP files preserve the loader/version directory structure.

## Verification

Before publishing:

- Build all 18 targets successfully.
- Confirm exactly 16 packaged JARs, 4 ZIPs, and `SHA256SUMS.txt`.
- Compare each packaged JAR SHA-256 with its matching `build/libs` output.
- Confirm loader metadata contains version 1.0.5 and the exact Minecraft range.
- Run `git diff --check` and core unit tests.
- Upload individual JARs plus the four ZIP files to tag `v1.0.5`.

## Minecraft 26.3 builds

Minecraft 26.3 targets are `fabric263` and `neoforge263`, both using Java 25
and Gradle 9.5. They share the 26.2 translation/loader sources and resources.
Keyboard mappings use Minecraft input constants and NeoForge's original
`KeyEvent`, so SDL and GLFW key codes are never mixed. External browser links
use the version-specific `platform26` / `platform263` source directory.

```powershell
.\.gradle-local\gradle-9.5.0\bin\gradle.bat -p fabric263 build
.\.gradle-local\gradle-9.5.0\bin\gradle.bat -p neoforge263 build
powershell -File verification/package-release.ps1
```

Both 26.3 JARs are included in the main loader ZIPs and all-versions ZIP.

Fabric requires Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3 for the
verified configuration. NeoForge was built with 26.3.0.6-beta and ModDevGradle
2.0.147; older build tooling fails while recompiling Minecraft's `HolderSet`.
The main packaging script checks metadata and entrypoint classes,
then verifies the copied JARs and ZIP contents against their build outputs.
