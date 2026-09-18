package com.naurway.skinamarink.entity;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * The entity itself - never rendered, never heard moving, permanently
 * invisible with no way to see through it. It exists purely as a position
 * and line-of-sight anchor (EntityContext.distanceToPlayer,
 * playerIsLookingAtEntity, and the target of "near_player"/"behind_player"
 * spawn_effect placement). All actual pacing decisions come from
 * SkinamarinkAgent + DreadTracker; this class carries no behavior of its own
 * beyond existing silently in the world. In the spirit of the film, it is
 * never the payoff - the wrongness around it is.
 */
public class SkinamarinkEntity extends PathfinderMob {

    public SkinamarinkEntity(EntityType<? extends SkinamarinkEntity> type, Level level) {
        super(type, level);
        this.setInvisible(true);
        this.setSilent(true);
        this.noCulling = true;
        this.setCustomNameVisible(false);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 1.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        // No sound as itself - anything audible comes from whisper_hint/loop_ambient.
        return null;
    }

    @Override
    public boolean isInvisibleTo(Player player) {
        return true;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }
}
