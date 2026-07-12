# Minecraft Translator 1.0.2 packaging

Local release JARs are collected under `mods-jar/1.0.2`, grouped by loader. This
generated directory is ignored by Git; published binaries belong on the GitHub
Release. Each JAR is pinned to the Minecraft version printed in its filename; it
is not a universal cross-version binary.

## Version matrix

| Minecraft | Loader | Java | Project |
| --- | --- | ---: | --- |
| 1.12.2 | Forge | 8 | `forge1122` |
| 1.13.2 | Forge | 8 | `forge1132` |
| 1.14.4 | Fabric | 8 | `fabric1144` |
| 1.15.2 | Fabric | 8 | `fabric1152` |
| 1.16.5 | Fabric | 8 | `fabric1165` |
| 1.17.1 | Fabric | 16 | `fabric1171` |
| 1.18.2 | Fabric | 17 | `fabric1182` |
| 1.19.4 | Fabric | 17 | `fabric1194` |
| 1.20.1 | Fabric / NeoForge | 17 | `fabric120` / `neoforge120` |
| 1.21.1 | Fabric / NeoForge | 21 | root / `neoforge` |
| 1.21.11 | Fabric | 21 | `fabric12111` |
| 26.1.2 | Fabric | 25 | `fabric2612` |
| 26.2 | Fabric / NeoForge | 25 | `fabric26` / `neoforge26` |

Java 8 compatibility ports use a reduced implementation focused on chat and item
tooltips. Fabric 1.14.4-1.16.5 also include the target-language settings list and
search. Forge 1.12.2-1.13.2 use the G hotkey and do not have the complete modern
settings screen.

## Building

Install a Gradle release compatible with the plugin used by the selected project.
The release builds were verified with Gradle 8.10 for stable Loom projects,
Gradle 8.13 for NeoForge 1.20.1/1.21.1, and Gradle 9.5 for the 1.21.11/26.x
projects. Forge 1.12.2 and 1.13.2
carry their own wrapper. Select the JDK shown in the matrix before invoking a
project; configured Java toolchains may provision the compile JDK when supported.

From PowerShell, the build shape is:

```powershell
# Root project: Fabric 1.21.1
gradle clean build

# Other Fabric and NeoForge projects
gradle -p fabric120 clean build
gradle -p neoforge120 clean build

# Legacy Forge wrappers
Push-Location forge1122
.\gradlew.bat clean build
Pop-Location
```

Replace the `-p` directory with any project shown in the matrix. The release JAR
is the remapped, non-`-dev`, non-`-sources` file under that project's `build/libs`.
Run `test` instead of `build` for unit-only verification where tests are present.

## Release layout

```text
mods-jar/1.0.2/
  forge/
  fabric/
  neoforge/
  MinecraftTranslator-1.0.2-all-versions.zip
```

Copy each final JAR into its loader directory, then create the ZIP from exactly
those 16 files. The generated package directory stays outside source control and
is uploaded as GitHub Release assets.

## Verification and publishing

Before publishing:

```powershell
$jars = Get-ChildItem mods-jar\1.0.2 -Recurse -Filter *.jar
$jars.Count                         # must be 16
$jars | Get-FileHash -Algorithm SHA256
$jars | ForEach-Object { jar tf $_.FullName | Select-String 'LICENSE_MinecraftTranslator' }
git diff --check
```

Also confirm each archive contains the correct loader metadata and exact
Minecraft dependency, and compare every packaged JAR hash with its matching
`build/libs` output. Publish the 16 individual JARs plus the all-versions ZIP;
release notes must state the final batch window, 10000 ms cooldown default, providers,
experimental-provider warning, optional-key custom/local AI support, the vanilla
settings blacklist, and structured debug failure reasons.

Before publishing, test the matching JAR in a clean client with the matching
loader/API dependency. Compilation and remapping verify the build toolchain, but
do not replace an in-game smoke test for every Minecraft release.
