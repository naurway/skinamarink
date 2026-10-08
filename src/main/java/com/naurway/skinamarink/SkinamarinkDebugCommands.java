package com.naurway.skinamarink;

import com.naurway.skinamarink.ai.DemandTracker;
import com.naurway.skinamarink.ai.DreadTracker;
import com.naurway.skinamarink.ai.SkinamarinkAgent;
import com.naurway.skinamarink.content.GeometryReconfigurer;
import com.naurway.skinamarink.content.SkinamarinkFx;
import com.naurway.skinamarink.entity.SkinamarinkEntity;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Debug-only commands for testing the AI agent, dread score, demand tracker,
 * and entity. Run "/sk test" in-game (or in the server console) to fire one
 * real request at Anthropic and see what the agent decided, printed
 * straight to chat - it uses the real SkinamarinkDirector context when an
 * entity is nearby (run "/sk spawn" first), falling back to a hand-built
 * fake context otherwise.
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
                                .then(Commands.literal("demand").executes(SkinamarinkDebugCommands::runDemandStatus)
                                        .then(Commands.literal("issue")
                                                .then(Commands.argument("type", StringArgumentType.word())
                                                        .then(Commands.argument("room", StringArgumentType.word())
                                                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1))
                                                                        .then(Commands.argument("severity", StringArgumentType.word())
                                                                                .executes(SkinamarinkDebugCommands::runDemandIssue)))))))
                                .then(Commands.literal("room")
                                        .then(Commands.literal("define")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                                        .executes(SkinamarinkDebugCommands::runRoomDefine)))))
                                        .then(Commands.literal("here").executes(SkinamarinkDebugCommands::runRoomHere))
                                        .then(Commands.literal("list").executes(SkinamarinkDebugCommands::runRoomList))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .executes(SkinamarinkDebugCommands::runRoomRemove)))
                                        .then(Commands.literal("reconfigure")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .then(Commands.argument("change_type", StringArgumentType.word())
                                                                .executes(SkinamarinkDebugCommands::runRoomReconfigure)))))
                                .then(Commands.literal("manifest")
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .executes(SkinamarinkDebugCommands::runManifest)))
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

        var testPlayer = source.getPlayer();
        Optional<SkinamarinkAgent.EntityContext> realContext = (SkinamarinkMod.director != null && testPlayer != null)
                ? SkinamarinkMod.director.buildContext(testPlayer, null)
                : Optional.empty();

        SkinamarinkAgent.EntityContext testContext;
        if (realContext.isPresent()) {
            testContext = realContext.get();
            source.sendSuccess(() -> Component.literal(
                    "[Skinamarink] Asking the agent for a decision (real context - entity found nearby)..."), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "[Skinamarink] Asking the agent for a decision (fake context - run /sk spawn first for a real one)..."), false);

            // Hand-built fake context, used only when no entity is nearby this
            // player yet. Real fearScore is still pulled from DreadTracker.
            JsonObject fakeActiveDemand = new JsonObject();
            fakeActiveDemand.addProperty("active", false);

            int fearScore = (SkinamarinkMod.dreadTracker != null && testPlayer != null)
                    ? SkinamarinkMod.dreadTracker.getScoreRounded(testPlayer.getUUID().toString())
                    : DreadTracker.BASELINE;

            testContext = new SkinamarinkAgent.EntityContext(
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
        }

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

    private static int runDemandStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.director == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Director isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        DemandTracker demand = SkinamarinkMod.director.demandTrackerFor(player.getUUID().toString());
        String status = demand.hasActiveDemand() ? demand.toContextJson().toString() : "(none active)";
        source.sendSuccess(() -> Component.literal("[Skinamarink] Demand: " + status), false);
        return 1;
    }

    private static int runDemandIssue(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.director == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Director isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        DemandTracker demand = SkinamarinkMod.director.demandTrackerFor(player.getUUID().toString());
        if (demand.hasActiveDemand()) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] A demand is already active - wait for it to resolve first."));
            return 0;
        }

        String typeArg = StringArgumentType.getString(ctx, "type");
        String room = StringArgumentType.getString(ctx, "room");
        int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
        String severity = StringArgumentType.getString(ctx, "severity");

        try {
            DemandTracker.DemandType type = DemandTracker.DemandType.valueOf(typeArg.toUpperCase());
            demand.issue(type, room, seconds, severity);
            source.sendSuccess(() -> Component.literal(
                    "[Skinamarink] Issued " + type + " (room=" + room + ", " + seconds + "s, " + severity + ")"), false);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Unknown demand type. Valid: " + java.util.Arrays.toString(DemandTracker.DemandType.values())));
            return 0;
        }
    }

    private static int runRoomDefine(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.roomTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Room tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
        BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");

        SkinamarinkMod.roomTracker.defineRoom(name, from, to);
        source.sendSuccess(() -> Component.literal(
                "[Skinamarink] Defined room '" + name + "' from " + from.toShortString() + " to " + to.toShortString()), false);
        return 1;
    }

    private static int runRoomHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.roomTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Room tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        String room = SkinamarinkMod.roomTracker.findRoomAt(player.blockPosition());
        source.sendSuccess(() -> Component.literal("[Skinamarink] You are in: " + room), false);
        return 1;
    }

    private static int runRoomList(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.roomTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Room tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        var zones = SkinamarinkMod.roomTracker.listRooms();
        if (zones.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[Skinamarink] No rooms defined yet."), false);
            return 1;
        }

        String listing = zones.stream()
                .map(z -> z.id() + " [" + z.minX() + "," + z.minY() + "," + z.minZ()
                        + " -> " + z.maxX() + "," + z.maxY() + "," + z.maxZ() + "]")
                .collect(Collectors.joining(", "));
        source.sendSuccess(() -> Component.literal("[Skinamarink] Rooms: " + listing), false);
        return 1;
    }

    private static int runRoomRemove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (SkinamarinkMod.roomTracker == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Room tracker isn't initialized yet - is the server fully started?"));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        boolean removed = SkinamarinkMod.roomTracker.removeRoom(name);
        if (removed) {
            source.sendSuccess(() -> Component.literal("[Skinamarink] Removed room '" + name + "'"), false);
            return 1;
        }
        source.sendFailure(Component.literal("[Skinamarink] No room named '" + name + "'"));
        return 0;
    }

    private static int runRoomReconfigure(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        String name = StringArgumentType.getString(ctx, "name");
        String changeType = StringArgumentType.getString(ctx, "change_type");

        boolean applied = GeometryReconfigurer.apply(player, changeType, name);
        if (applied) {
            source.sendSuccess(() -> Component.literal(
                    "[Skinamarink] Applied " + changeType + " in room '" + name + "'"), false);
            return 1;
        }
        source.sendFailure(Component.literal(
                "[Skinamarink] Could not apply " + changeType + " in room '" + name
                        + "' - check the room exists, the change_type is valid (remove_door, remove_window, "
                        + "relocate_window, shift_hallway_length), and an eligible block/space was found."));
        return 0;
    }

    private static int runManifest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        var player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] This command must be run by a player, not the console."));
            return 0;
        }

        String type = StringArgumentType.getString(ctx, "type");
        boolean applied = SkinamarinkFx.manifest(player, type);
        if (applied) {
            source.sendSuccess(() -> Component.literal("[Skinamarink] Manifested: " + type), false);
            return 1;
        }
        source.sendFailure(Component.literal(
                "[Skinamarink] Unknown manifestation_type '" + type + "'"));
        return 0;
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