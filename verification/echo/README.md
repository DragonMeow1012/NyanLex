# Echo and cache compatibility regressions

This suite exercises real modern `TranslationCache`, `TextFilter`, and
`TranslationService` sources with in-process fake translators. It checks that a
confirmed semantic KEEP stops new requests, queued and late styled work settles
without retry debt, recognized Traditional Chinese lobby notices never enter a
translator, and existing cache rows remain immediately usable.

The runner reuses the existing tests in `src/test/java`; this directory contains
only the regression classes:

| Class | Main coverage |
| --- | --- |
| `UserEchoRegressionTest` | Three echo confirmations, pending collector and worker jobs, backoff rehydration, late replies, mixed batches, paced requests, restarts, manual invalidation, and final callbacks |
| `LobbyNativeAnnouncementRegressionTest` | Exact native lobby announcements, CS/section styles, malformed or additional content, source and target language guards, and zero translator calls |
| `ExistingCacheReuseRegressionTest` | Existing template rows in a fake persistent store, first render and async completion, repeat observations, prewarming, live player-slot restoration, and styled keys |
| `ServerTraceReplayRegressionTest` | Recorded Herobrine/Cookiez KEEP-to-resend gaps, collector/worker alternatives at 15/60/144 fps, recorded native announcements, and a real recorded English retry result |

The integrated suite contains **474 tests**, with no skipped cases. Each
run must execute at least 450 cases successfully. This is a core regression suite;
it does not replace building all release JARs or testing Minecraft at runtime.

## Recorded server scenarios

The two `fixtures/*.excerpt.jsonl` files contain 32 and 18 complete event records
recovered from the saved outputs of the original October 6 trace analysis. The
second excerpt is the field projection printed by that analysis. They are
excerpts, not the complete 2,303-line and 1,557-line captures.

The resend cases use the literal recorded request/semantic keys and the observed
3,750 ms and 4,225 ms intervals between the third echo and the erroneous resend.
The log does not expose enqueue times: collector/worker ordering and 15/60/144 fps
render loops are explicit simulation alternatives. Each case establishes real
echo confirmations through the production cache, leaves styled work pending,
then checks provider entries, callbacks, persistence, and retry debt after KEEP.
Native notices run through the production service and both backend selections.
The recorded `idoit` -> `白癡` response is a positive control for useful retries.

On October 7 the pre-fix release core reproduced all 16 failure cases, while the
fixed Fabric 26.1.2 JAR passed all 17 scenarios. The old control is the pre-fix
production core, not the original instrumented trace JAR. Fake in-process
translators record provider-entry counts; no physical HTTP traffic or Minecraft
server/client runtime is tested.

## Run

Use Python 3 and a JDK **21 or newer**. Supply existing local dependency JARs; the
runner does not download anything or contact translation providers. The verified
dependencies are Gson 2.11.0 and JUnit Platform Console Standalone 1.11.4.

From the repository root:

```sh
python verification/echo/run.py \
  --java-home /path/to/jdk-25 \
  --gson /path/to/gson-2.11.0.jar \
  --junit /path/to/junit-platform-console-standalone-1.11.4.jar
```

On Windows, the same options accept Windows paths and `java.exe` / `javac.exe`.
Use a single line or the continuation syntax appropriate for your shell.

The script locates the repository from its own path, so it also works from another
working directory. `--module` selects a modern source tree and defaults to
`fabric2612`; use `--module .` for the root Fabric core. Java 8 legacy translators
use a separate engine and are outside this suite.

In source mode, the runner first compiles the actual `fabric1171` cache, filter, and dependencies
with `javac --release 16`. Behavioral tests then compile the selected core and test
sources with `--release 21`; this does not change release artifact bytecode.

Each run creates a fresh directory under `build/echo-verification` containing
compiler/test logs, executed command arguments, JUnit XML reports, and
`summary.json`. Existing runs are retained. `--output-dir` selects another results
parent directory. A compilation error, failed/skipped test, or incomplete suite
returns a nonzero exit status.

### Test an exact modern JAR

Add `--jar /path/to/the-release.jar` to the same command to compile only the tests
and load production classes from that exact artifact. Use a JDK new enough to read
and run the artifact's bytecode; Java 25 artifacts require JDK 25 or newer.

Artifact mode uses an empty source path, skips the separate source compilation,
and rejects any production class accidentally compiled into the test output. It
also records the JAR's SHA256 and checks that the file did not change during the
run. The summary distinguishes an artifact test from a source test. This validates
the core behavior present in the selected JAR; Minecraft metadata, full artifact
coverage, and game runtime checks remain separate release gates.

See [MODERN_ARTIFACT_RESULTS.md](MODERN_ARTIFACT_RESULTS.md) for the four formal
candidate artifact hashes, their 457-case results, and the Fabric 26.1.2
full-source/core-byte comparison.

## Java 8 legacy engines

The separate legacy runner compiles the real legacy engine and its tests with
`--release 8`. It runs at least 41 echo regression cases plus the existing
`InlineLegacyCoreSimulation`, including its subscriber, queue capacity, fairness,
request-switch, and protected-term checks. Use a JDK 9 or newer that supports
`--release 8`, with the same local Gson and JUnit dependencies:

```sh
python verification/echo/run_legacy_echo.py \
  --java-home /path/to/jdk-25 \
  --gson /path/to/gson-2.11.0.jar \
  --junit /path/to/junit-platform-console-standalone-1.11.4.jar \
  --module fabric1144
```

Supported legacy modules are `fabric1144`, `fabric1152`, `fabric1165`, `forge1122`,
and `forge1132`. Add `--jar /path/to/the-release.jar` to test production classes
from an exact JAR. In JAR mode the source path is empty, and the runner rejects
production classes compiled into the test output. Forge tests use the Forge
package name without copying its production sources.

The legacy cases cover three valid echoes, malformed-response streak resets,
late results, queued mixed batches, a paced request before the fake HTTP transport,
engine separation, existing AI and semantic translations, and manual retranslation.
They also exercise real export/save/load through the existing `legacy-template-v1`
format. Legacy KEEP decisions remain session memory unless saved through that
existing export/import flow; this change does not add automatic disk persistence.

See [LEGACY_RESULTS.md](LEGACY_RESULTS.md) for the legacy review findings, source
verification results, and the accepted storage boundary.
