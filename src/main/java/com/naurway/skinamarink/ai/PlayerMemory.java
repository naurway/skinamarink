package com.naurway.skinamarink.ai;
import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * The small, persistent, per-player-per-world summary that SkinamarinkAgent
 * actually reads on every decision call. This is what makes the entity
 * "remember" you across sessions without ever shipping the full raw log to
 * the model.
 *
 * File layout: /config/skinamarink/memory/<worldSaveId>.json
 *
 * Two update paths:
 *  - compactDeterministic(): cheap counters/aggregates, run at the end of
 *    every session. No API call.
 *  - compactWithLLM(): every N sessions (configurable), take the raw log
 *    since the last LLM pass and ask the model for an updated prose-ish
 *    summary of behavioral patterns. Rare and cheap relative to per-decision
 *    calls, and the only place an LLM sees the raw log directly.
 */
public final class PlayerMemory {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final int LLM_SUMMARY_EVERY_N_SESSIONS = 5;

    private final Path memoryFile;
    private final PlayerLogger logger;
    private final String apiKey;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HttpClient http = HttpClient.newHttpClient();

    private JsonObject memory; // in-memory working copy, flushed to disk after each update

    public PlayerMemory(String worldSaveId, Path configDir, PlayerLogger logger, String apiKey) {
        this.logger = logger;
        this.apiKey = apiKey;
        Path memDir = configDir.resolve("skinamarink").resolve("memory");
        try {
            Files.createDirectories(memDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create memory directory: " + memDir, e);
        }
        this.memoryFile = memDir.resolve(worldSaveId + ".json");
        this.memory = load();
    }

    /** What SkinamarinkAgent actually pulls into request context. Keep it small. */
    public JsonObject getSummaryForContext() {
        JsonObject slim = new JsonObject();
        slim.addProperty("sessions_observed", memory.get("sessions_observed").getAsInt());
        slim.add("confirmed_patterns", memory.get("confirmed_patterns"));
        slim.add("avoided_moves", memory.get("avoided_moves"));
        slim.add("recent_tool_calls", memory.get("recent_tool_calls")); // last ~10, for anti-repetition
        if (memory.has("llm_narrative_summary")) {
            slim.add("llm_narrative_summary", memory.get("llm_narrative_summary"));
        }
        return slim;
    }

    /** Call at the end of every session. Cheap, deterministic, no network call. */
    public void compactDeterministic(int session, Map<String, Object> sessionStats) {
        int sessionsObserved = memory.get("sessions_observed").getAsInt() + 1;
        memory.addProperty("sessions_observed", sessionsObserved);

        // Roll running averages for whatever numeric stats you pass in
        // (time_underground_pct, structures_built, etc.) — simple incremental mean.
        JsonObject running = memory.getAsJsonObject("running_averages");
        for (var e : sessionStats.entrySet()) {
            if (!(e.getValue() instanceof Number num)) continue;
            double prevAvg = running.has(e.getKey()) ? running.get(e.getKey()).getAsDouble() : 0.0;
            double newAvg = prevAvg + (num.doubleValue() - prevAvg) / sessionsObserved;
            running.addProperty(e.getKey(), newAvg);
        }

        save();

        if (sessionsObserved % LLM_SUMMARY_EVERY_N_SESSIONS == 0) {
            compactWithLLM(session); // fire the rarer, richer pass
        }
    }

    /** Record a tool call for the anti-repetition tail SkinamarinkAgent reads. */
    public void recordToolCall(String tool, String detail) {
        JsonArray recent = memory.getAsJsonArray("recent_tool_calls");
        JsonObject entry = new JsonObject();
        entry.addProperty("tool", tool);
        entry.addProperty("detail", detail);
        recent.add(entry);
        while (recent.size() > 10) recent.remove(0); // keep only the last 10
        save();
    }

    /** Record a confirmed behavioral pattern (called by you, or by an LLM summarization pass). */
    public void addConfirmedPattern(String pattern, String confidence) {
        JsonArray patterns = memory.getAsJsonArray("confirmed_patterns");
        JsonObject p = new JsonObject();
        p.addProperty("pattern", pattern);
        p.addProperty("confidence", confidence);
        patterns.add(p);
        save();
    }

    // ---- The rarer, richer LLM summarization pass ----------------------

    /**
     * Every N sessions: hand the model the raw log since last summary and ask
     * for an updated compact narrative summary. This is the only place the
     * raw log touches an LLM, and it's infrequent — nothing like the
     * per-decision calls in SkinamarinkAgent.
     */
    private void compactWithLLM(int currentSession) {
        List<String> rawLines;
        try {
            rawLines = logger.readAllLines();
        } catch (IOException e) {
            return; // skip this pass silently, deterministic data is still fine
        }
        // Only send the tail — last few thousand lines max, not the whole history forever.
        int tailStart = Math.max(0, rawLines.size() - 2000);
        String rawTail = String.join("\n", rawLines.subList(tailStart, rawLines.size()));

        String prompt = """
            Here is a raw event log (JSONL) from a horror game session tracking one
            player's behavior. Write an updated 5-8 line plain-text summary of this
            player's behavioral patterns relevant to horror pacing — things like:
            do they build a fixed home or wander, how do they respond to hints
            (investigate vs ignore), what areas/routes do they return to, do they
            play cautiously or recklessly. Be specific and concrete, not generic.
            Output ONLY the summary text, no preamble.

            LOG:
            %s
            """.formatted(rawTail);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("max_tokens", 400);
        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", prompt);
        messages.add(msg);
        body.add("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        // Blocking is fine here — this runs rarely (every 5 sessions) and should
        // happen off the server thread (e.g. on session-end, in a background task),
        // never mid-gameplay.
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
            String summaryText = root.getAsJsonArray("content")
                    .get(0).getAsJsonObject().get("text").getAsString();
            memory.addProperty("llm_narrative_summary", summaryText.trim());
            memory.addProperty("llm_narrative_summary_as_of_session", currentSession);
            save();
        } catch (Exception e) {
            System.err.println("[Skinamarink] LLM summarization pass failed: " + e.getMessage());
            // deterministic memory is untouched, agent keeps working fine without this
        }
    }

    // ---- Persistence -----------------------------------------------------

    private JsonObject load() {
        if (Files.exists(memoryFile)) {
            try {
                String content = Files.readString(memoryFile, StandardCharsets.UTF_8);
                return JsonParser.parseString(content).getAsJsonObject();
            } catch (Exception e) {
                System.err.println("[Skinamarink] Failed to load memory, starting fresh: " + e.getMessage());
            }
        }
        JsonObject fresh = new JsonObject();
        fresh.addProperty("sessions_observed", 0);
        fresh.add("confirmed_patterns", new JsonArray());
        fresh.add("avoided_moves", new JsonArray());
        fresh.add("recent_tool_calls", new JsonArray());
        fresh.add("running_averages", new JsonObject());
        return fresh;
    }

    private void save() {
        try {
            Files.writeString(memoryFile, gson.toJson(memory), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Skinamarink] Failed to save memory: " + e.getMessage());
        }
    }
}
