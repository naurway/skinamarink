package com.naurway.skinamarink.content;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.Optional;

/**
 * The table whisper_hint picks from - short, one-shot sounds played only for
 * the targeted player. Deliberately plain and small per SkinamarinkAgent's
 * system prompt: no monster stingers, just something that reads as slightly
 * too simple or too flat to explain.
 *
 * All entries use stock vanilla SoundEvents for now - the mod has no custom
 * sound assets yet. Swap these for real recorded/authored audio later; the
 * ids (and the agent's tool schema) don't need to change when you do.
 */
public enum HintTable {
    FLOORBOARD_CREAK(SoundEvents.WOODEN_DOOR_CLOSE, 0.5f, 0.7f),
    LIGHTS_FLICKER(SoundEvents.CANDLE_EXTINGUISH, 0.6f, 1.0f),
    CHILD_HUMMING(SoundEvents.NOTE_BLOCK_PLING, 0.4f, 0.6f),
    PAGE_TURN(SoundEvents.ITEM_BOOK_PAGE_TURN, 0.5f, 1.0f),
    DISTANT_KNOCK(SoundEvents.WOODEN_DOOR_OPEN, 0.4f, 0.5f);

    public final SoundEvent sound;
    public final float volume;
    public final float pitch;

    HintTable(SoundEvent sound, float volume, float pitch) {
        this.sound = sound;
        this.volume = volume;
        this.pitch = pitch;
    }

    public static Optional<HintTable> byId(String id) {
        try {
            return Optional.of(valueOf(id.toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
