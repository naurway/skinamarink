package com.example.ai;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Optional;

/**
 * This is the piece that makes the entity "autonomous" rather than reactive.
 * Up to now, the agent has only ever responded to the current moment. A
 * demand is different: the agent sets a goal ("don't look at me for the next
 * 30 seconds"), the goal persists across many ticks with no LLM involved,
 * and ONLY WHEN IT RESOLVES (complied / violated / expired) does that outcome
 * get reported back to the agent so it can react. That's a real perceive ->
 * decide -> act -> observe-outcome loop, not just a pacing script.
 *
 * DemandTracker itself is 100% deterministic and runs every tick (cheap -
 * boolean checks against telemetry you already have). No network calls live
 * here. The LLM only touches this at the two edges: issuing the demand, and
 * being told how it resolved.
 */
public final class DemandTracker {

    public enum DemandType {
        REMAIN_STATIONARY,      // player must not move for the duration
        DO_NOT_LOOK_AT_ENTITY,  // playerIsLookingAtEntity must stay false
        STAY_IN_ROOM,           // player must not leave target_room
        EXTINGUISH_LIGHT,       // player must not have an active light source equipped
        RETURN_TO_LOCATION      // player must reach target_room before the deadline
    }

    public enum Outcome { PENDING, COMPLIED, VIOLATED, EXPIRED }

    public record ActiveDemand(
            DemandType type,
            String targetRoom,       // nullable, only used by STAY_IN_ROOM / RETURN_TO_LOCATION
            Instant issuedAt,
            Instant deadline,
            String severity          // "low" | "medium" | "high" - agent's own framing, informs punishment weight later
    ) {}

    private ActiveDemand active; // null when no demand is currently in effect

    public boolean hasActiveDemand() {
        return active != null;
    }

    public Optional<ActiveDemand> getActive() {
        return Optional.ofNullable(active);
    }

    /** Called when the agent's issue_demand tool call comes back. Only one demand active at a time. */
    public void issue(DemandType type, String targetRoom, int durationSeconds, String severity) {
        this.active = new ActiveDemand(
                type, targetRoom, Instant.now(),
                Instant.now().plusSeconds(durationSeconds), severity
        );
    }

    /**
     * Call this every tick (or every second - it's cheap) while a demand is
     * active. Returns PENDING until the demand resolves one way or another,
     * at which point the caller should clear it via `clear()` after reading
     * the outcome and feeding it back into the next agent decision call.
     *
     * `telemetry` is whatever subset of current player state is relevant -
     * pass the same kind of fields you already build for EntityContext.
     */
    public Outcome evaluate(PlayerTelemetrySnapshot telemetry) {
        if (active == null) return Outcome.PENDING;

        boolean expired = Instant.now().isAfter(active.deadline());

        boolean violated = switch (active.type()) {
            case REMAIN_STATIONARY -> !telemetry.stationary();
            case DO_NOT_LOOK_AT_ENTITY -> telemetry.lookingAtEntity();
            case STAY_IN_ROOM -> active.targetRoom() != null
                    && !active.targetRoom().equals(telemetry.currentRoom());
            case EXTINGUISH_LIGHT -> telemetry.lightSourceActive();
            case RETURN_TO_LOCATION -> false; // this one only resolves on success or expiry, not a "violate mid-way" condition
        };

        if (violated) return Outcome.VIOLATED;

        // RETURN_TO_LOCATION resolves as COMPLIED the moment the player arrives, before expiry
        if (active.type() == DemandType.RETURN_TO_LOCATION
                && active.targetRoom() != null
                && active.targetRoom().equals(telemetry.currentRoom())) {
            return Outcome.COMPLIED;
        }

        if (expired) {
            // For hold-still/avert-gaze/stay-in-room type demands, surviving to the
            // deadline without violating IS compliance. For return_to_location,
            // running out the clock without arriving is a failure to comply.
            return active.type() == DemandType.RETURN_TO_LOCATION ? Outcome.VIOLATED : Outcome.COMPLIED;
        }

        return Outcome.PENDING;
    }

    public void clear() {
        this.active = null;
    }

    /** Minimal telemetry snapshot needed to evaluate any demand type. */
    public record PlayerTelemetrySnapshot(
            boolean stationary,
            boolean lookingAtEntity,
            String currentRoom,
            boolean lightSourceActive
    ) {}

    /** Serializes current demand state for inclusion in the agent's next context payload. */
    public JsonObject toContextJson() {
        JsonObject obj = new JsonObject();
        if (active == null) {
            obj.addProperty("active", false);
            return obj;
        }
        obj.addProperty("active", true);
        obj.addProperty("type", active.type().name());
        obj.addProperty("target_room", active.targetRoom());
        obj.addProperty("severity", active.severity());
        obj.addProperty("seconds_remaining",
                Math.max(0, active.deadline().getEpochSecond() - Instant.now().getEpochSecond()));
        return obj;
    }
}
