package com.example.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Is the player looking at X" utility - the load-bearing check that
 * geography shifts, presence gating, and advancement criteria all depend on.
 *
 * Performance note: each call does up to 3 checks in increasing cost order
 * (distance -> field-of-view -> raycast), and bails out early whenever
 * possible. Even so, DO NOT call this every tick for every candidate.
 * Throttle calls to every 5-10 ticks from whatever system uses this.
 */
public final class LookDetectionUtil {

    private LookDetectionUtil() {}

    private static final double DEFAULT_FOV_DEGREES = 90.0;

    public static boolean isLookingAtEntity(ServerPlayer player, Entity target, double maxDistance) {
        return isLookingAtEntity(player, target, maxDistance, DEFAULT_FOV_DEGREES);
    }

    public static boolean isLookingAtEntity(ServerPlayer player, Entity target, double maxDistance, double fovDegrees) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 targetPos = target.getBoundingBox().getCenter();
        return checkAll(player, eyePos, targetPos, maxDistance, fovDegrees);
    }

    public static boolean isLookingAtBlock(ServerPlayer player, BlockPos pos, double maxDistance) {
        return isLookingAtBlock(player, pos, maxDistance, DEFAULT_FOV_DEGREES);
    }

    public static boolean isLookingAtBlock(ServerPlayer player, BlockPos pos, double maxDistance, double fovDegrees) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 targetPos = Vec3.atCenterOf(pos);
        return checkAll(player, eyePos, targetPos, maxDistance, fovDegrees);
    }

    // ---- internal: cheapest check first, bail out ASAP ----

    private static boolean checkAll(ServerPlayer player, Vec3 eyePos, Vec3 targetPos, double maxDistance, double fovDegrees) {
        // 1. Distance check (cheap - no trig, no raycast)
        double distanceSq = eyePos.distanceToSqr(targetPos);
        if (distanceSq > maxDistance * maxDistance) {
            return false;
        }

        // 2. Field-of-view check (a bit of trig, still cheap)
        if (!withinFieldOfView(player, eyePos, targetPos, fovDegrees)) {
            return false;
        }

        // 3. Raycast for occlusion (the expensive one - only runs if 1 & 2 passed)
        return hasLineOfSight(player, eyePos, targetPos);
    }

    private static boolean withinFieldOfView(ServerPlayer player, Vec3 eyePos, Vec3 targetPos, double fovDegrees) {
        Vec3 lookVec = player.getViewVector(1.0F).normalize();
        Vec3 toTarget = targetPos.subtract(eyePos).normalize();

        double dot = lookVec.dot(toTarget);
        double angleDegrees = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));

        return angleDegrees <= (fovDegrees / 2.0);
    }

    private static boolean hasLineOfSight(ServerPlayer player, Vec3 from, Vec3 to) {
        Level level = player.level();
        ClipContext context = new ClipContext(
                from, to,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                player
        );
        BlockHitResult result = level.clip(context);

        if (result.getType() == HitResult.Type.MISS) {
            return true; // nothing blocking the way
        }

        // If the raycast hit something at (or past) the target's own distance,
        // that "hit" is basically the target itself, not an obstruction.
        double hitDistanceSq = from.distanceToSqr(result.getLocation());
        double targetDistanceSq = from.distanceToSqr(to);
        return hitDistanceSq >= targetDistanceSq - 0.5;
    }
}
