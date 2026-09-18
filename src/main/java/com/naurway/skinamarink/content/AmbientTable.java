package com.naurway.skinamarink.content;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.Optional;

/**
 * The table loop_ambient picks from. There's no true server-driven looping
 * audio without a custom looping sound asset, so this is approximated by
 * replaying the same one-shot sound once a second for durationSeconds - see
 * AmbientLoopTracker, which SkinamarinkDirector ticks once a second.
 */
public enum AmbientTable {
    OLD_TV_STATIC(SoundEvents.PORTAL_AMBIENT, 0.15f, 1.4f, 25),
    CAVE_HUM(SoundEvents.AMBIENT_CAVE, 0.2f, 1.0f, 30),
    SOFT_KNOCKING(SoundEvents.WOODEN_DOOR_CLOSE, 0.2f, 0.6f, 20);

    public final SoundEvent sound;
    public final float volume;
    public final float pitch;
    public final int durationSeconds;

    AmbientTable(SoundEvent sound, float volume, float pitch, int durationSeconds) {
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
        this.durationSeconds = durationSeconds;
    }

    public static Optional<AmbientTable> byId(String id) {
        try {
            return Optional.of(valueOf(id.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
