package com.naurway.skinamarink.ai;
import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * LLM-backed decision layer for the Skinamarink entity.
 *
 * Design principles:
 *  - The LLM is NEVER on the hot path. It is not called every tick. It is only
 *    consulted at deliberate "decision points" (a demand resolving, or every N
 *    seconds while dread is elevated), and it runs fully async off the server
 *    thread. The actual movement/pathfinding/rendering stays in your existing
 *    GeckoLib controller and vanilla-style Java entity code - deterministic,
 *    fast, no network dependency.
 *  - The LLM does not generate free text that gets parsed with regex. It is
 *    given a fixed menu of tool calls (manifest, whisper hint, adjust dread,
 *    spawn effect, do nothing) and MUST pick from that menu. This keeps the
 *    entity's behavior safe, bounded, and debuggable - the LLM is choosing
 *    WHICH scripted event to fire and roughly WHEN, not writing new behavior.
 *  - There are no fear tiers - only DreadTracker's continuous fear_score,
 *    which decays toward a resting baseline on its own. Bigger tool calls are
 *    gated by score thresholds (see DreadTracker), enforced here server-side
 *    (enforceThresholds) as well as by the system prompt, so a model that
 *    ignores the prompt's guidance still can't jump straight to a manifest.
 *  - If the API call fails, times out, or the player has no internet-dependent
 *    settings enabled, we fall back silently to a conservative deterministic
 *    default. The LLM layer is a "flavor" enhancement, never a dependency for
 *    the mod to function.
 */
public final class SkinamarinkAgent {

    // ---- Config -------------------------------------------------------

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-sonnet-4-6";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration MIN_COOLDOWN = Duration.ofSeconds(20); // never call more often than this

    private final String apiKey;
    private final HttpClient http;
    private final Executor callbackExecutor; // used to hop back onto the server thread
    private final Gson gson = new Gson();

    private Instant lastCallAt = Instant.EPOCH;

    public SkinamarinkAgent(String apiKey, Executor serverThreadExecutor) {
        this.apiKey = apiKey;
        this.callbackExecutor = serverThreadExecutor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .executor(Executors.newVirtualThreadPerTaskExecutor()) // Java 21; use a fixed pool on 17
                .build();
    }

    // ---- Public API -----------------------------------------------------

    /**
     * Snapshot of everything the agent is allowed to know about right now.
     * Keep this small and factual - no raw chat logs, no player-identifying
     * data beyond what you already track for gameplay.
     */
    public record EntityContext(
            int fearScore,                // 0-100, from DreadTracker - the only escalation signal, no tiers
            double distanceToPlayer,      // blocks
            boolean playerIsLookingAtEntity,
            boolean playerIsStationary,   // hasn't moved in last N ticks
            int secondsSinceLastEvent,    // since last manifestation/hint fired
            String timeOfDay,             // "day" | "dusk" | "night"
            String lastRoom,              // last named room/area player entered, if tracked
            List<String> recentPlayerActions, // e.g. ["opened_door", "used_flashlight", "backtracked"]
            JsonObject activeDemand,      // from DemandTracker.toContextJson() - {"active": false} if none
            String lastDemandOutcome      // "COMPLIED" | "VIOLATED" | "EXPIRED" | null - only set the call right after a demand resolves
    ) {}

    /** The bounded set of things the entity is allowed to do. */
    public sealed interface AgentAction {
        record DoNothing(String reason) implements AgentAction {}
        record AdjustDread(int delta, String reason) implements AgentAction {}
        record WhisperHint(String hintId) implements AgentAction {}       // maps to your existing hint/audio table
        record SpawnEffect(String effectId, String location) implements AgentAction {} // "near_player" | "behind_player" | "last_room"
        record Manifest(String manifestationType) implements AgentAction {} // maps to your existing manifestation table
        record ReconfigureGeometry(String changeType, String targetRoom) implements AgentAction {} // "remove_door" | "shift_hallway_length" | "relocate_window" | "remove_window"
        record LoopAmbient(String loopId) implements AgentAction {} // old-TV static / cartoon-loop audio texture, from your ambient table
        record RecordObservation(String note, String confidence) implements AgentAction {} // writes to PlayerMemory, no in-game effect
        record IssueDemand(String demandType, String targetRoom, int durationSeconds, String severity) implements AgentAction {} // sets a goal DemandTracker will watch
    }

