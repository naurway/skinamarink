package com.naurway.skinamarink.content;

import com.naurway.skinamarink.SkinamarinkMod;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Plays the content-table entries for whisper_hint/spawn_effect/loop_ambient.
 * Everything here is aimed at ONE specific player - playNotifySound and the
 * per-player sendParticles overload both send only to that player's client,
 * matching SkinamarinkAgent's "aimed at THIS player specifically" design
 * rather than broadcasting to everyone nearby.
 *
 * NOTE: ServerLevel#sendParticles' per-player overload signature has moved
 * around across Minecraft versions (an extra "always render" boolean was
 * added in some). If this doesn't compile against MC 26.2, that's the line
 * to fix first - the (player, particle, force, x, y, z, count, dx, dy, dz,
 * speed) 11-arg form used here is the long-standing one.
 */
public final class SkinamarinkFx {

    private SkinamarinkFx() {}

    /** Returns false if hintId doesn't match anything in HintTable (caller should log/no-op). */
    public static boolean playHint(ServerPlayer player, String hintId) {
        Optional<HintTable> hint = HintTable.byId(hintId);
        if (hint.isEmpty()) return false;
        HintTable h = hint.get();
        player.playNotifySound(h.sound, SoundSource.AMBIENT, h.volume, h.pitch);
        return true;
    }

    /** Returns false if effectId doesn't match anything in EffectTable (caller should log/no-op). */
    public static boolean spawnEffect(ServerPlayer player, String effectId, String location) {
        Optional<EffectTable> effect = EffectTable.byId(effectId);
        if (effect.isEmpty()) return false;
        EffectTable e = effect.get();

        Vec3 pos = resolveLocation(player, location);
        ServerLevel level = (ServerLevel) player.level();
        level.sendParticles(player, e.particle, true,
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
        player.playNotifySound(loop.sound, SoundSource.AMBIENT, loop.volume, loop.pitch);
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
