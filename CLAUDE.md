# Skinamarink — Minecraft horror mod

A Fabric mod inspired by the film *Skinamarink*. The fear comes from absence and wrongness: the world changes when the player isn't looking, and an unseen presence is implied through sound and screen effects, never shown. An LLM "director" (Claude API) picks which scripted beat fires and roughly when.

**Read `README.md` first.** It is the up-to-date description of every system (dread score, director, demands, rooms, content tables, geometry, manifest) and every `/sk` debug command. If you change how a system works, update the README in the same commit.

The owner is a beginner modder who playtests locally. Explain changes plainly. When you summarize your work, list every file you touched and give a step-by-step "how to test in-game" checklist using `/sk` commands.

## Stack

- Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.158.0+26.2, Loom 1.17-SNAPSHOT (see `gradle.properties`)
- Java 25 (matches `.github/workflows/build.yml`), Gradle wrapper
- Mojang mappings (`Identifier`, `ServerPlayer`, etc.), not Yarn
- Package `com.naurway.skinamarink`, mod id `skinamarink` (`SkinamarinkMod.MOD_ID`). Don't rename these.
- Owner's IDE is IntelliJ on Windows.

## Map of the code

- `SkinamarinkMod` — initializer. `SkinamarinkDebugCommands` — all `/sk` commands.
- `ai/` — `SkinamarinkAgent` (async LLM, bounded tool menu), `SkinamarinkDirector` (once-a-second decision cycle; the real glue between agent and world), `DreadTracker` (continuous 0–100 fear score that gates tool calls), `DemandTracker`, `RoomTracker` (designer-defined named zones), `PlayerActivityTracker`, `PlayerMemory`, `PlayerLogger` (write-only JSONL).
- `content/` — tables and playback behind `whisper_hint`, `spawn_effect`, `loop_ambient`, `manifest`, plus `GeometryReconfigurer` for `reconfigure_geometry`.
- `entity/SkinamarinkEntity` — real but permanently invisible and silent; only a position / line-of-sight anchor.
- `mixin/` — `PlayerAdvancementsMixin` records earned advancements; `MinecraftServerMixin`.
- Leftover: `src/main/java/com/example/util/LookDetectionUtil.java` may still exist from before the rebrand. The director has its own `isLookingAt`. Don't delete either without asking; flag duplication instead.

## Design decisions that are deliberate (don't undo them)

- **No named fear tiers.** Pacing runs off `DreadTracker`'s continuous score with server-enforced thresholds. Don't reintroduce tiers.
- **The entity is never rendered or audible as itself**, even during `manifest`.
- **`GeometryReconfigurer` never touches blocks outside the target room's zone** (except confirmed-air extension for `shift_hallway_length`). Keep that guarantee.
- **Effects play only for the targeted player**, never broadcast.

## Hard rules

1. **Never call the Claude API per tick.** Agent calls happen only at the director's decision points, async, behind the agent's cooldown. A per-tick call burns real money at 20 calls/second.
2. **The no-API path must always work.** If `SKINAMARINK_ANTHROPIC_KEY` is missing or a call fails or is slow, the mod still runs on its deterministic fallback. Never block the server thread on network I/O.
3. **The API key env var is `SKINAMARINK_ANTHROPIC_KEY`**, not `ANTHROPIC_API_KEY`. Never hardcode or commit a key.
4. **No custom shaders.** The owner's hardware can't run them. Atmosphere comes from vanilla block-state flicker, vanilla sounds and particles, vanilla status effects, and an optional third-party shaderpack.
5. **World changes happen only when the player isn't looking.**
6. Prefer data-driven tunables (JSON in `src/main/resources`) for things like advancement→mutation mappings, weights, and cooldowns.
7. Keep changes small and on a branch.

## Testing

- You can't launch Minecraft here. Try `./gradlew build` to catch compile errors. If dependency downloads are blocked in this environment, say so once and stop retrying.
- Every new behavior gets a `/sk` command so the owner can trigger and inspect it in-game without waiting on the agent.

## Owner's Windows gotchas (for advice you give, not this sandbox)

- After changing env vars: `.\gradlew --stop`, then fully restart IntelliJ.
- Stale Loom caches (`.gradle\loom-cache`, `launch.cfg`) cause run-config weirdness after moving the project. Check the `-Dfabric.dli.config=` VM arg.
- The API key must be scoped to a single workspace, or you get an `anthropic-workspace-id` error.
- The rebrand renamed the mod id, so old IntelliJ run configs may still point at `modid.*` modules. Regenerate them (`.\gradlew genSources`, then reload the Gradle project) if runs fail.

## Still open (rough order)

1. Owner playtests the current branch end to end and merges it to `main`.
2. Flickering lights (vanilla light-block state toggling while unobserved).
3. Advancement-triggered mutations: a JSON mapping from vanilla advancement ids to entity/director changes, fed by `PlayerAdvancementsMixin`. Later, a custom hidden advancement tab.
4. Offline pre-generated content pools (hint lines, demand phrasings) to reduce live API use.
