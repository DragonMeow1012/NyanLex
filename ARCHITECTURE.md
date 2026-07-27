# Project architecture

## Source ownership

There is one canonical copy of loader-independent code:

- `src/main/java/com/dragonmeow/mctranslator/{cache,config,service,style,translate}`
- `src/main/resources/assets/mctranslator/lang`

`fabric26` and `neoforge` compile those directories directly. Their source
trees contain only loader and Minecraft-version integration code. Do not copy
common classes or language files into either target.

Historical targets retain version-pinned snapshots of the common packages. There is
no mechanical cross-version sync: changes must be ported deliberately and verified
with that target's Java, loader and Minecraft API. This prevents a current-core
change from silently breaking an older compatibility build.

## Responsibility boundaries

- `TranslatorConfig` owns JSON persistence, defaults and normalization. It has
  no Minecraft dependency.
- `TranslationService` owns surface decisions and coordinates the two caches.
  It does not own HTTP, login UI or loader events.
- `TranslationCache` owns request coalescing, validation, retry state and
  persistence.
- `OpenAiTranslator` owns the OpenAI-compatible request/response protocol.
- `CodexAppServerClient` owns the local `codex app-server` process and JSONL
  protocol. Its dedicated `CODEX_HOME` keeps game login state independent.
- `AiTranslationRuntime` owns AI-provider routing, Codex process lifetime,
  request pacing and per-launch token accounting.
- Loader entry points own Minecraft events, mixins, screen transitions and
  client-thread delivery.
- Loader settings screens own version-specific widgets, rendering and screen
  navigation. They persist one `TranslatorConfig`; `AiTranslationRuntime` executes it.

## Request policy

Translation is demand-driven. Opening a creative inventory or container must
not scan or pre-translate item names. Requests originate from visible text,
an actually rendered tooltip, FTB text, chat, or an explicit user action such
as screen scan or retranslate.

## Verification

Run the shared unit tests first, then both active target builds:

```powershell
.\.gradle-local\gradle-8.10\bin\gradle.bat test
.\.gradle-local\gradle-9.5.0\bin\gradle.bat -p fabric26 build
.\.gradle-local\gradle-8.13\bin\gradle.bat -p neoforge build
```

Gradle 8.13 must run on Java 21. Fabric 26.2 compiles with its Java 25
toolchain; NeoForge 1.21.1 compiles with Java 21.
