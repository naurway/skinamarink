# Skinamarink

A Fabric mod for Minecraft that builds a liminal-space horror entity in the
spirit of the film *Skinamarink*: absence over presence, drifting geometry,
and childlike wrongness instead of jump scares.

The entity's pacing is driven by an LLM-backed "director" (`SkinamarinkAgent`)
that is never on the hot path — it's consulted only at deliberate decision
points, runs fully async, and picks from a fixed menu of tool calls
(`do_nothing`, `adjust_dread`, `whisper_hint`, `spawn_effect`, `manifest`,
`reconfigure_geometry`, `loop_ambient`, `record_observation`, `issue_demand`).
All movement, pathfinding, and rendering stays deterministic, vanilla-style
Java; the model only chooses *which* scripted event fires and roughly *when*.
If the API call fails or times out, the mod falls back silently to a
conservative deterministic behavior — the LLM layer is flavor, never a
dependency for the mod to run.

There is no named fear tier (DORMANT/AWARE/HUNTING/MANIFEST). Pacing runs off
a single continuous `fear_score` (0–100, see `DreadTracker`) that decays
toward a resting baseline whenever nothing happens, and that gates which tool
calls are legal — bigger, rarer calls need a higher score, enforced
server-side so the model can't just ignore the gate. The entity itself is
never rendered or heard — `SkinamarinkEntity` is a real but permanently
invisible/silent entity, used only as a position/line-of-sight anchor.

## How it works

- **`SkinamarinkAgent`** — calls the Anthropic API on a cooldown, returns a
  bounded tool call describing the next pacing beat. Downgrades `manifest`/
  `reconfigure_geometry`/`whisper_hint`/`spawn_effect` calls that come back
  above the model's dread budget instead of trusting the prompt alone.
- **`DreadTracker`** — the per-player `fear_score`: moved by `adjust_dread`
  calls and demand outcomes (a violation spikes it, compliance settles it),
  decays toward baseline once a second when nothing happens, and defines the
  thresholds that unlock `whisper_hint`/`spawn_effect` (25+),
  `reconfigure_geometry` (50+), and `manifest` (75+). In-session only.
- **`SkinamarinkEntity`** — the entity itself: a real spawned entity, always
  invisible and silent, that exists purely so distance/line-of-sight/
  "behind_player" placement have something real to measure against.
- **`DemandTracker`** — deterministic state machine for the entity's
  "demands" (e.g. *don't look at me for 30 seconds*), evaluated every tick
  with no LLM involvement until the demand resolves (complied/violated/
  expired), at which point the outcome feeds back into `DreadTracker` and the
  next agent call.
- **`PlayerMemory`** — a small, persistent per-world-save summary of a
  player's behavioral patterns, so the entity "remembers" a player across
  sessions without ever shipping the raw log to the model.
- **`PlayerLogger`** — append-only JSONL ground-truth event log, used to
  build `PlayerMemory` and for debug tooling.
- **`PlayerActivityTracker`** — a fast in-memory rolling window (recent
  actions, current room, movement) used to build each decision's context.

## Setup

For general Fabric mod setup, see the [Fabric documentation](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up)
for your IDE.

The AI agent needs an Anthropic API key, read from the environment:

```
export SKINAMARINK_ANTHROPIC_KEY=sk-ant-...
```

Without it, the agent silently no-ops and the mod runs fine on its
deterministic fallback behavior alone.

### Debug commands

- `/sk test` — fires one real request at the agent with a hand-built fake
  context (using your real dread score, if any) and prints the resulting
  decision to chat.
- `/sk activity` — dumps the calling player's tracked room, stationary
  status, and recent actions.
- `/sk spawn` — spawns the invisible entity at your position, for testing
  positional logic.
- `/sk dread` — prints your current dread score and the thresholds.
- `/sk dread adjust <delta>` — nudges your dread score, for testing gating
  without waiting on real events.

## Status

Early WIP. The agent, dread score, memory, demand-tracking, and entity
layers are wired up and testable via the debug commands, but there's no
decision-cycle driver building real `EntityContext`s off live gameplay yet
(distance/line-of-sight/room tracking, demand telemetry), and no hint/effect/
manifestation/geometry-reconfiguration content behind the agent's tool calls
— they currently have nowhere to land except debug chat output and the
dread-score enforcement itself.

## License

This project is available under the CC0 license.
