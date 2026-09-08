package com.example.ai;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only JSONL event log, one file per world-save. This is ground truth —
 * nothing here is ever rewritten or deleted. It is NOT read by the live agent
 * on every decision (too big, too slow); it exists so PlayerMemory has raw
 * material to compact, and so you can build debug tooling (e.g. `/sk log`)
 * independent of anything the AI ever decided.
 *
 * File layout: /config/skinamarink/logs/<worldSaveId>.jsonl
 * One JSON object per line, e.g.:
 *   {"ts":"2026-08-29T22:03:11Z","session":3,"event":"block_placed","biome":"cave","y":41}
 */
public final class PlayerLogger {

    private final Path logFile;
    private final Gson gson = new Gson();
    private final ReentrantLock writeLock = new ReentrantLock(); // guard concurrent appends

    public PlayerLogger(String worldSaveId, Path configDir) {
        Path logsDir = configDir.resolve("skinamarink").resolve("logs");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create log directory: " + logsDir, e);
        }
        this.logFile = logsDir.resolve(worldSaveId + ".jsonl");
    }

    /** Fire-and-forget append. Never throws into caller — logging must never crash gameplay. */
    public void log(int session, String event, JsonObject fields) {
        JsonObject line = new JsonObject();
        line.addProperty("ts", Instant.now().toString());
        line.addProperty("session", session);
        line.addProperty("event", event);
        for (var entry : fields.entrySet()) {
            line.add(entry.getKey(), entry.getValue());
        }

        writeLock.lock();
        try {
            Files.writeString(
                    logFile,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            // Swallow + optionally surface to your mod's logger. Never let a disk
            // hiccup take down the entity/game loop.
            System.err.println("[Skinamarink] Failed to write log line: " + e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    // --- Convenience wrappers for the events you'll want most often ---

    public void logBlockPlaced(int session, String biome, int y) {
        JsonObject f = new JsonObject();
        f.addProperty("biome", biome);
        f.addProperty("y", y);
        log(session, "block_placed", f);
    }

    public void logToolCall(int session, String toolName, String noteOrId) {
        JsonObject f = new JsonObject();
        f.addProperty("tool", toolName);
        f.addProperty("detail", noteOrId);
        log(session, "tool_call", f);
    }

    public void logSessionEnd(int session, int durationSeconds) {
        JsonObject f = new JsonObject();
        f.addProperty("duration_seconds", durationSeconds);
        log(session, "session_end", f);
    }

    /** Reads the raw log back, for debug commands or a compaction pass. */
    public java.util.List<String> readAllLines() throws IOException {
        if (!Files.exists(logFile)) return java.util.List.of();
        return Files.readAllLines(logFile, StandardCharsets.UTF_8);
    }

    /** Reads only lines belonging to a specific session — for `/sk log session N`. */
    public java.util.List<JsonObject> readSession(int session) throws IOException {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        for (String line : readAllLines()) {
            JsonObject obj = gson.fromJson(line, JsonObject.class);
            if (obj.has("session") && obj.get("session").getAsInt() == session) {
                out.add(obj);
            }
        }
        return out;
    }
}
