package com.example.mixin;

import com.example.ExampleMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    @Inject(method = "award", at = @At("RETURN"))
    private void skinamarink$onAward(AdvancementHolder advancement, String criterionName,
                                     CallbackInfoReturnable<Boolean> cir) {
        // award() returns true only when this criterion completion actually
        // newly granted the advancement - false on repeat/no-op calls.
        if (!cir.getReturnValue()) return;

        PlayerAdvancements self = (PlayerAdvancements) (Object) this;
        ServerPlayer player = ((PlayerAdvancementsAccessor) self).getPlayer();
        if (player == null || ExampleMod.activityTracker == null) return;

        String playerId = player.getUUID().toString();
        String advancementId = advancement.id().toString();
        ExampleMod.activityTracker.recordAction(playerId, "advancement:" + advancementId);
    }
}