# Codex mode latency report (2026-10-02)

Scope: `CodexAppServerClient` / `CodexAppServerTransport`, codex-cli 0.159.2, model `gpt-5.6-sol`,
effort `medium` (both taken from the user's `nyanslate.json`; **model, effort and service tier were
never changed**). Real requests used: 10 of 10 (3 baseline, 3 reuse probe, 4 after).
Harness: `scratchpad/codex-lat/` (`harness.py`, `JHarness.java`, `mock_capture.py`); it ran on a copy of
`mctranslator-codex-home` (the `nyanslate-codex-home` dir did not exist on this machine); auth files were
copied, never read or printed, and deleted afterwards.

## Key finding: the latency is model-side, not module overhead
Per request (fresh ephemeral thread, as the module does it):

| step | measured |
|---|---|
| spawn -> initialize done | ~630 ms (once per session) |
| model/list | ~0.8-0.9 s cold, 14 ms warm |
| thread/start | 10-31 ms |
| turn/start response | 2-16 ms |
| first output delta | 2.7-3.9 s (model/server TTFT) |
| turn/completed | 3.4-4.4 s |

thread/start, thread/unsubscribe and the RPC round trips are negligible, so "pre-start a spare thread",
"drop redundant round trips" and "pool of warm connections" would buy nothing; they were not built.
`awaitTurnMessage` is future based (no fixed sleep). Output is ~40-90 tokens; reasoning tokens vary per
request (0 to 271 at `medium`), which alone swings a request from 2.8 s to 8.8 s.

## Before / after
Same system prompt and Hypixel tooltip payloads (`live-dump-2`), sequential.

| run | per-request wall (ms) | input tokens | cached input | notes |
|---|---|---|---|---|
| BEFORE (fresh thread each, original flags), n=3 | 3407 / 4367 / 3504 (mean 3760) | 5384 / 5362 / 5387 | 0 / 4864 / 0 (mean 32%) | |
| AFTER (Java client, trimmed prompt + warm-up), n=4 | 3829 / 3388 / 2761 / 8766 | 3631 / 3609 / 3634 / 3744 | 3456 / 0 / 0 / 2304 (mean 40%) | last request drew 271 reasoning tokens |
| PROBE (thread reuse + trimmed), n=3 | 5523 / 2382 / 4228 | 3631 / 3795 / 3932 | 0 / 3456 / 3584 (mean 63%) | history grows each turn |

Honest reading: input tokens dropped 33% (5.4k -> 3.6k), which is real and deterministic. End-to-end
latency is **not distinguishable** from baseline at n<=4: run-to-run variance (reasoning tokens, server
queueing) is +-1-4 s. The first request after game start does gain ~0.6 s from the warm-up.

## Changes
1. `CodexAppServerClient.minimalAppServerCommand`: extra `-c` flags, constant across requests so the
   cached prefix stays byte identical. Verified with a local mock provider capturing the exact
   Responses body (request went from 25.0 KB to 17.2 KB):
   - `include_permissions_instructions=false`, `include_environment_context=false` (the environment block
     contained cwd, shell, date -> also removes per-day prefix variation),
     `include_apps_instructions=false`, `include_collaboration_mode_instructions=false`,
     `project_doc_max_bytes=0`, `skills.include_instructions=false`, `skills.bundled.enabled=false`
     (the skills catalog also leaked the user's `~/.agents/skills` paths and descriptions into every
     request), `features.multi_agent_v2.usage_hint_enabled=false`;
   - features `unified_exec`, `view_image`, `sleep_tool`, `worktrees` off (fewer default tool schemas).
2. `CodexAppServerClient.warmUpAsync()` (daemon thread: start app-server + `account/read`), invoked from the
   glue in all 11 trees when `aiUseCodex` is on. Also makes `isSignedInCached()` true in a fresh session
   (previously only the settings screen filled it, so the AI retry gate read "signed out").
3. sync-core run; glue line ported to the 10 non-root trees.

## Findings that need a user decision (not changed)
- **Service tier `priority` is only sent if `cachedModels` is non-empty, and `listModels()` is only
  called from `AiConfigScreen`.** In a fresh game session where the settings screen was never opened, no
  `serviceTier` is sent at all (fast mode silently off). Warm-up deliberately does NOT call `listModels()`
  to avoid changing tier behaviour/quota use. If "send priority whenever the model supports it" is the
  intent, add `listModels()` to `warmUpAsync()` (one line; 14 ms warm, ~0.9 s cold). My harness called
  `listModels()` first in both before and after runs, so the numbers above include priority.
- Official base prompt is replaced only partially: `instructions` is empty on this model
  (`x-openai-internal-codex-responses-lite`); our `baseInstructions` is sent as the first developer message.
  ~3.6k tokens of fixed overhead remain (the `exec`/`wait`/`request_user_input` tool and the
  `collaboration` multi-agent tool schemas plus a 2.5 KB multi-agent developer note). None of the config
  keys I found removes these; only a custom `model_catalog_json` might, which I judged too invasive.
- The exchange dumps show mojibake in the system prompt's Chinese examples (e.g. `Enchant 竊・髯・ｭ・`); that
  is the dump or the source constant being decoded with the wrong charset. Worth a separate check.
- Legacy `LegacyCodexClient` (Java 8 ports, not touched): same pattern (fresh thread per request, no trim
  flags) so it would benefit from the same flags if it uses the same app-server.

## Not adopted
- **Thread reuse / fixed prompt_cache_key.** `prompt_cache_key` is the thread id (confirmed from the captured
  request) and the protocol has no parameter to set it (`thread/start` has none; `CODEX_PROMPT_CACHE_KEY`
  does not exist in this binary). Reusing one thread gave a steadier cache (63% vs 32-40%), but latency
  gain was not shown (n=3, noisy), history grows every turn (rotation needed), earlier anchored
  translations stay in context (risk of anchor confusion), and `thread/revert` only supports paginated
  (non-ephemeral, disk-persisted) threads. Failed the "measurably faster" bar -> not built.
- Spare/pre-started thread, connection pool, dropping round trips: thread/start is 10-30 ms.
- Model-list prefetch to avoid `failed to refresh available models`: not reproduced with this CLI version
  (thread/start was never delayed in 10 runs); stderr was not captured for it, so it is unconfirmed.
- Parallel turns: the client already supports concurrent `complete()` calls (futures keyed by id) and the
  translator runs `workerThreads=2`; no change needed. Cross-request parallelism does not reduce a single
  request's latency.
- Direct `chatgpt.com/backend-api` endpoint: excluded by instruction.
