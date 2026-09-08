package com.example;

import com.example.ai.SkinamarinkAgent;
import com.google.gson.JsonObject;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Debug-only commands for testing the AI agent without needing the real
 * entity/fear-tier systems built yet. Run "/sk test" in-game (or in the
 * server console) to fire one real request at Anthropic and see what the
 * agent decided, printed straight to chat.
 */
public final class SkinamarinkDebugCommands {

    private SkinamarinkDebugCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("sk")
                                .then(Commands.literal("test").executes(SkinamarinkDebugCommands::runTest))
                )
        );
    }

    private static int runTest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (ExampleMod.skinamarinkAgent == null) {
            source.sendFailure(Component.literal(
                    "[Skinamarink] Agent isn't initialized yet - is the server fully started?"));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[Skinamarink] Asking the agent for a decision..."), false);

        // Hand-built fake context - stand-in for what the real entity/fear-tier
        // system will eventually build every decision cycle.
        JsonObject fakeActiveDemand = new JsonObject();
        fakeActiveDemand.addProperty("active", false);

        SkinamarinkAgent.EntityContext testContext = new SkinamarinkAgent.EntityContext(
                "HUNTING",                                   // fearTier
                40,                                           // fearScore
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

        JsonObject memorySummary = (ExampleMod.playerMemory != null)
                ? ExampleMod.playerMemory.getSummaryForContext()
                : new JsonObject();

        ExampleMod.skinamarinkAgent.requestDecision(testContext, memorySummary, action -> {
            String description = describe(action);
            source.sendSuccess(() -> Component.literal("[Skinamarink] Decision: " + description), false);
        });

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