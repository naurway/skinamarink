package com.naurway.skinamarink.ai;

import com.google.gson.JsonObject;
import com.naurway.skinamarink.SkinamarinkMod;
import com.naurway.skinamarink.content.SkinamarinkFx;
import com.naurway.skinamarink.entity.SkinamarinkEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real decision-cycle driver - builds EntityContext from live gameplay
 * and calls SkinamarinkAgent at the two decision points its own docs
 * describe: periodically (every DECISION_INTERVAL_SECONDS, subject to the
 * agent's own cooldown), and immediately when a demand resolves. Everything
 * here is deterministic and cheap; the LLM call itself stays fully async on
 * SkinamarinkAgent's side.
 *
 * Room tracking is driven by RoomTracker's designer-defined zones (see
 * /sk room define) - each tick checks every online player's block position
 * against them and updates PlayerActivityTracker's currentRoom on change,
 * recording an "entered_room:<id>" action too. A player standing outside any
 * defined zone reads "unknown", same as before any zones exist at all.
 *
 * whisper_hint/spawn_effect/loop_ambient play real content (see
 * com.naurway.skinamarink.content) aimed only at the targeted player.
 * manifest and reconfigure_geometry are still stubs (log only) - they need
 * their own content/mechanics, deliberately out of scope here.
 *
 * One real gap this does NOT solve, called out rather than faked:
 * lightSourceActive is a placeholder held-item check (torch/lantern in
 * hand), not a real "is there a flashlight/light source active" system.
 */
public final class SkinamarinkDirector {

    private static final double ENTITY_SEARCH_RADIUS = 128.0;
    private static final double LOOK_DOT_THRESHOLD = 0.85; // ~cos(32 degrees) - a fairly narrow "looking at it" cone
    private static final int STATIONARY_THRESHOLD_SECONDS = 5;
    private static final int DECISION_INTERVAL_SECONDS = 15; // periodic beat; SkinamarinkAgent's own cooldown does the real throttling

    private final Map<String, DemandTracker> demandTrackers = new ConcurrentHashMap<>();
    private int periodicTickCounter = 0;

    public DemandTracker demandTrackerFor(String playerId) {
        return demandTrackers.computeIfAbsent(playerId, id -> new DemandTracker());
    }

