package com.naurway.skinamarink;

import com.naurway.skinamarink.ai.DreadTracker;
import com.naurway.skinamarink.ai.PlayerActivityTracker;
import com.naurway.skinamarink.ai.PlayerLogger;
import com.naurway.skinamarink.ai.PlayerMemory;
import com.naurway.skinamarink.ai.SkinamarinkAgent;
import com.naurway.skinamarink.entity.SkinamarinkEntity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.level.block.DoorBlock;

public class SkinamarinkMod implements ModInitializer {
	public static final String MOD_ID = "skinamarink";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Registry entries must exist at mod-init time, not deferred to server
	// start - the registries are frozen well before SERVER_STARTED fires.
	public static final EntityType<SkinamarinkEntity> ENTITY_TYPE = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			id("entity"),
			FabricEntityTypeBuilder.create(MobCategory.MISC, SkinamarinkEntity::new)
					.dimensions(EntityDimensions.scalable(0.6f, 1.95f))
					.build()
	);

	// Public static for now so other classes can reach these easily while
	// we're getting things running. We can clean this up into something
	// less "global variable"-y later once the basics work.
	public static PlayerLogger playerLogger;
	public static PlayerMemory playerMemory;
	public static SkinamarinkAgent skinamarinkAgent;
	public static PlayerActivityTracker activityTracker;
	public static DreadTracker dreadTracker;

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");
		FabricDefaultAttributeRegistry.register(ENTITY_TYPE, SkinamarinkEntity.createAttributes());
		SkinamarinkDebugCommands.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String worldSaveId = "dev-world"; // placeholder - real per-save ID comes later
			Path configDir = FabricLoader.getInstance().getConfigDir();
			activityTracker = new PlayerActivityTracker();
			dreadTracker = new DreadTracker();
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

            // Decay every tracked player's dread once a second (20 ticks @ 20 TPS).
            final int[] dreadTickCounter = {0};
            ServerTickEvents.END_SERVER_TICK.register(srv -> {
                dreadTickCounter[0]++;
                if (dreadTickCounter[0] < 20) return;
                dreadTickCounter[0] = 0;
                for (var onlinePlayer : srv.getPlayerList().getPlayers()) {
                    dreadTracker.tick(onlinePlayer.getUUID().toString());
                }
            });

			LOGGER.info("Skinamarink AI systems initialized.");
		});
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}