package com.naurway.skinamarink.content;

import com.naurway.skinamarink.SkinamarinkMod;
import com.naurway.skinamarink.entity.SkinamarinkEntity;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;

/**
 * Plays the content-table entries for whisper_hint/spawn_effect/loop_ambient
 * /manifest. Everything here is aimed at ONE specific player -
 * playToPlayer (a sound packet sent only to this player's connection),
 * addEffect, and the per-player sendParticles overload all only affect that
 * player's client, matching SkinamarinkAgent's "aimed at THIS player
 * specifically" design rather than broadcasting to everyone nearby.
 *
 * MC 26.2 notes: ServerPlayer#playNotifySound no longer exists, so sounds go
 * out as a ClientboundSoundPacket; per-player sendParticles takes two booleans
 * (overrideLimiter, alwaysShow) before the coordinates.
 */
public final class SkinamarinkFx {

    private static final double ENTITY_SEARCH_RADIUS = 128.0;

    private SkinamarinkFx() {}

    /** Returns false if hintId doesn't match anything in HintTable (caller should log/no-op). */
    public static boolean playHint(ServerPlayer player, String hintId) {
        Optional<HintTable> hint = HintTable.byId(hintId);
        if (hint.isEmpty()) return false;
        HintTable h = hint.get();
        playToPlayer(player, h.sound, h.volume, h.pitch);
        return true;
    }

    /** Returns false if effectId doesn't match anything in EffectTable (caller should log/no-op). */
    public static boolean spawnEffect(ServerPlayer player, String effectId, String location) {
        Optional<EffectTable> effect = EffectTable.byId(effectId);
        if (effect.isEmpty()) return false;
        EffectTable e = effect.get();

        Vec3 pos = resolveLocation(player, location);
        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(player, e.particle, true, false,
                pos.x(), pos.y(), pos.z(), e.count, e.spread, e.spread, e.spread, 0.01);
        return true;
    }

    /** Returns false if loopId doesn't match anything in AmbientTable (caller should log/no-op). */
    public static boolean startAmbientLoop(ServerPlayer player, String loopId) {
        Optional<AmbientTable> loop = AmbientTable.byId(loopId);
        if (loop.isEmpty()) return false;
        SkinamarinkMod.ambientLoopTracker.start(player.getUUID().toString(), loop.get());
        playLoopBeat(player, loop.get()); // first beat immediately, rather than waiting up to a second
        return true;
    }

    /** Called once a second by SkinamarinkDirector while a loop is active for this player. */
    public static void playLoopBeat(ServerPlayer player, AmbientTable loop) {
        playToPlayer(player, loop.sound, loop.volume, loop.pitch);
    }

    /**
     * Returns false if manifestationType doesn't match anything in
     * ManifestationTable (caller should log/no-op). The entity is never
     * rendered - this briefly relocates it close, plays a sound and a
     * sensed-not-seen screen effect for the player, then pulls it back away
     * in the same call. There's nothing for the player to "catch" even
     * mid-effect, since it's permanently invisible regardless of distance.
     */
    public static boolean manifest(ServerPlayer player, String manifestationType) {
        Optional<ManifestationTable> manifestation = ManifestationTable.byId(manifestationType);
        if (manifestation.isEmpty()) return false;
        ManifestationTable m = manifestation.get();

        Optional<SkinamarinkEntity> entity = findNearestEntity(player);

        entity.ifPresent(e -> {
            Vec3 close = player.position().subtract(player.getViewVector(1.0f).scale(m.approachDistance));
            e.setPos(close.x(), close.y(), close.z());
        });

        playToPlayer(player, m.sound, m.volume, m.pitch);
        player.addEffect(new MobEffectInstance(m.screenEffect, m.screenEffectDurationTicks, 0));

        entity.ifPresent(e -> {
            Vec3 far = player.position().add(randomFarOffset());
            e.setPos(far.x(), far.y(), far.z());
        });

        return true;
    }

    /** Plays a sound at the player's position that only this player hears. */
    private static void playToPlayer(ServerPlayer player, Holder<SoundEvent> sound, float volume, float pitch) {
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.AMBIENT,
                player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
    }

    private static Optional<SkinamarinkEntity> findNearestEntity(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        return level.getEntitiesOfClass(SkinamarinkEntity.class, player.getBoundingBox().inflate(ENTITY_SEARCH_RADIUS))
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    }

    private static Vec3 randomFarOffset() {
        double angle = Math.random() * Math.PI * 2;
        double distance = 60 + Math.random() * 20;
        return new Vec3(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
    }

    private static Vec3 resolveLocation(ServerPlayer player, String location) {
        return switch (location) {
            case "behind_player" -> player.position().subtract(player.getViewVector(1.0f).scale(2.5));
            case "last_room" -> lastRoomCenter(player).orElse(player.position());
            default -> player.position(); // "near_player" and anything unrecognized
        };
    }

    private static Optional<Vec3> lastRoomCenter(ServerPlayer player) {
        if (SkinamarinkMod.roomTracker == null || SkinamarinkMod.activityTracker == null) return Optional.empty();
        String roomId = SkinamarinkMod.activityTracker.getCurrentRoom(player.getUUID().toString());
        return SkinamarinkMod.roomTracker.get(roomId).map(zone -> new Vec3(
                (zone.minX() + zone.maxX()) / 2.0,
                (zone.minY() + zone.maxY()) / 2.0,
                (zone.minZ() + zone.maxZ()) / 2.0
        ));
    }
}