    /**
     * Asks the agent what to do given the current context. Never blocks the
     * calling thread. `onDecision` is invoked on the executor you supplied
     * (pass your server's main-thread executor so you can safely touch world
     * state inside the callback).
     */
    public void requestDecision(EntityContext ctx, JsonObject memorySummary, java.util.function.Consumer<AgentAction> onDecision) {
        Instant now = Instant.now();
        if (Duration.between(lastCallAt, now).compareTo(MIN_COOLDOWN) < 0) {
            // Too soon - don't spam the API, just let the deterministic system run this beat.
            onDecision.accept(new AgentAction.DoNothing("cooldown"));
            return;
        }
        lastCallAt = now;

        HttpRequest request;
        try {
            request = buildRequest(ctx, memorySummary);
        } catch (Exception e) {
            onDecision.accept(fallback(ctx));
            return;
        }

        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(REQUEST_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(HttpResponse::body)
                .thenApply(body -> {
                    System.out.println("[Skinamarink DEBUG] Raw response: " + body);
                    return body;
                })
                .thenApply(this::parseAction)
                .thenApply(action -> enforceThresholds(action, ctx))
                .exceptionally(err -> {
                    err.printStackTrace();
                    return fallback(ctx);
                })
                .thenAcceptAsync(onDecision, callbackExecutor);
    }

    // ---- Request building -------------------------------------------------

    private HttpRequest buildRequest(EntityContext ctx, JsonObject memorySummary) {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("max_tokens", 300);
        body.addProperty("system", systemPrompt());

        JsonObject payload = new JsonObject();
        payload.add("current_state", gson.toJsonTree(ctx));
        payload.add("player_memory", memorySummary); // from PlayerMemory.getSummaryForContext()

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", gson.toJson(payload)); // structured context, not prose
        messages.add(userMsg);
        body.add("messages", messages);

        body.add("tools", toolSchema());
        JsonObject toolChoice = new JsonObject();
        toolChoice.addProperty("type", "any");
        body.add("tool_choice", toolChoice);
        return HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private String systemPrompt() {
        return """
            You are the pacing director for a horror entity in a Skinamarink-inspired
            liminal-space game. Core rules of this aesthetic, in priority order:

            1. ABSENCE OVER PRESENCE. The entity is never fully shown. You are not
               choosing when something appears - you are choosing when something that
               should be there is subtly missing, moved, or wrong. A door that led
               somewhere yesterday now leads somewhere else. A room the player mapped
               no longer connects the way it did. Prefer reconfigure_geometry over
               manifest by a wide margin - a visible entity is a last resort, not a
               payoff.
            2. STILLNESS IS THE INSTRUMENT. Nothing should move quickly. If you choose
               spawn_effect or manifest, prefer effects that are static, distant, or
               only noticeable in retrospect (something in a corner that wasn't there,
               that doesn't move while looked at). Never pair two showy events close
               together - let long stretches of do_nothing sit between them so a single
               wrong detail actually lands.
            3. CHILDLIKE WRONGNESS, NOT MENACE. Any hint content (audio, text, loop_ambient)
               should read as plain, small, and slightly too simple - a repeated sound,
               a flat statement of fact, something a child would say or notice - never
               a threat, a monster-movie stinger, or anything that explains itself.
               Withheld explanation is the point; do not let any tool call resolve into
               something legible as "the monster is coming."
            4. TIME AND SPACE SHOULD DRIFT, NOT SNAP. Prefer gradual changes the player
               might not consciously register (a hallway one block longer than before,
               a torch that's slightly dimmer) over sudden jump-scare-style change.
            5. FEAR_SCORE REPLACES ANY NOTION OF A NAMED STATE. There are no tiers -
               only a continuous fear_score (0-100) in the context, which decays toward
               a low resting baseline whenever nothing happens. Treat it as a budget:
               below 25, stay to do_nothing / adjust_dread / record_observation /
               loop_ambient. Once it clears 25, whisper_hint and spawn_effect are
               appropriate. Once it clears 50, reconfigure_geometry - your primary
               instrument - becomes available. manifest requires at least 75, and is
               silently downgraded server-side if you call it below that, so there is
               no benefit to choosing it speculatively - wait until demand violations
               or repeated escalation have actually earned it.

            You will receive a JSON snapshot of current state plus a persistent memory
            summary of this specific player's patterns across past sessions
            (confirmed_patterns, avoided_moves, recent_tool_calls, llm_narrative_summary),
            plus active_demand (any expectation you've currently set for the player)
            and last_demand_outcome (how a previous demand resolved, if this is the
            first call after resolution - null otherwise).

            DEMANDS ARE HOW YOU HAVE AGENCY, NOT PACING BEATS. issue_demand sets a
            concrete expectation the player must meet or fail over the following
            15-60 seconds, tracked automatically without you - you are not consulted
            again until it resolves. This is the core of who this entity is: it does
            not simply react to what the player does, it TELLS them what to do and
            responds to whether they obeyed.
              - Never call issue_demand if active_demand.active is already true - wait
                for it to resolve.
              - When you receive last_demand_outcome = VIOLATED, this must produce a
                real consequence in the same call - prefer manifest or
                reconfigure_geometry over a mild adjust_dread, in proportion to the
                severity you set when you issued it. Disobedience should cost
                something the player can point to.
              - When last_demand_outcome = COMPLIED, do not reward with anything
                positive (this entity does not reassure) - at most a brief
                do_nothing or small negative adjust_dread (things calming slightly),
                consistent with an entity that is satisfied rather than pleased.
              - Space demands out - do not issue a new one immediately after the last
                resolves. Let stillness follow both compliance and punishment alike.
              - Favor demand types your memory of this player suggests they'll
                struggle with (e.g. a player who never stays still is a good target
                for REMAIN_STATIONARY) - the entity should feel like it knows what's
                hard for THIS player specifically.

            Use the player memory (confirmed_patterns, avoided_moves, recent_tool_calls,
            llm_narrative_summary) actively:
              - Never repeat a tool+detail pair that appears in recent_tool_calls.
              - If a pattern in confirmed_patterns implies a category of event won't
                land (e.g. no fixed dwelling -> skip home-invasion-style effects,
                listed in avoided_moves), do not choose it.
              - Reference the player's own established routines (return routes, favored
                areas) as the site of wrongness rather than generic locations - the
                specificity of "this changed, and it's a place only THIS player would
                recognize as changed" is what should make it feel aimed at them.

            Choose exactly ONE tool call representing the single best next beat for
            tension. Prefer do_nothing or small adjust_dread calls far more often than
            any visible/audible tool. Never choose manifest unless fear_score is at
            least 75. Keep your reasoning implicit in the tool choice - do not add
            commentary.
            """;
    }

    private JsonArray toolSchema() {
        JsonArray tools = new JsonArray();

        tools.add(tool("do_nothing", "Let the deterministic system continue uninterrupted.",
                props(prop("reason", "string", "brief internal note, not shown to player"))));

        tools.add(tool("adjust_dread", "Nudge the numeric fear score without a visible event.",
                props(
                    prop("delta", "integer", "amount to add or subtract, typically -5 to 10"),
                    prop("reason", "string", "why")
                )));

        tools.add(tool("whisper_hint", "Trigger a subtle audio/visual hint from the existing hint table.",
                props(prop("hint_id", "string", "id from the mod's hint table, e.g. 'floorboard_creak', 'lights_flicker'"))));

        tools.add(tool("spawn_effect", "Spawn a scripted ambient effect.",
                props(
                    prop("effect_id", "string", "id from the mod's effect table"),
                    prop("location", "string", "one of: near_player, behind_player, last_room")
                )));

        tools.add(tool("manifest", "Trigger a full manifestation event. Only valid at fear_score >= 75 - calls below that are silently downgraded. Last resort - prefer reconfigure_geometry.",
                props(prop("manifestation_type", "string", "id from the mod's manifestation table"))));

        tools.add(tool("reconfigure_geometry", "Subtly alter a room/hallway's layout - a door, window, or passage that no longer matches what the player remembers. Only valid at fear_score >= 50 - calls below that are silently downgraded.",
                props(
                    prop("change_type", "string", "one of: remove_door, shift_hallway_length, relocate_window, remove_window"),
                    prop("target_room", "string", "room/area id, prefer one from the player's own return-route memory when available")
                )));

        tools.add(tool("loop_ambient", "Play a quiet looping audio texture (old static, a repeating simple sound) - not an event, a background wrongness.",
                props(prop("loop_id", "string", "id from the mod's ambient loop table"))));

        tools.add(tool("record_observation", "Write a note to persistent player memory. No in-game effect - use this often, it's how the entity 'learns' this player.",
                props(
                    prop("note", "string", "specific, concrete behavioral observation"),
                    prop("confidence", "string", "low, medium, or high")
                )));

        tools.add(tool("issue_demand", "State an expectation for the player to obey. Only valid if no demand is currently active (check active_demand in context first). This is the entity setting a goal that persists across many ticks without you - you will be told the outcome (complied/violated/expired) on a future call, and should choose the punishment/response then, not now.",
                props(
                    prop("demand_type", "string", "one of: REMAIN_STATIONARY, DO_NOT_LOOK_AT_ENTITY, STAY_IN_ROOM, EXTINGUISH_LIGHT, RETURN_TO_LOCATION"),
                    prop("target_room", "string", "required for STAY_IN_ROOM/RETURN_TO_LOCATION, empty string otherwise"),
                    prop("duration_seconds", "integer", "how long the player has to comply, typically 15-60"),
                    prop("severity", "string", "low, medium, or high - your own framing of how harshly to punish violation later")
                )));

        return tools;
    }

    private JsonObject tool(String name, String description, JsonObject inputSchema) {
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("input_schema", inputSchema);
        return t;
    }

    private JsonObject props(JsonObject... properties) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject propsObj = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonObject p : properties) {
            String name = p.get("__name").getAsString();
            p.remove("__name");
            propsObj.add(name, p);
            required.add(name);
        }
        schema.add("properties", propsObj);
        schema.add("required", required);
        return schema;
    }

