package com.naurway.skinamarink.content;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player "currently looping" ambient sound state - see AmbientTable. */
public final class AmbientLoopTracker {

    private record ActiveLoop(AmbientTable loop, Instant endsAt) {}

    private final Map<String, ActiveLoop> active = new ConcurrentHashMap<>();

    public void start(String playerId, AmbientTable loop) {
        active.put(playerId, new ActiveLoop(loop, Instant.now().plusSeconds(loop.durationSeconds)));
    }

    /** The loop to replay this tick, if one is still active for this player; clears it once expired. */
    public Optional<AmbientTable> currentLoop(String playerId) {
        ActiveLoop loop = active.get(playerId);
        if (loop == null) return Optional.empty();
        if (Instant.now().isAfter(loop.endsAt())) {
            active.remove(playerId);
            return Optional.empty();
        }
        return Optional.of(loop.loop());
    }
}
