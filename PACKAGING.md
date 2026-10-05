# NyanLex Translator 1.0.0 packaging

Each JAR is tied to one Minecraft version and loader. The expanded release contains **62 JARs**: 37 Fabric, 23 NeoForge and 2 Forge. This covers every stable Minecraft release from 1.16.5 through 26.3 for Fabric, and from 1.20.1 through 26.3 for NeoForge, while retaining the four older targets.

NeoForge has no releases for Minecraft versions earlier than 1.20.1. Minecraft snapshots and pre-releases are not included. Some stable Minecraft targets require a beta NeoForge build; use the exact dependency pins in the project properties or [port manifests](ports/README.md). The NeoForge 26.1.2 target is built against **26.1.2.109**, not only relabelled with a lower minimum.

## Project matrix

The canonical machine-readable matrix is [verification/release_matrix.py](verification/release_matrix.py), which combines the 18 maintained targets with the three port manifests and rejects missing or duplicate combinations.

| Loader | Minecraft | Java | Project |
| --- | --- | ---: | --- |
| Fabric | 1.14.4 | 8 | `fabric1144` |
| Fabric | 1.15.2 | 8 | `fabric1152` |
| Fabric | 1.16.5 | 8 | `fabric1165` |
| Fabric | 1.17 | 16 | `ports/fabric-legacy` (`-Ptarget=1.17`) |
| Fabric | 1.17.1 | 16 | `fabric1171` |
| Fabric | 1.18 | 17 | `ports/fabric-legacy` (`-Ptarget=1.18`) |
| Fabric | 1.18.1 | 17 | `ports/fabric-legacy` (`-Ptarget=1.18.1`) |
| Fabric | 1.18.2 | 17 | `fabric1182` |
| Fabric | 1.19 | 17 | `ports/fabric-legacy` (`-Ptarget=1.19`) |
| Fabric | 1.19.1 | 17 | `ports/fabric-legacy` (`-Ptarget=1.19.1`) |
| Fabric | 1.19.2 | 17 | `ports/fabric-legacy` (`-Ptarget=1.19.2`) |
| Fabric | 1.19.3 | 17 | `ports/fabric-legacy` (`-Ptarget=1.19.3`) |
| Fabric | 1.19.4 | 17 | `fabric1194` |
| Fabric | 1.20 | 17 | `ports/fabric-legacy` (`-Ptarget=1.20`) |
| Fabric | 1.20.1 | 17 | `fabric120` |
| Fabric | 1.20.2 | 17 | `ports/fabric-legacy` (`-Ptarget=1.20.2`) |
| Fabric | 1.20.3 | 17 | `ports/fabric-legacy` (`-Ptarget=1.20.3`) |
| Fabric | 1.20.4 | 17 | `ports/fabric-legacy` (`-Ptarget=1.20.4`) |
| Fabric | 1.20.5 | 21 | `ports/fabric-modern` (`-Ptarget=1.20.5`) |
| Fabric | 1.20.6 | 21 | `ports/fabric-modern` (`-Ptarget=1.20.6`) |
| Fabric | 1.21 | 21 | `ports/fabric-modern` (`-Ptarget=1.21`) |
| Fabric | 1.21.1 | 21 | `repository root` |
| Fabric | 1.21.2 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.2`) |
| Fabric | 1.21.3 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.3`) |
| Fabric | 1.21.4 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.4`) |
| Fabric | 1.21.5 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.5`) |
| Fabric | 1.21.6 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.6`) |
| Fabric | 1.21.7 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.7`) |
| Fabric | 1.21.8 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.8`) |
| Fabric | 1.21.9 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.9`) |
| Fabric | 1.21.10 | 21 | `ports/fabric-modern` (`-Ptarget=1.21.10`) |
| Fabric | 1.21.11 | 21 | `fabric12111` |
| Fabric | 26.1 | 25 | `ports/fabric-modern` (`-Ptarget=26.1`) |
| Fabric | 26.1.1 | 25 | `ports/fabric-modern` (`-Ptarget=26.1.1`) |
| Fabric | 26.1.2 | 25 | `fabric2612` |
| Fabric | 26.2 | 25 | `fabric26` |
| Fabric | 26.3 | 25 | `fabric263` |
| Forge | 1.12.2 | 8 | `forge1122` |
| Forge | 1.13.2 | 8 | `forge1132` |
| NeoForge | 1.20.1 | 17 | `neoforge120` |
| NeoForge | 1.20.2 | 17 | `ports/neoforge` (`-Ptarget=1.20.2`) |
| NeoForge | 1.20.3 | 17 | `ports/neoforge` (`-Ptarget=1.20.3`) |
| NeoForge | 1.20.4 | 17 | `ports/neoforge` (`-Ptarget=1.20.4`) |
| NeoForge | 1.20.5 | 21 | `ports/neoforge` (`-Ptarget=1.20.5`) |
| NeoForge | 1.20.6 | 21 | `ports/neoforge` (`-Ptarget=1.20.6`) |
| NeoForge | 1.21 | 21 | `ports/neoforge` (`-Ptarget=1.21`) |
| NeoForge | 1.21.1 | 21 | `neoforge` |
| NeoForge | 1.21.2 | 21 | `ports/neoforge` (`-Ptarget=1.21.2`) |
| NeoForge | 1.21.3 | 21 | `ports/neoforge` (`-Ptarget=1.21.3`) |
| NeoForge | 1.21.4 | 21 | `ports/neoforge` (`-Ptarget=1.21.4`) |
| NeoForge | 1.21.5 | 21 | `ports/neoforge` (`-Ptarget=1.21.5`) |
| NeoForge | 1.21.6 | 21 | `ports/neoforge` (`-Ptarget=1.21.6`) |
| NeoForge | 1.21.7 | 21 | `ports/neoforge` (`-Ptarget=1.21.7`) |
| NeoForge | 1.21.8 | 21 | `ports/neoforge` (`-Ptarget=1.21.8`) |
| NeoForge | 1.21.9 | 21 | `ports/neoforge` (`-Ptarget=1.21.9`) |
| NeoForge | 1.21.10 | 21 | `ports/neoforge` (`-Ptarget=1.21.10`) |
| NeoForge | 1.21.11 | 21 | `ports/neoforge` (`-Ptarget=1.21.11`) |
| NeoForge | 26.1 | 25 | `ports/neoforge` (`-Ptarget=26.1`) |
| NeoForge | 26.1.1 | 25 | `ports/neoforge` (`-Ptarget=26.1.1`) |
| NeoForge | 26.1.2 | 25 | `ports/neoforge` (`-Ptarget=26.1.2`) |
| NeoForge | 26.2 | 25 | `neoforge26` |
| NeoForge | 26.3 | 25 | `neoforge263` |

