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
- **`SkinamarinkDirector`** — the real decision-cycle driver. Once a second it
  decays dread, re-evaluates every player's active demand against real
  telemetry (stationary, looking-at-entity via view-vector + line-of-sight,
  held-light-source), and calls the agent at the two decision points that
  matter: periodically (every 15s, subject to the agent's own cooldown) and
  immediately when a demand resolves. It also applies whatever comes back —
  dread deltas, demand issuance, `record_observation` into `PlayerMemory`,
  and `recordToolCall` for the anti-repetition list.
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
- **`RoomTracker`** — named cuboid regions ("rooms") that the map designer
  defines by hand via `/sk room define`, persisted per world-save. Not
  automatic physical room detection — for a hand-built liminal-space
  structure, the designer already knows where the rooms are, and
  `STAY_IN_ROOM`/`RETURN_TO_LOCATION`/`reconfigure_geometry`'s `target_room`
  all want a stable, named place to refer to. `SkinamarinkDirector` checks
  every online player's position against these zones once a second and
  updates `PlayerActivityTracker`'s current room on change.
- **`content` package** (`HintTable`, `EffectTable`, `AmbientTable`,
  `AmbientLoopTracker`, `SkinamarinkFx`) — the real payloads behind
  `whisper_hint`, `spawn_effect`, and `loop_ambient`. Each table maps an id
  to a stock vanilla sound/particle (no custom audio assets yet — easy to
  swap in real ones later without touching the agent or its schema). All
  playback is aimed at just the targeted player (`playNotifySound` /
  the per-player `sendParticles` overload), never broadcast to everyone
  nearby. `spawn_effect`'s `location` resolves to the player's position,
  ~2.5 blocks behind their facing, or their current room's center (via
  `RoomTracker`) for `near_player`/`behind_player`/`last_room`.
  `loop_ambient` isn't a true server-driven audio loop (no looping sound
  asset exists) — it's approximated by replaying the same sound once a
  second for a set duration, tracked per-player in `AmbientLoopTracker` and
  ticked by `SkinamarinkDirector`. `SkinamarinkAgent`'s tool schema lists
  the tables' valid ids directly (generated from the enums), so the model
  reliably picks real content instead of inventing ids.

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

- `/sk test` — fires one real request at the agent. Uses the real
  `SkinamarinkDirector` context if an entity is nearby (`/sk spawn` it
  first), otherwise falls back to a hand-built fake one. Prints the
  resulting decision to chat.
- `/sk activity` — dumps the calling player's tracked room, stationary
  status, and recent actions.
- `/sk spawn` — spawns the invisible entity at your position, for testing
  positional logic.
- `/sk dread` — prints your current dread score and the thresholds.
- `/sk dread adjust <delta>` — nudges your dread score, for testing gating
  without waiting on real events.
- `/sk demand` — prints your currently active demand, if any.
- `/sk demand issue <type> <room> <seconds> <severity>` — manually issues a
  demand (e.g. `/sk demand issue REMAIN_STATIONARY none 20 medium`), for
  testing `DemandTracker` resolution without waiting on the agent to issue
  one itself. `room` is only used by `STAY_IN_ROOM`/`RETURN_TO_LOCATION`.
- `/sk room define <name> <from> <to>` — defines a named cuboid room from
  two block-position corners (e.g. `/sk room define kitchen ~ ~ ~ ~10 ~5 ~8`).
- `/sk room here` — prints which defined room you're currently standing in
  (`unknown` if none).
- `/sk room list` — lists all defined rooms and their bounds.
- `/sk room remove <name>` — deletes a defined room.

## Status

Early WIP. The agent, dread score, memory, demand-tracking, room-tracking,
entity, decision-cycle driver, and hint/effect/ambient content are all wired
up and testable via the debug commands — `whisper_hint`, `spawn_effect`, and
`loop_ambient` now actually play a sound or spawn particles for the targeted
player instead of only logging. What's still open:

- **`manifest` and `reconfigure_geometry` are still stubs** (log only) —
  they need their own content/mechanics (the signature "geometry drift"
  mechanic in particular hasn't been started).
- **`lightSourceActive` is a placeholder** — a held-torch/lantern check, not
  a real flashlight/light-source mechanic.
- **Vanilla placeholder audio.** The content tables use stock vanilla
  `SoundEvents`/`ParticleTypes` since the mod has no custom sound assets
  yet — the ids are stable, so swapping in real recorded audio later won't
  touch the agent or `SkinamarinkDirector`.

## License

This project is available under the CC0 license.
