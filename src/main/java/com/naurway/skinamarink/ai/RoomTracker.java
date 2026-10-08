package com.naurway.skinamarink.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Named cuboid regions ("rooms") that the map/structure designer defines by
 * hand (via /sk room define), persisted per world-save. This is deliberately
 * NOT automatic physical room detection (flood-fill bounded by walls, etc.) -
 * for a hand-built liminal-space structure, the designer already knows where
 * the rooms are, and reconfigure_geometry/STAY_IN_ROOM/RETURN_TO_LOCATION all
 * want a stable, named "place" to refer to rather than a guessed one.
 *
 * File layout: /config/skinamarink/rooms/<worldSaveId>.json
 */
public final class RoomTracker {

    private final Path roomsFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final Map<String, RoomZone> zones = new ConcurrentHashMap<>();

    public record RoomZone(String id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }
    }

    public RoomTracker(String worldSaveId, Path configDir) {
        Path roomsDir = configDir.resolve("skinamarink").resolve("rooms");
        try {
            Files.createDirectories(roomsDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create rooms directory: " + roomsDir, e);
        }
        this.roomsFile = roomsDir.resolve(worldSaveId + ".json");
        load();
    }

    /** Defines (or redefines) a named region from two opposite corners - order doesn't matter. */
    public void defineRoom(String id, BlockPos a, BlockPos b) {
        RoomZone zone = new RoomZone(
                id,
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())
        );
        zones.put(id, zone);
        save();
    }

    public boolean removeRoom(String id) {
        boolean removed = zones.remove(id) != null;
        if (removed) save();
        return removed;
    }

    public Collection<RoomZone> listRooms() {
        return zones.values();
    }

    /**
     * The room containing this position, if any. Zones aren't expected to
     * overlap for a well-formed map; if they do, which one wins is
     * unspecified. Returns "unknown" (not Optional.empty()) as the standard
     * "not in any defined room" value, matching PlayerActivityTracker's
     * existing default so callers don't need two different not-found cases.
     */
    public String findRoomAt(BlockPos pos) {
        for (RoomZone zone : zones.values()) {
            if (zone.contains(pos)) return zone.id();
        }
        return "unknown";
    }

    public Optional<RoomZone> get(String id) {
        return Optional.ofNullable(zones.get(id));
    }

    // ---- Persistence -----------------------------------------------------

    private void load() {
        if (Files.exists(roomsFile)) {
            try {
                String content = Files.readString(roomsFile, StandardCharsets.UTF_8);
                List<RoomZone> loaded = gson.fromJson(content, new TypeToken<List<RoomZone>>() {}.getType());
                if (loaded != null) {
                    for (RoomZone zone : loaded) {
                        zones.put(zone.id(), zone);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Skinamarink] Failed to load rooms, starting fresh: " + e.getMessage());
            }
        }
    }

    private void save() {
        try {
            Files.writeString(roomsFile, gson.toJson(new ArrayList<>(zones.values())), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Skinamarink] Failed to save rooms: " + e.getMessage());
        }
    }
}
