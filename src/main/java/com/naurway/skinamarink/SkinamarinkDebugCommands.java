package com.naurway.skinamarink;

import com.naurway.skinamarink.ai.DreadTracker;
import com.naurway.skinamarink.ai.SkinamarinkAgent;
import com.naurway.skinamarink.entity.SkinamarinkEntity;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Debug-only commands for testing the AI agent, dread score, and entity
 * without needing the real decision-cycle driver built yet. Run "/sk test"
 * in-game (or in the server console) to fire one real request at Anthropic
 * and see what the agent decided, printed straight to chat.
 */
public final class SkinamarinkDebugCommands {

    private SkinamarinkDebugCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("sk")
                                .then(Commands.literal("test").executes(SkinamarinkDebugCommands::runTest))
                                .then(Commands.literal("activity").executes(SkinamarinkDebugCommands::runActivity))
                                .then(Commands.literal("spawn").executes(SkinamarinkDebugCommands::runSpawn))
                                .then(Commands.literal("dread").executes(SkinamarinkDebugCommands::runDread)
                                        .then(Commands.literal("adjust")
                                                .then(Commands.argument("delta", IntegerArgumentType.integer())
                                                        .executes(SkinamarinkDebugCommands::runDreadAdjust))))
                )
        );
    }

    private static int runActivity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.activityTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Activity tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        String playerId = player.getUUID().toString();
        var recent = SkinamarinkMod.activityTracker.getRecentActions(playerId);
        String room = SkinamarinkMod.activityTracker.getCurrentRoom(playerId);
        boolean stationary = SkinamarinkMod.activityTracker.isStationary(playerId, 5);

        String actionsText = recent.isEmpty() ? "(none recorded yet)" : String.join(", ", recent);

        source.sendSuccess(() -> Component.literal(
                "[Skinamarink] Room: " + room
                        + " | Stationary(5s+): " + stationary
                        + " | Recent actions: " + actionsText), false);

        return 1;
    }

    private static int runTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.skinamarinkAgent == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Agent isn't initialized yet - is the server fully started?"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[Skinamarink] Asking the agent for a decision..."), false);

        // Hand-built fake context - stand-in for what the real decision-cycle
        // driver will eventually build every cycle. Real fearScore is pulled
        // from DreadTracker if a player is running the command; everything
        // else here is still a placeholder.
        JsonObject fakeActiveDemand = new JsonObject();
        fakeActiveDemand.addProperty("active", false);

        var testPlayer = source.getPlayer();
        int fearScore = (SkinamarinkMod.dreadTracker != null && testPlayer != null)
                ? SkinamarinkMod.dreadTracker.getScoreRounded(testPlayer.getUUID().toString())
                : DreadTracker.BASELINE;

        SkinamarinkAgent.EntityContext testContext = new SkinamarinkAgent.EntityContext(
                fearScore,                                    // fearScore
                8.0,                                          // distanceToPlayer
                false,                                        // playerIsLookingAtEntity
                true,                                         // playerIsStationary
                90,                                           // secondsSinceLastEvent
                "night",                                      // timeOfDay
                "test_room",                                  // lastRoom
                java.util.List.of("opened_door", "backtracked"), // recentPlayerActions
                fakeActiveDemand,                              // activeDemand
                null                                           // lastDemandOutcome
        );

        JsonObject memorySummary = (SkinamarinkMod.playerMemory != null)
                ? SkinamarinkMod.playerMemory.getSummaryForContext()
                : new JsonObject();

        SkinamarinkMod.skinamarinkAgent.requestDecision(testContext, memorySummary, action -> {
            String description = describe(action);
            source.sendSuccess(() -> Component.literal("[Skinamarink] Decision: " + description), false);
        });

        return 1;
    }

    private static int runSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        SkinamarinkEntity entity = new SkinamarinkEntity(SkinamarinkMod.ENTITY_TYPE, level);
        entity.setPos(player.getX(), player.getY(), player.getZ());
        level.addFreshEntity(entity);

        source.sendSuccess(() -> Component.literal(
                "[Skinamarink] Spawned an invisible entity at your position."), false);
        return 1;
    }

    private static int runDread(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.dreadTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Dread tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        int score = SkinamarinkMod.dreadTracker.getScoreRounded(player.getUUID().toString());
        source.sendSuccess(() -> Component.literal("[Skinamarink] Dread: " + score
                + " (whisper/effect at " + DreadTracker.WHISPER_AND_EFFECT_THRESHOLD
                + ", reconfigure_geometry at " + DreadTracker.RECONFIGURE_GEOMETRY_THRESHOLD
                + ", manifest at " + DreadTracker.MANIFEST_THRESHOLD + ")"), false);
        return 1;
    }

    private static int runDreadAdjust(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.dreadTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Dread tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        int delta = IntegerArgumentType.getInteger(ctx, "delta");
        SkinamarinkMod.dreadTracker.applyDelta(player.getUUID().toString(), delta);
        int score = SkinamarinkMod.dreadTracker.getScoreRounded(player.getUUID().toString());
        source.sendSuccess(() -> Component.literal("[Skinamarink] Dread adjusted by " + delta + " -> " + score), false);
        return 1;
    }

    private static String describe(SkinamarinkAgent.AgentAction action) {
        return switch (action) {
            case SkinamarinkAgent.AgentAction.DoNothing a ->
                    "do_nothing (" + a.reason() + ")";
            case SkinamarinkAgent.AgentAction.AdjustDread a ->
                    "adjust_dread " + a.delta() + " (" + a.reason() + ")";
            case SkinamarinkAgent.AgentAction.WhisperHint a ->
                    "whisper_hint: " + a.hintId();
            case SkinamarinkAgent.AgentAction.SpawnEffect a ->
                    "spawn_effect: " + a.effectId() + " @ " + a.location();
            case SkinamarinkAgent.AgentAction.Manifest a ->
                    "manifest: " + a.manifestationType();
            case SkinamarinkAgent.AgentAction.ReconfigureGeometry a ->
                    "reconfigure_geometry: " + a.changeType() + " in " + a.targetRoom();
            case SkinamarinkAgent.AgentAction.LoopAmbient a ->
                    "loop_ambient: " + a.loopId();
            case SkinamarinkAgent.AgentAction.RecordObservation a ->
                    "record_observation: " + a.note() + " (" + a.confidence() + ")";
            case SkinamarinkAgent.AgentAction.IssueDemand a ->
                    "issue_demand: " + a.demandType() + " in " + a.targetRoom()
                            + " for " + a.durationSeconds() + "s (" + a.severity() + ")";
        };
    }
}