    /** Call once a second (e.g. every 20 server ticks) from SkinamarinkMod. */
    public void tick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerId = player.getUUID().toString();
            SkinamarinkMod.dreadTracker.tick(playerId);
            updateRoom(player);
            replayAmbientLoop(player);
            resolveDemandIfNeeded(player);
        }

        periodicTickCounter++;
        if (periodicTickCounter < DECISION_INTERVAL_SECONDS) return;
        periodicTickCounter = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String playerId = player.getUUID().toString();
            if (!demandTrackerFor(playerId).hasActiveDemand()) {
                requestDecision(player, null);
            }
        }
    }

    private void updateRoom(ServerPlayer player) {
        if (SkinamarinkMod.roomTracker == null) return;

        String playerId = player.getUUID().toString();
        String newRoom = SkinamarinkMod.roomTracker.findRoomAt(player.blockPosition());
        String previousRoom = SkinamarinkMod.activityTracker.getCurrentRoom(playerId);
        if (newRoom.equals(previousRoom)) return;

        SkinamarinkMod.activityTracker.setCurrentRoom(playerId, newRoom);
        SkinamarinkMod.activityTracker.recordAction(playerId, "entered_room:" + newRoom);
    }

    private void replayAmbientLoop(ServerPlayer player) {
        if (SkinamarinkMod.ambientLoopTracker == null) return;
        SkinamarinkMod.ambientLoopTracker.currentLoop(player.getUUID().toString())
                .ifPresent(loop -> SkinamarinkFx.playLoopBeat(player, loop));
    }

    private void resolveDemandIfNeeded(ServerPlayer player) {
        String playerId = player.getUUID().toString();
        DemandTracker demand = demandTrackerFor(playerId);
        if (!demand.hasActiveDemand()) return;

        boolean lookingAt = findNearestEntity(player).map(e -> isLookingAt(player, e)).orElse(false);

        DemandTracker.PlayerTelemetrySnapshot telemetry = new DemandTracker.PlayerTelemetrySnapshot(
                SkinamarinkMod.activityTracker.isStationary(playerId, STATIONARY_THRESHOLD_SECONDS),
                lookingAt,
                SkinamarinkMod.activityTracker.getCurrentRoom(playerId),
                isHoldingLightSource(player)
        );

        DemandTracker.Outcome outcome = demand.evaluate(telemetry);
        if (outcome == DemandTracker.Outcome.PENDING) return;

        String severity = demand.getActive().map(DemandTracker.ActiveDemand::severity).orElse("medium");
        SkinamarinkMod.dreadTracker.applyDemandOutcome(playerId, outcome, severity);
        demand.clear();

        // A demand resolving is a real decision point, not just the periodic beat.
        requestDecision(player, outcome.name());
    }

    private void requestDecision(ServerPlayer player, String lastDemandOutcome) {
        if (SkinamarinkMod.skinamarinkAgent == null) return;

        Optional<SkinamarinkAgent.EntityContext> ctx = buildContext(player, lastDemandOutcome);
        if (ctx.isEmpty()) return; // no entity near this player yet - nothing to decide about

        JsonObject memorySummary = (SkinamarinkMod.playerMemory != null)
                ? SkinamarinkMod.playerMemory.getSummaryForContext()
                : new JsonObject();

        SkinamarinkMod.skinamarinkAgent.requestDecision(ctx.get(), memorySummary, action -> applyAction(player, action));
    }

    public Optional<SkinamarinkAgent.EntityContext> buildContext(ServerPlayer player, String lastDemandOutcome) {
        Optional<SkinamarinkEntity> maybeEntity = findNearestEntity(player);
        if (maybeEntity.isEmpty()) return Optional.empty();
        SkinamarinkEntity entity = maybeEntity.get();

        String playerId = player.getUUID().toString();
        ServerLevel level = (ServerLevel) player.level();

        return Optional.of(new SkinamarinkAgent.EntityContext(
                SkinamarinkMod.dreadTracker.getScoreRounded(playerId),
                Math.sqrt(entity.distanceToSqr(player)),
                isLookingAt(player, entity),
                SkinamarinkMod.activityTracker.isStationary(playerId, STATIONARY_THRESHOLD_SECONDS),
                SkinamarinkMod.dreadTracker.secondsSinceLastEvent(playerId),
                timeOfDay(level),
                SkinamarinkMod.activityTracker.getCurrentRoom(playerId),
                SkinamarinkMod.activityTracker.getRecentActions(playerId),
                demandTrackerFor(playerId).toContextJson(),
                lastDemandOutcome
        ));
    }

    private void applyAction(ServerPlayer player, SkinamarinkAgent.AgentAction action) {
        String playerId = player.getUUID().toString();
        DreadTracker dread = SkinamarinkMod.dreadTracker;

        switch (action) {
            case SkinamarinkAgent.AgentAction.DoNothing ignored -> { /* nothing to apply */ }
            case SkinamarinkAgent.AgentAction.AdjustDread a -> dread.applyDelta(playerId, a.delta());
            case SkinamarinkAgent.AgentAction.WhisperHint a -> {
                dread.markEvent(playerId);
                recordTool(playerId, "whisper_hint", a.hintId());
                if (!SkinamarinkFx.playHint(player, a.hintId())) {
                    SkinamarinkMod.LOGGER.warn("[Skinamarink] whisper_hint: unknown hint_id '{}'", a.hintId());
                }
            }
            case SkinamarinkAgent.AgentAction.SpawnEffect a -> {
                dread.markEvent(playerId);
                recordTool(playerId, "spawn_effect", a.effectId() + "@" + a.location());
                if (!SkinamarinkFx.spawnEffect(player, a.effectId(), a.location())) {
                    SkinamarinkMod.LOGGER.warn("[Skinamarink] spawn_effect: unknown effect_id '{}'", a.effectId());
                }
            }
            case SkinamarinkAgent.AgentAction.Manifest a -> {
                dread.markEvent(playerId);
                recordTool(playerId, "manifest", a.manifestationType());
                SkinamarinkMod.LOGGER.info("[Skinamarink] manifest: {} (no manifestation table wired up yet)", a.manifestationType());
            }
            case SkinamarinkAgent.AgentAction.ReconfigureGeometry a -> {
                dread.markEvent(playerId);
                recordTool(playerId, "reconfigure_geometry", a.changeType() + " in " + a.targetRoom());
                SkinamarinkMod.LOGGER.info("[Skinamarink] reconfigure_geometry: {} in {} (not implemented yet)", a.changeType(), a.targetRoom());
            }
            case SkinamarinkAgent.AgentAction.LoopAmbient a -> {
                dread.markEvent(playerId);
                recordTool(playerId, "loop_ambient", a.loopId());
                if (!SkinamarinkFx.startAmbientLoop(player, a.loopId())) {
                    SkinamarinkMod.LOGGER.warn("[Skinamarink] loop_ambient: unknown loop_id '{}'", a.loopId());
                }
            }
            case SkinamarinkAgent.AgentAction.RecordObservation a -> {
                if (SkinamarinkMod.playerMemory != null) {
                    SkinamarinkMod.playerMemory.addConfirmedPattern(a.note(), a.confidence());
                }
            }
            case SkinamarinkAgent.AgentAction.IssueDemand a -> {
                DemandTracker demand = demandTrackerFor(playerId);
                if (demand.hasActiveDemand()) {
                    SkinamarinkMod.LOGGER.warn("[Skinamarink] Model issued a demand while one was already active - ignoring.");
                    return;
                }
                try {
                    DemandTracker.DemandType type = DemandTracker.DemandType.valueOf(a.demandType());
                    demand.issue(type, a.targetRoom(), a.durationSeconds(), a.severity());
                    recordTool(playerId, "issue_demand", a.demandType() + " in " + a.targetRoom());
                } catch (IllegalArgumentException e) {
                    SkinamarinkMod.LOGGER.warn("[Skinamarink] Model returned an unknown demand_type: {}", a.demandType());
                }
            }
        }
    }

    private void recordTool(String playerId, String tool, String detail) {
        if (SkinamarinkMod.playerMemory != null) {
            SkinamarinkMod.playerMemory.recordToolCall(tool, detail);
        }
    }

    private Optional<SkinamarinkEntity> findNearestEntity(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        List<SkinamarinkEntity> nearby = level.getEntitiesOfClass(
                SkinamarinkEntity.class, player.getBoundingBox().inflate(ENTITY_SEARCH_RADIUS));
        return nearby.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(player)));
    }

    private boolean isLookingAt(ServerPlayer player, SkinamarinkEntity entity) {
        Vec3 toEntity = entity.getEyePosition().subtract(player.getEyePosition()).normalize();
        Vec3 look = player.getViewVector(1.0f);
        return look.dot(toEntity) > LOOK_DOT_THRESHOLD && player.hasLineOfSight(entity);
    }

    private boolean isHoldingLightSource(ServerPlayer player) {
        return isLightItem(player.getMainHandItem()) || isLightItem(player.getOffhandItem());
    }

    private boolean isLightItem(ItemStack stack) {
        return stack.is(Items.TORCH) || stack.is(Items.SOUL_TORCH)
                || stack.is(Items.LANTERN) || stack.is(Items.SOUL_LANTERN);
    }

    private String timeOfDay(ServerLevel level) {
        long time = level.getDayTime() % 24000;
        if (time < 12000) return "day";
        if (time < 13500) return "dusk";
        if (time < 22500) return "night";
        return "dusk";
    }
}
