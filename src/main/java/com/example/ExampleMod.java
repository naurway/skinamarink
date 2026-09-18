package com.example;

import com.example.ai.PlayerActivityTracker;
import com.example.ai.PlayerLogger;
import com.example.ai.PlayerMemory;
import com.example.ai.SkinamarinkAgent;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.level.block.DoorBlock;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Public static for now so other classes can reach these easily while
	// we're getting things running. We can clean this up into something
	// less "global variable"-y later once the basics work.
	public static PlayerLogger playerLogger;
	public static PlayerMemory playerMemory;
	public static SkinamarinkAgent skinamarinkAgent;
	public static PlayerActivityTracker activityTracker;
	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");
		SkinamarinkDebugCommands.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String worldSaveId = "dev-world"; // placeholder - real per-save ID comes later
			Path configDir = FabricLoader.getInstance().getConfigDir();
			activityTracker = new PlayerActivityTracker();
			String apiKey = System.getenv("SKINAMARINK_ANTHROPIC_KEY");
			if (apiKey == null || apiKey.isBlank()) {
				LOGGER.warn("SKINAMARINK_ANTHROPIC_KEY is not set! The AI agent will silently no-op until it is.");
			}
			playerLogger = new PlayerLogger(worldSaveId, configDir);
			playerMemory = new PlayerMemory(worldSaveId, configDir, playerLogger, apiKey);
			skinamarinkAgent = new SkinamarinkAgent(apiKey, server::execute);

            UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
                var state = world.getBlockState(hitResult.getBlockPos());
                if (state.getBlock() instanceof DoorBlock) {
                    String playerId = player.getUUID().toString();
                    activityTracker.recordAction(playerId, "opened_door");
                }
                return net.minecraft.world.InteractionResult.PASS;
            });
			LOGGER.info("Skinamarink AI systems initialized.");
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}