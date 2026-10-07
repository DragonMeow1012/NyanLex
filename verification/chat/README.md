# Chat display timing and translation backfill

The advanced settings offer three chat display timings:

- `ORDERED`: wait for translations and display messages in receive order.
- `READY_FIRST`: wait for each translation and display it when ready.
- `ORIGINAL_FIRST` (default): show originals immediately, then update each
  message in its original history position, preserving its age and metadata.

Timing is independent of the existing bilingual/translation-only display mode.
Bilingual mode includes both texts; translation-only mode ends with only the
translation. Missing or invalid timing settings default to `ORIGINAL_FIRST`.
The old two-state JSON field is ignored; only the new enum is persisted.

Clearing or trimming chat retires the missing entry instead of appending it
again. Switching server or request settings invalidates old callbacks. Server
announcement frames display their original lines as they arrive and retain
their existing paragraph translation behavior. Waiting modes fall back to the
original after 15 seconds so a missing callback cannot indefinitely block chat.
Modern adapters retain late callbacks for five minutes. Legacy adapters retain
original-first requests for five minutes and retire timed-out waiting entries.
Switching to original-first reveals pending originals without duplicate lines.

`run.py` exercises the eleven maintained modern chat adapters against their
compiled, named Minecraft classes. `run_legacy.py` does the same for the five
Java 8 adapters. The templates cover immediate originals, out-of-order results,
in-place bilingual updates, preserved history age, failed results, cleared
history, all three timings, timeout fallback, timing changes, display content,
and callback retirement. Modern tests additionally
cover late recovery and announcement collection. Legacy tests explicitly cover
translations arriving after the former 15-second deadline.

These are in-memory game-class simulations: no Minecraft client constructor,
window, server connection, or real translation provider is started. They do
not replace testing inside a running game. Mixin target checks and the final
JAR regressions in `verification/echo` provide separate artifact validation.

Build the selected project first and export its named classpath with
`verification/export-maintained-verification.init.gradle`. Then run:

```powershell
python verification/chat/run.py --module fabric2612 --output .localtest/chat/fabric2612
python verification/chat/run_legacy.py --module fabric1165 --output .localtest/chat/fabric1165
```

For Forge, export the runtime classpath using the project's JDK 8 Gradle wrapper
with `export-runtime.init.gradle` and `NYANLEX_CHAT_RUNTIME_REPORT`, then pass
that report to `run_legacy.py --classpath-report`. Modern simulation harnesses
use the local JDK 25; Forge harnesses use the project's local JDK 8. These test
toolchains do not change the release JAR's declared Java compatibility.

The chat change preserves the `1.0.0-echo-fix.20261006.2` translation cache,
filter, translator, service, and name-masking sources. Release work uses full
Gradle source builds and normal remapping/reobfuscation, followed by all 62
exact-JAR echo regressions and the normal release packaging checks.
