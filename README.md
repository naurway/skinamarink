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

## How it works

- **`SkinamarinkAgent`** — calls the Anthropic API on a cooldown, returns a
  bounded tool call describing the next pacing beat.
- **`DemandTracker`** — deterministic state machine for the entity's
  "demands" (e.g. *don't look at me for 30 seconds*), evaluated every tick
  with no LLM involvement until the demand resolves (complied/violated/
  expired), at which point the outcome feeds back into the next agent call.
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
  context and prints the resulting decision to chat.
- `/sk activity` — dumps the calling player's tracked room, stationary
  status, and recent actions.

## Status

Early WIP. The agent, memory, and demand-tracking layers are wired up and
testable via the debug commands, but there's no entity, fear-tier state
machine, or hint/effect/manifestation content behind them yet — the agent's
tool calls currently have nowhere to land except debug chat output.

## License

This project is available under the CC0 license.
