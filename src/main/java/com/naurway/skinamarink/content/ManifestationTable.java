package com.naurway.skinamarink.content;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.Optional;

/**
 * The table manifest picks from - the entity's rarest, closest beat. Per
 * SkinamarinkAgent's system prompt this is a last resort, not a payoff, and
 * even here the entity is never rendered or seen (it stays permanently
 * invisible - see SkinamarinkEntity). Each entry briefly relocates the real
 * entity right next to the player - close enough that distanceToPlayer would
 * read as "right there" if anyone could see it - pairs it with a short,
 * plain sound and a screen effect that reads as sensed-not-seen, then pulls
 * it back away. No monster-movie stinger; restrained, not a reveal.
 */
public enum ManifestationTable {
    CLOSE_BEHIND(SoundEvents.PORTAL_TRAVEL, 0.5f, 0.7f, MobEffects.DARKNESS, 60, 0.9),
    RIGHT_IN_FRONT(SoundEvents.GLASS_BREAK, 0.4f, 0.6f, MobEffects.DARKNESS, 40, 0.75),
    COLD_PRESENCE(SoundEvents.AMBIENT_CAVE, 0.6f, 0.5f, MobEffects.NAUSEA, 60, 1.5);

    public final SoundEvent sound;
    public final float volume;
    public final float pitch;
    public final MobEffect screenEffect;
    public final int screenEffectDurationTicks;
    public final double approachDistance; // blocks from the player when the entity briefly relocates close

    ManifestationTable(SoundEvent sound, float volume, float pitch,
                        MobEffect screenEffect, int screenEffectDurationTicks, double approachDistance) {
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
        this.screenEffect = screenEffect;
        this.screenEffectDurationTicks = screenEffectDurationTicks;
        this.approachDistance = approachDistance;
    }

    public static Optional<ManifestationTable> byId(String id) {
        try {
            return Optional.of(valueOf(id.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
