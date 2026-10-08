# Skinamarink — Minecraft horror mod

A Fabric mod inspired by the film *Skinamarink*. The fear comes from absence and wrongness: the world changes when the player isn't looking, and an unseen presence is implied through sound and glimpses rather than shown. An AI "director" (Claude API) picks pacing and events from observed player behavior.

The owner is a beginner modder who playtests locally. Explain changes plainly, and when you summarize your work, list every file you touched and what to test in-game.

## Stack

- Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.158.0+26.2, Loom 1.17-SNAPSHOT (see `gradle.properties`)
- Java 25 (matches `.github/workflows/build.yml`), Gradle wrapper
- Mojang mappings (`net.minecraft.resources.Identifier`, `ServerPlayer`, etc.), not Yarn
- Base package `com.example`, mod id `modid` (`ExampleMod.MOD_ID`). Don't rename these.
- Owner's IDE is IntelliJ on Windows. Server run config module is `modid.main`, not `modid.client`.

## Layout

- `src/main/java/com/example/ExampleMod.java`: initializer. Builds all AI systems in `SERVER_STARTED` and stores them in public static fields (deliberate for now).
- `src/main/java/com/example/SkinamarinkDebugCommands.java`: `/sk` debug commands (`test`, `activity`, ...).
- `com.example.ai`:
  - `SkinamarinkAgent`: async Claude API decision layer with a bounded tool menu (including `issue_demand`). Runs off-thread and posts results back via the server executor.
  - `DemandTracker`: deterministic per-tick tracker for demands the presence issues (stay still, stay in room).
  - `PlayerMemory`: per-world JSON summary of player behavior, with a rare LLM summarization pass.
  - `PlayerLogger`: append-only JSONL log. Write-only, with no reader methods. That's why `PlayerActivityTracker` exists.
  - `PlayerActivityTracker`: in-memory rolling window of recent actions, stationary detection, room tracking.
- `com.example.util.LookDetectionUtil`: "is the player looking at X" (frustum + raycast). Every world change and presence action gates on this.
- `com.example.mixin.PlayerAdvancementsMixin`: hooks `PlayerAdvancements#award` and records advancements into the tracker.

## Hard rules

1. **Never call the Claude API per tick or inside a tick handler directly.** Director calls happen at decision points only (roughly every 30–90 s, or on a meaningful event), always async, always with a local fallback. A per-tick call burns real money at 20 calls/second.
2. **The local, no-API path must always work.** If `SKINAMARINK_ANTHROPIC_KEY` is missing or a call fails or is slow, the mod still runs on heuristics and the scare layer still fires. Never block the server thread on network I/O.
3. **The API key env var is `SKINAMARINK_ANTHROPIC_KEY`**, not `ANTHROPIC_API_KEY`. Never hardcode a key or commit one.
4. **No custom shaders.** The owner's hardware can't handle them. Atmosphere comes from vanilla block-state flicker (lights), looped sounds, and an optional third-party shaderpack.
5. **Changes happen only when the player isn't looking** (via `LookDetectionUtil`). Breaking this breaks the whole premise.
6. Prefer data-driven tables (JSON in `src/main/resources`) for tunables like advancement→mutation mappings, event weights, and cooldowns, so they can be tweaked without recompiling.
7. Keep changes small and on a branch. Don't refactor the static-field setup in `ExampleMod` unless asked.

## Testing

- You can't launch Minecraft here. Try `./gradlew build` to catch compile errors. If dependency downloads are blocked in this environment, say so and stop retrying.
- `/sk test` sends **hardcoded fake** `EntityContext` data, not real observations. A passing `/sk test` proves the API pipeline works, not that real player data flows through.
- When you add behavior, add or extend a `/sk` debug command so the owner can trigger and inspect it in-game.
- End every task with a short "how to test in-game" checklist.

## Owner's environment gotchas (for advice you give, not this sandbox)

- After changing env vars on Windows: `.\gradlew --stop`, then fully restart IntelliJ.
- Stale Loom caches (`.gradle\loom-cache`, `launch.cfg`) cause run-config weirdness after moving the project. Check the `-Dfabric.dli.config=` VM arg.
- The API key must be scoped to a single workspace, or you get an `anthropic-workspace-id` error.

## Build order (current)

1. ~~Look-detection utility~~ (done)
2. **Glue layer**: tick loop + fear-tier state machine wiring the AI core to `LookDetectionUtil` / `PlayerActivityTracker` / the world ← next
3. Geography shift system
4. Flickering lights + ambient drone audio
5. Stalking presence entity (audio-first)
6. Rules-based director polish (cooldowns, escalation)
7. Advancement-triggered mutations (vanilla hook first, then a custom hidden advancement tab)
