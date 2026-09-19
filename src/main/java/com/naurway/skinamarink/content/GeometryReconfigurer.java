package com.naurway.skinamarink.content;

import com.naurway.skinamarink.SkinamarinkMod;
import com.naurway.skinamarink.ai.RoomTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The mechanics behind reconfigure_geometry - the entity's signature move
 * per SkinamarinkAgent's system prompt ("prefer reconfigure_geometry over
 * manifest by a wide margin"). Every mutation here stays strictly within the
 * target room's own RoomTracker.RoomZone bounding box, or - for
 * shift_hallway_length's extension only - into space immediately beyond it
 * that is confirmed air first. Nothing here ever overwrites a block outside
 * what the map designer explicitly claimed as this room, or a non-air block
 * the designer didn't define as part of it.
 */
public final class GeometryReconfigurer {

    // Safety cap against an accidentally huge /sk room define hanging the server thread.
    private static final int MAX_ROOM_VOLUME_FOR_SCAN = 50_000;

    private GeometryReconfigurer() {}

    /** Returns false if the change couldn't be applied (unknown room, unknown change_type, no eligible block found, etc). */
    public static boolean apply(ServerPlayer player, String changeType, String targetRoomId) {
        if (SkinamarinkMod.roomTracker == null) return false;
        Optional<RoomTracker.RoomZone> maybeZone = SkinamarinkMod.roomTracker.get(targetRoomId);
        if (maybeZone.isEmpty()) return false;
        RoomTracker.RoomZone zone = maybeZone.get();

        long volume = (long) (zone.maxX() - zone.minX() + 1)
                * (zone.maxY() - zone.minY() + 1)
                * (zone.maxZ() - zone.minZ() + 1);
        if (volume > MAX_ROOM_VOLUME_FOR_SCAN) {
            SkinamarinkMod.LOGGER.warn(
                    "[Skinamarink] reconfigure_geometry skipped - room '{}' is too large to scan safely ({} blocks)",
                    targetRoomId, volume);
            return false;
        }

        ServerLevel level = (ServerLevel) player.level();
        return switch (changeType) {
            case "remove_door" -> removeDoor(level, zone);
            case "remove_window" -> removeWindow(level, zone);
            case "relocate_window" -> relocateWindow(level, zone);
            case "shift_hallway_length" -> shiftHallwayLength(level, zone, targetRoomId);
            default -> false;
        };
    }

    private static boolean removeDoor(ServerLevel level, RoomTracker.RoomZone zone) {
        Optional<BlockPos> doorPos = findFirst(zone, pos -> level.getBlockState(pos).getBlock() instanceof DoorBlock);
        if (doorPos.isEmpty()) return false;

        BlockPos lower = lowerDoorHalf(level, doorPos.get());
        BlockState wall = adjacentWallState(level, lower);
        level.setBlock(lower, wall, 3);
        level.setBlock(lower.above(), wall, 3);
        return true;
    }

    private static boolean removeWindow(ServerLevel level, RoomTracker.RoomZone zone) {
        Optional<BlockPos> windowPos = findFirst(zone, pos -> isWindow(level.getBlockState(pos)));
        if (windowPos.isEmpty()) return false;

        level.setBlock(windowPos.get(), adjacentWallState(level, windowPos.get()), 3);
        return true;
    }

    private static boolean relocateWindow(ServerLevel level, RoomTracker.RoomZone zone) {
        Optional<BlockPos> windowPos = findFirst(zone, pos -> isWindow(level.getBlockState(pos)));
        if (windowPos.isEmpty()) return false;
        BlockPos oldPos = windowPos.get();
        BlockState oldWindowState = level.getBlockState(oldPos);

        boolean alongX = (zone.maxX() - zone.minX()) >= (zone.maxZ() - zone.minZ());
        int[] offsets = {2, -2, 3, -3, 4, -4};

        for (int offset : offsets) {
            BlockPos candidate = alongX ? oldPos.offset(offset, 0, 0) : oldPos.offset(0, 0, offset);
            if (!zone.contains(candidate)) continue;

            BlockState candidateState = level.getBlockState(candidate);
            if (candidateState.isAir() || isWindow(candidateState)
                    || candidateState.getBlock() instanceof DoorBlock) continue;

            level.setBlock(candidate, oldWindowState, 3);
            level.setBlock(oldPos, adjacentWallState(level, oldPos), 3);
            return true;
        }

        // No safe relocation spot found nearby - fall back to a plain removal
        // rather than leaving the agent's chosen action with no effect at all.
        level.setBlock(oldPos, adjacentWallState(level, oldPos), 3);
        return true;
    }

