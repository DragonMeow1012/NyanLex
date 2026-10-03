# Modern Fabric release ports

`targets.json` is the exact release matrix for this project. Every target has its own Minecraft dependency, Fabric API pin, Java requirement, metadata, classes and output JAR. Build with Gradle 9.5.0 running on JDK 25:

```powershell
gradle -p ports/fabric-modern --project-cache-dir "$PWD/ports/fabric-modern/.gradle/targets/1.21.6" '-Ptarget=1.21.6' build
```

The artifact is `build/<target>/libs/nyanlex-<mod_version>-Fabric-<target>.jar` relative to this directory. `mod_version` comes from the repository's root `gradle.properties`. Always pass a target-specific `--project-cache-dir`: changing `-Ptarget` while sharing task history can make Gradle remove the previous target's classes as stale outputs. The command above isolates that history under `.gradle/targets/<target>`.

## Source ownership

Translation logic and resources come directly from the maintained root, `fabric12111`, or `fabric2612` source tree. Only the affected Minecraft/Fabric API boundaries are adapted:

- `1.20-components`: pre-1.21 options-screen lifecycle, floating-point HUD ticks and HUD draw calls.
- `render-state`: 1.21.2 entity render states, direct sidebar rendering, key bindings and client scheduling.
- `data-components`: 1.21.5 inventory access and optional NBT values.
- `gui-state`: 1.21.6 void rendering methods and deferred tooltips; reuses the maintained GUI-render implementations through `overrides/gui-state.json`.
- `input-events`: 1.21.9/1.21.10 input-event APIs, pre-1.21.11 class names and chat-render signature.
- `unobfuscated`: 26.1/26.1.1 directly share the maintained 26.1.2 platform implementation, including the distance argument in the 26.1-family name-tag submission descriptor.

`patches` contains checked, build-time API substitutions, not runtime version detection. An upstream source change that invalidates a substitution fails the build. Generated adapters stay under the target's build directory; there are no duplicated translation-core trees.

## Verification

Each successful build exports `build/<target>/port-verification.json` with the named Minecraft classpath and compiled classes. From the repository root, run:

```powershell
python verification/verify-port-artifacts.py fabric/1.21.6
```

This verifies the artifact, release metadata, feature classes and Mixin targets against that exact game version. Compilation and static checks do not replace an in-game smoke test.
