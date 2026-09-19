# Current-screen retranslation and translation sharing

## Changes

- `P` captures one frame of original visible text, invalidates those translation
  rows, and requests new translations. Navigation cancels capture. FTB paragraphs
  are captured before wrapping, and hovered item paragraphs keep tooltip routing.
  Capture is limited to 512 distinct strings, each at most 16,384 characters.
- Translation Settings can export/import translation-only JSON. Existing final
  wording is retained, invalid placeholder layouts are rejected, and importing
  does not contact translation providers. Target language, template format, and
  machine provider must match. Modern and Java 8 legacy formats are separate.
- Legacy imported rows are persisted locally. Imports exceeding the shared
  8,192-entry legacy cache are rejected before mutation.
- File selection uses LWJGL TinyFD, avoiding Minecraft's `java.awt.headless=true`.
  LWJGL 2 clients use an isolated Java 8-compatible AWT picker process instead.
- Modern memory-cache hits reuse already validated rows. Disk rows, incoming
  translations, imported rows, and whitespace aliases still undergo validation.

## Validation

- All 18 Minecraft/loader targets built successfully.
- Root JUnit suite: 526 tests, zero failures/errors.
- Fabric 1.21.11 JUnit suite: 490 tests, zero failures/errors.
- Java 8 legacy core simulation passed, including import reuse, duplicate merge,
  persistence across restart, and capacity rejection without partial mutation.
- Final-JAR core/glue checks passed for both Forge targets. Additional complete
  final-JAR checks passed for Fabric 1.14.4. The unrelated full Codex protocol
  matrix was stopped during Fabric 1.15.2; a full matrix pass is not claimed.
- After the final picker change, `verify-translation-features.py` checked all 18
  rebuilt JARs: feature classes/methods, localized labels, Java 8 bytecode for the
  five legacy targets, Forge 1.12.2 transformer manifest, and Forge 1.13.2 coremod.
- Existing packaging scripts validated 16 main JARs plus two 26.3 JARs, ZIP entry
  bytes, and SHA-256 manifests. This initial validation used local 1.0.4 builds.
- No full interactive game test of the new file picker or every loader was run.

## Stutter evidence and limits

A 60-second JFR sample from the previously running Fabric client contained
translator regex/style processing on the render thread, including cache shape
validation. It did not capture a significant GC pause, slow file write, or monitor
stall. Source review confirmed repeated validation of unchanged memory-cache
hits, which this change removes. This is an identified avoidable cost, not proof
that every intermittent long-session stall has the same cause. A long-session
before/after game comparison remains necessary.

## Promotional media

`docs/videos/minecraft-translator-demo-10x.mp4` retains the whole source timeline.
Demonstration sections stay at normal speed; waiting sections run at 10x with
the literal title `加速10倍ing`. Output is approximately 60 seconds at 60 fps.
API-key and account regions are covered before time remapping. Contact sheets
were checked for the mask and speed label. Original media was not modified.
README screenshots use the anonymized player-name versions.

## 1.0.5 publication rebuild

All 18 targets were rebuilt with 1.0.5 metadata and client-version strings.
The root and Fabric 1.21.11 test suites passed again. Feature-artifact checks
passed for all 18 new JARs. The main packaging script now includes 26.3 and
verified 18 JARs, four ZIPs (12 Fabric, four NeoForge, two Forge, 18 combined),
and 22 SHA-256 entries. The separate 26.3 packaging script was removed.
Both README download tables now refer to the same `v1.0.5` release.