    private static boolean shiftHallwayLength(ServerLevel level, RoomTracker.RoomZone zone, String roomId) {
        boolean alongX = (zone.maxX() - zone.minX()) >= (zone.maxZ() - zone.minZ());

        int lastInteriorIndex = alongX ? zone.maxX() : zone.maxZ();
        int newIndex = lastInteriorIndex + 1;
        int otherAxisMin = alongX ? zone.minZ() : zone.minX();
        int otherAxisMax = alongX ? zone.maxZ() : zone.maxX();

        // Only extend into confirmed-empty space - never clip into something
        // the designer didn't define as part of this room.
        for (int other = otherAxisMin; other <= otherAxisMax; other++) {
            for (int y = zone.minY(); y <= zone.maxY(); y++) {
                BlockPos target = alongX ? new BlockPos(newIndex, y, other) : new BlockPos(other, y, newIndex);
                if (!level.getBlockState(target).isAir()) {
                    return false;
                }
            }
        }

        // Extrude the last interior cross-section one slice further out.
        for (int other = otherAxisMin; other <= otherAxisMax; other++) {
            for (int y = zone.minY(); y <= zone.maxY(); y++) {
                BlockPos source = alongX ? new BlockPos(lastInteriorIndex, y, other) : new BlockPos(other, y, lastInteriorIndex);
                BlockPos target = alongX ? new BlockPos(newIndex, y, other) : new BlockPos(other, y, newIndex);
                level.setBlock(target, level.getBlockState(source), 3);
            }
        }

        // The room actually grew - keep RoomTracker's bounds in sync so future
        // lookups (last_room placement, other reconfigure calls) reflect it.
        BlockPos newMin = new BlockPos(zone.minX(), zone.minY(), zone.minZ());
        BlockPos newMax = alongX
                ? new BlockPos(newIndex, zone.maxY(), zone.maxZ())
                : new BlockPos(zone.maxX(), zone.maxY(), newIndex);
        SkinamarinkMod.roomTracker.defineRoom(roomId, newMin, newMax);
        return true;
    }

    private static Optional<BlockPos> findFirst(RoomTracker.RoomZone zone, Predicate<BlockPos> predicate) {
        for (int x = zone.minX(); x <= zone.maxX(); x++) {
            for (int y = zone.minY(); y <= zone.maxY(); y++) {
                for (int z = zone.minZ(); z <= zone.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (predicate.test(pos)) return Optional.of(pos);
                }
            }
        }
        return Optional.empty();
    }

    private static BlockPos lowerDoorHalf(ServerLevel level, BlockPos doorPos) {
        BlockState state = level.getBlockState(doorPos);
        return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER ? doorPos : doorPos.below();
    }

    private static BlockState adjacentWallState(ServerLevel level, BlockPos pos) {
        BlockPos[] neighbors = {pos.north(), pos.south(), pos.east(), pos.west()};
        for (BlockPos n : neighbors) {
            BlockState state = level.getBlockState(n);
            if (!state.isAir() && !(state.getBlock() instanceof DoorBlock) && !isWindow(state)) {
                return state;
            }
        }
        return Blocks.STONE_BRICKS.defaultBlockState();
    }

    private static boolean isWindow(BlockState state) {
        Block block = state.getBlock();
        return block == Blocks.GLASS || block == Blocks.GLASS_PANE
                || block instanceof StainedGlassBlock || block instanceof StainedGlassPaneBlock;
    }
}
