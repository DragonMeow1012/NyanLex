# Rebuilding only the 1.0.0 translation cores

> Historical procedure for the initial echo-only rebuild. The October 7 chat-mode
> release uses Gradle source builds and remap/reobfuscation for all 62 targets;
> see [its artifact manifest](release-2026-10-07.json). The incremental artifacts
> described below are not the October 7 published JARs.

`rebuild-release-cores.py` is a separate, explicitly identified incremental
build procedure. It uses the already remapped or reobfuscated published 1.0.0
JAR for each canonical Minecraft/loader target. It recompiles only
`TranslationCache.java` and `TextFilter.java` in modern trees, or the complete
`LegacyTranslator.java` class family in Java 8 trees.

Every other JAR entry retains its exact uncompressed bytes. This includes game
adapters, Mixin classes, refmaps, resources, dependency predicates, licenses,
and the original manifest. The ZIP container is recreated deterministically
with the recorded Python/zlib version. Retained `Fabric-*` manifest fields
describe the original mapped artifact; they do not describe a new Loom run.

The procedure makes no network requests and performs no publication. It does
not run Minecraft or a translation provider. Its reports always set
`method = "core-only incremental rebuild"`,
`clean_full_gradle_build = false`, and `runtimeTested = false`.

## Inputs

Preserve the original input JARs before replacing any public release assets.
The original all-versions ZIP used for this change is:

- GitHub release ID: `402472496`, tag `v1.0.0`.
- Filename: `NyanLex-1.0.0-all-versions.zip`.
- Size: `65107793` bytes.
- SHA-256: `e2ed6a521d38f85d0848f4cb13382fb581241214ec68bc5ed9a2c1c3525ec968`.
- Its 62 JAR hashes match `verification/release-2026-10-05.json`.

The release's tag/target commit is older than the assets that were updated on
October 5. The source baseline is therefore explicitly pinned to
`95ae24353cbe1434bd6508e69af0ff8bfb4a72b3`. The procedure recompiles that
baseline for every target and compares the entire resulting core class family
to the original published core. It never assumes that a moving `HEAD` or the
release tag identifies the current binary's source.

The input directory must have this layout:

```text
original-jars/fabric/26.1.2/nyanlex-1.0.0-Fabric-26.1.2.jar
original-jars/forge/1.12.2/nyanlex-1.0.0-Forge-1.12.2.jar
original-jars/neoforge/26.3/nyanlex-1.0.0-NeoForge-26.3.jar
```

All 62 paths come from `verification/release_matrix.py`. The script verifies
the manifest's exact target set and each original SHA-256 before compilation.

The verified toolchains used for this release are Eclipse Temurin JDK
`8u504-b01`, `21.0.12.1+1`, and `25.0.4.1+1`. Forge compiles with JDK 8;
other targets up to Java 21 use JDK 21 with their declared `--release`;
Java 25 targets use JDK 25. The auditor also runs on JDK 25. The build uses
Gson `2.11.0` and ASM core/tree `9.10.1`. These are tooling/compile dependencies
only and are not added to any mod JAR. The report records their SHA-256 values.

## Command

Use new, empty output and report directories. Paths may be adapted to the
machine; the source baseline must remain explicit and available in Git history.

```bash
python verification/rebuild-release-cores.py \
  --input-root /path/to/original-jars \
  --output-root /path/to/rebuilt-jars \
  --report-root /path/to/rebuild-reports \
  --java-home /path/to/jdk-25.0.4.1+1 \
  --java21-home /path/to/jdk-21.0.12.1+1 \
  --java8-home /path/to/jdk8u504-b01 \
  --gson /path/to/gson-2.11.0.jar \
  --asm /path/to/asm-9.10.1.jar \
  --asm-tree /path/to/asm-tree-9.10.1.jar \
  --baseline-commit 95ae24353cbe1434bd6508e69af0ff8bfb4a72b3
```

