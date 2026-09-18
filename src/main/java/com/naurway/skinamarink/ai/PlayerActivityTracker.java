package com.naurway.skinamarink.ai;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast, in-memory "what's this player been doing lately" tracker.
 * NOT the permanent record (that's PlayerLogger's job) - just a short
 * rolling window that EntityContext gets built from each decision cycle.
 */
public final class PlayerActivityTracker {

    private static final int MAX_RECENT_ACTIONS = 10;

    private final Map<String, Deque<String>> recentActions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastMovedAtMillis = new ConcurrentHashMap<>();
    private final Map<String, String> currentRoom = new ConcurrentHashMap<>();

    public void recordAction(String playerId, String actionName) {
        Deque<String> actions = recentActions.computeIfAbsent(playerId, k -> new ArrayDeque<>());
        actions.addLast(actionName);
        while (actions.size() > MAX_RECENT_ACTIONS) {
            actions.removeFirst();
        }
    }

    public void recordMovement(String playerId) {
        lastMovedAtMillis.put(playerId, System.currentTimeMillis());
    }

    public boolean isStationary(String playerId, int thresholdSeconds) {
        Long lastMoved = lastMovedAtMillis.get(playerId);
        if (lastMoved == null) return false;
        long elapsedSeconds = (System.currentTimeMillis() - lastMoved) / 1000;
        return elapsedSeconds >= thresholdSeconds;
    }

    public void setCurrentRoom(String playerId, String roomName) {
        currentRoom.put(playerId, roomName);
    }

    public String getCurrentRoom(String playerId) {
        return currentRoom.getOrDefault(playerId, "unknown");
    }

    public List<String> getRecentActions(String playerId) {
        Deque<String> actions = recentActions.get(playerId);
        if (actions == null) return List.of();
        return new ArrayList<>(actions);
    }
}