## Build and validation

The maintained targets use their existing source projects. The 44 additional targets share those sources and adapt only Minecraft/loader API boundaries; see [ports/README.md](ports/README.md).

```powershell
# Rebuild the 18 maintained targets with their established toolchains.
./verification/verify-release-matrix.ps1 -Phase Build

# Rebuild and statically validate all 44 additional targets.
./verification/build-ports.ps1

# Or build one additional target.
./verification/build-ports.ps1 -Targets neoforge/26.1.2

# Final-artifact feature checks across all 62 targets.
python verification/verify-translation-features.py

# Additional-target metadata, bytecode and exact vanilla Mixin call-site checks.
python verification/verify-port-artifacts.py

# Verification-tool regression tests and package transaction/rollback tests.
python verification/test-port-verification.py
./verification/test-release-transaction.ps1

# Existing core/protocol harness against the 18 maintained final JARs.
./verification/verify-release-matrix.ps1 -Phase FinalJar
```

To resume a failed maintained target, pass `-Projects` with its project key (for example, `-Projects fabric1171`). A selected run reports only that subset; retain the successful reports and hashes for the other targets before treating the complete release matrix as verified.

Port builds require an explicit target-specific `--project-cache-dir`. The driver supplies it; without isolation, changing `-Ptarget` can make Gradle delete a previous target's compiled classes as stale outputs. Successful port builds write `build/<MC>/port-verification.json` with that target's actual named game classpath.

The artifact verifier rejects anonymous inner classes in the composer Mixin. These classes can pass compilation and ordinary selector checks but fail during NeoForge's runtime class transformation. The composer uses the existing Host interface directly and an external platform canvas instead. Static checks also reject public Host method collisions with vanilla classes.

Builds and static checks do **not** prove that every modpack works. Record actual startup/play tests separately, including Minecraft, Loader build and modpack version; do not mark `runtimeTested` true based on compilation alone.

## Release folders

Package only after all checks pass:

```powershell
./verification/package-release.ps1
```

The default output is `mods-jar/1.0.0-expanded`, preserving the previous `mods-jar/1.0.0` package. The output folder label does not change mod version 1.0.0 or public asset names. `-OutputName` can select another immediate child of `mods-jar`.

```text
mods-jar/1.0.0-expanded/
  fabric/<MC>/nyanlex-1.0.0-Fabric-<MC>.jar
  neoforge/<MC>/nyanlex-1.0.0-NeoForge-<MC>.jar
  forge/<MC>/nyanlex-1.0.0-Forge-<MC>.jar
  NyanLex-1.0.0-Fabric.zip
  NyanLex-1.0.0-NeoForge.zip
  NyanLex-1.0.0-Forge.zip
  NyanLex-1.0.0-all-versions.zip
  SHA256SUMS.txt
```

The packager revalidates all 44 ports before creating a staging directory, binds copied files to verified hashes, checks all 62 JARs and four ZIP contents, and performs a guarded atomic folder swap with rollback. The checksum file contains 66 entries: 62 JARs and four ZIPs. Generated binaries are ignored by Git.

## Publication checklist

- Confirm 62 JARs, four ZIPs and one checksum file; compare every JAR to its build output.
- Confirm exact Minecraft/loader metadata, Java levels, client-side declarations, entrypoint classes, icons and four UI locales.
- Check Fabric API's actual mod ID for each pinned API artifact. Older targets may use `fabric`; newer ones use `fabric-api`. Do not infer the ID solely from the Minecraft major version.
- Confirm no earlier project-name directories, screenshot drivers, translation-hub content or authoring/test tools are packaged.
- Run core unit tests and `git diff --check`.
- Back up existing published assets and notes before replacement. Upload the individual JARs plus four ZIPs and checksums to GitHub Release `v1.0.0`.
- On Modrinth, use one exact Minecraft version and one loader per version; Fabric API is required for Fabric. Compare uploaded file hashes against the verified package.
- Update all four README download tables and release/store support descriptions together. Keep the GitHub default README in Traditional Chinese and Modrinth descriptions in English.
- Describe the difference between build/static validation and actual game testing, including any unresolved startup failures. Never publish a known-broken artifact.

## Minecraft 26.3 source sharing

`fabric263` and `neoforge263` use Java 25 and Gradle 9.5. They share the 26.2 platform sources and resources, with version-specific browser-link helpers in `platform26` / `platform263`. Input mappings use Minecraft input constants and NeoForge's original events so SDL and GLFW key codes are not mixed.

The verified dependency pins are Fabric Loader 0.19.5 / Fabric API 0.161.0+26.3 and NeoForge 26.3.0.6-beta. All public asset names retain the exact Minecraft version.
