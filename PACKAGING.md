# Minecraft Translator 1.0.2 packaging

Release JARs are under `mods-jar/1.0.2`, grouped by loader. Each JAR is pinned to
the Minecraft version printed in its filename; it is not a universal cross-version
binary.

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
settings screen. Fabric 1.21.11 omits boss-bar, entity-name and scoreboard direct
render hooks because those rendering APIs changed in that release.

## Release layout

```text
mods-jar/1.0.2/
  forge/
  fabric/
  neoforge/
```

Before publishing, test the matching JAR in a clean client with the matching
loader/API dependency. Compilation and remapping verify the build toolchain, but
do not replace an in-game smoke test for every Minecraft release.
