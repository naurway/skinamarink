package com.naurway.skinamarink.ai;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player dread score - the single continuous signal that replaces the old
 * DORMANT/AWARE/HUNTING/MANIFEST tiers. Moved by demand outcomes and the
 * agent's own adjust_dread calls, pulled back toward a resting baseline by
 * passive decay, and used to gate which tool calls are legal right now
 * (SkinamarinkAgent enforces the thresholds below server-side, not just via
 * the system prompt).
 *
 * In-session only - resets when a player (re)joins, unlike PlayerMemory's
 * cross-session data. Call tick() roughly once a second per tracked player.
 */
public final class DreadTracker {

    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 100;
    public static final int BASELINE = 15;

    // Below these thresholds the corresponding tool call is illegal and
    // SkinamarinkAgent downgrades it even if the model picks it anyway.
    public static final int WHISPER_AND_EFFECT_THRESHOLD = 25;
    public static final int RECONFIGURE_GEOMETRY_THRESHOLD = 50;
    public static final int MANIFEST_THRESHOLD = 75;

    // Fraction of the gap to BASELINE closed per tick() call - exponential decay.
    private static final double DECAY_RATE_PER_TICK = 0.015;

    private static final Map<String, Integer> VIOLATED_DELTA = Map.of("low", 8, "medium", 15, "high", 25);
    private static final int COMPLIED_DELTA = -4;
    private static final int EXPIRED_DELTA = -2;

    private final Map<String, Double> scores = new ConcurrentHashMap<>();

    public double getScore(String playerId) {
        return scores.getOrDefault(playerId, (double) BASELINE);
    }

    public int getScoreRounded(String playerId) {
        return (int) Math.round(getScore(playerId));
    }

    public void applyDelta(String playerId, double delta) {
        scores.put(playerId, clamp(getScore(playerId) + delta));
    }

    /** Call when DemandTracker.evaluate() resolves a demand - moves the score by the appropriate amount. */
    public void applyDemandOutcome(String playerId, DemandTracker.Outcome outcome, String severity) {
        switch (outcome) {
            case VIOLATED -> applyDelta(playerId, VIOLATED_DELTA.getOrDefault(severity, VIOLATED_DELTA.get("medium")));
            case COMPLIED -> applyDelta(playerId, COMPLIED_DELTA);
            case EXPIRED -> applyDelta(playerId, EXPIRED_DELTA);
            case PENDING -> { /* no-op - shouldn't be called with a non-resolved outcome */ }
        }
    }

    /** Pulls the score back toward BASELINE. Call once a second per tracked player. */
    public void tick(String playerId) {
        double current = getScore(playerId);
        scores.put(playerId, current + (BASELINE - current) * DECAY_RATE_PER_TICK);
    }

    public void reset(String playerId) {
        scores.put(playerId, (double) BASELINE);
    }

    private static double clamp(double v) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, v));
    }
}