`--target fabric/26.1.2` may be repeated to select a subset for a diagnostic
run. A subset report identifies its selected count and never claims 62 passes.
PowerShell accepts the same arguments; use its own line-continuation syntax or
put the command on one line.

## Gates

Each target passes the following checks before it is recorded as rebuilt:

1. The original JAR hash matches the pinned release manifest; its ZIP CRC is
   valid and its entries are unique and safe. Signed JARs/manifests are rejected.
2. The pinned baseline sources compile against that target's original JAR with
   an empty source path, no annotation processing, and no implicit compilation.
3. The complete baseline core family matches the original published core in
   ASM canonical form. Only debug tables, verifier-frame encoding, constant-pool
   ordering, and inner/nest inventory ordering are normalized. Instructions,
   signatures, field values, annotations, and bootstrap arguments remain part of
   the comparison. Actual artifact bytes are never normalized by the auditor.
4. The frozen new sources compile against the same original JAR. Every emitted
   class must belong to the selected outer class family and use the exact target
   Java class level. No Minecraft, loader, or Mixin types may occur in core class
   references or structural descriptors.
5. Public and package contracts remain compatible. All unchanged classes'
   references to the core resolve after replacement. Compiler-generated Java 8
   private-access bridges may be renumbered only when every original reference
   to them is inside the fully replaced family. A narrowly recognized existing
   JDK-inherited reference must retain its exact original reference and JDK
   inheritance boundary; this does not excuse a removed mod declaration or an
   inherited constructor.
6. The entire old class family is removed/replaced, including nested classes.
   Every other entry's SHA-256 remains identical. All freshly compiled class
   bytes in the final JAR match the compiler outputs exactly.
7. The final JAR passes Java-level, feature, Mixin-rule, metadata, entrypoint,
   version, icon, and ZIP checks. Ports use the existing port metadata checker.
   Maintained targets retain their actual original Minecraft predicates: four
   maintained NeoForge artifacts use bounded ranges, and Forge 1.13.2 obtains
   effective version `1.0.0` from its manifest via `${file.jarVersion}`.
   All 44 expanded ports retain their existing strict zero-finding Mixin-rule
   gate. The additional check on maintained artifacts records any identical
   original/final findings as `unchanged_baseline_findings`, never as a pass;
   a change to the finding set fails. The pinned original Fabric 1.16.5 artifact
   has one such existing finding: its optional `@Pseudo` Jade mixin is listed in
   a required config. Both that class and config remain byte-for-byte unchanged.
8. Source and verifier hashes are unchanged between the start and end of the
   run. Every target's input/output hash, source hashes, class hashes, preserved
   entry hashes, compiler commands, and logs are saved outside the artifact tree.

`CoreClassAudit.java` is verification tooling, not mod source. Its compiled
classes stay in the report directory.

## Separate artifact tests and full builds

`verification/echo/run.py --jar <final-jar>` and
`verification/echo/run_legacy_echo.py --jar <final-jar>` run the relevant JVM
regressions against the exact finished artifact. Their empty source paths and
test-output checks prevent production source classes from replacing the JAR's
implementation during testing. Keep these reports linked to the tested SHA-256.
They are core JVM tests, not an in-game runtime test.

For this release, Fabric 26.1.2 was also compiled from its complete source tree
against the verified official unobfuscated game JAR and matching dependencies.
That independent full-source output corroborates the new core bytes. Its
Linux resource line endings and plain-Java manifest differ from the original
Windows/Loom resources, so it is not the selected distributable: the matrix uses
the incremental artifact with the original resource and manifest bytes.

The existing full Gradle workflows, port game-classpath Mixin checks, and
`package-release.ps1` remain separate. This procedure reruns the checks that
operate on final JARs alone. It does not fabricate Gradle classpaths, relabel a
historical game-classpath check as a new pass, or claim the complete Gradle
release gate has run. A release package must additionally validate the exact
62-file set and all archive/checksum contents before publication.