    private JsonObject prop(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("__name", name);
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    // ---- Response parsing --------------------------------------------------

    private AgentAction parseAction(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();

        if (root.has("error")) {
            System.err.println("[Skinamarink] API error: " + root.get("error"));
            return new AgentAction.DoNothing("api_error");
        }

        JsonArray content = root.getAsJsonArray("content");
        if (content == null) {
            System.err.println("[Skinamarink] Unexpected response shape (no content): " + responseBody);
            return new AgentAction.DoNothing("malformed_response");
        }

        for (JsonElement el : content) {
            JsonObject block = el.getAsJsonObject();
            if (!"tool_use".equals(block.get("type").getAsString())) continue;

            String name = block.get("name").getAsString();
            JsonObject input = block.getAsJsonObject("input");

            return switch (name) {
                case "do_nothing" -> new AgentAction.DoNothing(getOr(input, "reason", ""));
                case "adjust_dread" -> new AgentAction.AdjustDread(
                        input.get("delta").getAsInt(), getOr(input, "reason", ""));
                case "whisper_hint" -> new AgentAction.WhisperHint(input.get("hint_id").getAsString());
                case "spawn_effect" -> new AgentAction.SpawnEffect(
                        input.get("effect_id").getAsString(), input.get("location").getAsString());
                case "manifest" -> new AgentAction.Manifest(input.get("manifestation_type").getAsString());
                case "reconfigure_geometry" -> new AgentAction.ReconfigureGeometry(
                        input.get("change_type").getAsString(), input.get("target_room").getAsString());
                case "loop_ambient" -> new AgentAction.LoopAmbient(input.get("loop_id").getAsString());
                case "record_observation" -> new AgentAction.RecordObservation(
                        input.get("note").getAsString(), getOr(input, "confidence", "low"));
                case "issue_demand" -> new AgentAction.IssueDemand(
                        input.get("demand_type").getAsString(),
                        getOr(input, "target_room", ""),
                        input.get("duration_seconds").getAsInt(),
                        getOr(input, "severity", "medium"));
                default -> new AgentAction.DoNothing("unknown_tool:" + name);
            };
        }
        return new AgentAction.DoNothing("no_tool_use_in_response");
    }

    private String getOr(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    // ---- Threshold enforcement -----------------------------------------------

    /**
     * Server-side guardrail backing the system prompt's fear_score gating - a
     * model that ignores the prompt still can't skip straight to a big event.
     * Downgrades are silent (the model is never told its call was denied).
     */
    private AgentAction enforceThresholds(AgentAction action, EntityContext ctx) {
        return switch (action) {
            case AgentAction.Manifest ignored when ctx.fearScore() < DreadTracker.MANIFEST_THRESHOLD ->
                    new AgentAction.AdjustDread(5, "manifest_denied_insufficient_dread");
            case AgentAction.ReconfigureGeometry ignored when ctx.fearScore() < DreadTracker.RECONFIGURE_GEOMETRY_THRESHOLD ->
                    new AgentAction.AdjustDread(3, "reconfigure_denied_insufficient_dread");
            case AgentAction.WhisperHint ignored when ctx.fearScore() < DreadTracker.WHISPER_AND_EFFECT_THRESHOLD ->
                    new AgentAction.DoNothing("whisper_denied_insufficient_dread");
            case AgentAction.SpawnEffect ignored when ctx.fearScore() < DreadTracker.WHISPER_AND_EFFECT_THRESHOLD ->
                    new AgentAction.DoNothing("effect_denied_insufficient_dread");
            default -> action;
        };
    }

    // ---- Fallback -----------------------------------------------------------

    /** Deterministic fallback if the LLM call fails - conservative, never manifests unprompted. */
    private AgentAction fallback(EntityContext ctx) {
        if (ctx.fearScore() >= DreadTracker.MANIFEST_THRESHOLD && ctx.secondsSinceLastEvent() > 45) {
            return new AgentAction.WhisperHint("floorboard_creak");
        }
        return new AgentAction.DoNothing("fallback");
    }
}
