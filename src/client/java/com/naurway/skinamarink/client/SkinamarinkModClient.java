package com.naurway.skinamarink.client;

import com.naurway.skinamarink.SkinamarinkMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;

public class SkinamarinkModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Every entity type needs a renderer on the client or the game crashes
		// the moment one is spawned. The entity is never meant to be seen, so
		// it gets NoopRenderer, which draws nothing.
		EntityRendererRegistry.register(SkinamarinkMod.ENTITY_TYPE, NoopRenderer::new);
	}
}
