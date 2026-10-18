package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import io.github.longshou.mcyukkuri.body.RemainsRot;
import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * The living side of death (docs/INJURIES_AND_REMAINS.md, "临终遗言、墓访与献身"). Komicolle's overview ends on the dying wish "还想继续慢慢来……"
 * and the 'dying words' of a yukkuri; the rest below is project design.
 * <ul>
 * <li>Last words: {@link #dying} hooks {@code BodyLife.DYING_LISTENERS}. A dying individual with kin within ten blocks says its last words to
 *     them ({@code dying_kin}); the kin gather, cry and are unsettled ({@code kin_dying}).</li>
 * <li>Mourning: free kin (partner, parents, children, siblings) go to the fresh body or the grave of their dead, stand there for a while, then
 *     leave a little eased. A grave is visited again after {@code graveVisitInterval} (two game days).</li>
 * <li>Sacrifice (simplified): an adult whose own child is the target of a chasing predator draws the chase onto itself.</li>
 * </ul>
 */
public final class GriefLife extends Goal {
    private enum Kind { NONE, BODY, GRAVE }
    private final YukkuriEntity entity;
    /** Dead individual -> game time of the last visit. */
    private final Map<UUID, Long> visited = new HashMap<>();
    private Kind kind = Kind.NONE;
    private BlockPos spot;
    private RemainsEntity body;
    private UUID deadId;
    private boolean arrived;
    private int elapsed, stayed, scanWait, sacrificeWait, sacrificeCheck;
    private String lastReason = "idle";

    public GriefLife(YukkuriEntity entity) {
        this.entity = entity; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        scanWait = 200 + entity.getRandom().nextInt(400);
    }

    private ServerLevel level() { return (ServerLevel) entity.level(); }
    public boolean active() { return kind != Kind.NONE; }
    public boolean visiting() { return kind != Kind.NONE && arrived; }
    public String describe() {
        return "kind=" + kind.name().toLowerCase() + " arrived=" + arrived + " stayed=" + stayed + " visits=" + visited.size() + " eligible=" + eligible() + " last=" + lastReason;
    }
    /** Testing door: scan for a visit on the next tick, ignoring the previous visits. */
    public void forceVisit() { visited.clear(); scanWait = 0; }
    private boolean free() {
        Identity id = entity.status().identity();
        return id == Identity.WILD || id == Identity.STRAY || id == Identity.SETTLER;
    }
    private boolean eligible() {
        return entity.isAlive() && !entity.isNoAi() && !entity.isPanicking() && !entity.isCommandPlaying() && free()
                && entity.family().stage() != FamilyLife.Stage.BABY && !entity.life().isSleeping() && !entity.life().hasUrgentNeed()
                && !entity.body().dying() && !entity.operations().active() && !entity.groupInteraction().active() && !entity.dailyLife().active()
                && !entity.playLife().active() && !entity.family().active() && !entity.isInWaterOrBubble() && !entity.mind().nonYukkuri()
                && !entity.life().isSleeping();
    }

    // ---- last words ------------------------------------------------------------------------------------------------------------
    /** BodyLife.DYING_LISTENERS: called once when a body starts dying. */
    public static void dying(YukkuriEntity dying, String cause, int ticks) {
        if (!(dying.level() instanceof ServerLevel level)) return;
        var kin = level.getEntitiesOfClass(YukkuriEntity.class, dying.getBoundingBox().inflate(10, 3, 10),
                other -> other != dying && other.isAlive() && !other.body().dying() && !other.life().isSleeping() && kinOf(level, dying, other) && other.hasLineOfSight(dying))
                .stream().sorted(Comparator.comparingDouble(dying::distanceToSqr)).toList();
        if (kin.isEmpty()) return;
        // The 'dying' line (wanting to take it easy a little longer) is already on its way; the words to the kin follow it.
        dying.showReactionDelayed("blank_cry", "dying_kin", ticks, 28);
        MCYukkuri.LOGGER.info("[grief] {} #{} says last words to {} kin ({})", dying.getVariant().assetId(), dying.getId(), kin.size(), cause);
        int n = 0;
        for (var other : kin) {
            if (n++ >= 4) break;
            other.mind().add(6, "kin_dying");
            other.showReactionDelayed("teary", "kin_dying", 100, 50 + n * 6);
            if (other.distanceToSqr(dying) > 6) other.getNavigation().moveTo(dying, 1.1);
        }
    }
    /** Partner, parent, child or sibling by recorded care relations. */
    static boolean kinOf(ServerLevel level, YukkuriEntity a, YukkuriEntity b) {
        return FamilyData.get(level).socialKin(a.getUUID(), b.getUUID()) || b.getUUID().equals(a.family().partner()) || a.getUUID().equals(b.family().partner());
    }
    private boolean kinOfDead(UUID dead, CompoundTag data) {
        return FamilyData.get(level()).socialKin(entity.getUUID(), dead) || dead.equals(entity.family().partner())
                || data.hasUUID("Partner") && entity.getUUID().equals(data.getUUID("Partner"));
    }

    // ---- sacrifice and the grief clock ---------------------------------------------------------------------------------------------
    /** Every tick from the entity. */
    public void clock() {
        if (entity.level().isClientSide) return;
        if (scanWait > 0) scanWait--;
        if (sacrificeWait > 0) { sacrificeWait--; return; }
        if (++sacrificeCheck < 10) return;
        sacrificeCheck = 0;
        if (!YukkuriConfig.SACRIFICE.get() || !entity.isAlive() || !entity.family().adult() || entity.body().dying() || entity.life().isSleeping()
                || entity.getVariant().isPredator() || entity.getVariant().isHouse() || entity.isNoAi()) return;
        for (var predator : level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(14, 4, 14), p -> p.getVariant().isPredator() && p.predator().active())) {
            var prey = predator.predator().prey();
            if (prey == null || prey == entity || !isMyChild(prey)) continue;
            if (entity.getRandom().nextInt(2) != 0) { sacrificeWait = 100; return; }
            if (predator.predator().divert(entity)) {
                sacrificeWait = 3600;
                entity.showReaction("angry", "sacrifice", 100);
                MCYukkuri.LOGGER.info("[grief] {} #{} draws {} #{} away from its child #{}", entity.getVariant().assetId(), entity.getId(), predator.getVariant().assetId(), predator.getId(), prey.getId());
                return;
            }
        }
    }
    private boolean isMyChild(YukkuriEntity other) {
        return entity.getUUID().equals(other.family().mother()) || entity.getUUID().equals(other.family().father());
    }

    // ---- visits ------------------------------------------------------------------------------------------------------------------
    @Override public boolean canUse() {
        if (active()) return true;
        if (!YukkuriConfig.GRAVE_VISITS.get() || scanWait > 0 || !eligible()) return false;
        scanWait = 600 + entity.getRandom().nextInt(600);
        long now = level().getGameTime();
        long interval = YukkuriConfig.GRAVE_VISIT_INTERVAL.get();
        for (var remains : level().getEntitiesOfClass(RemainsEntity.class, entity.getBoundingBox().inflate(16, 4, 16))) {
            var data = remains.data();
            if (!data.hasUUID("Identity") || remains.stage() != RemainsRot.FRESH) continue;
            UUID id = data.getUUID("Identity");
            if (!kinOfDead(id, data) || recently(id, now, interval) || !entity.life().reachable(remains.blockPosition(), false)) continue;
            return begin(Kind.BODY, remains.blockPosition(), id, remains);
        }
        if (!hasDeadKin()) { lastReason = "no dead kin"; return false; }
        BlockPos origin = entity.blockPosition();
        BlockPos best = null; UUID bestId = null; double bestDist = 1e9;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-20, -3, -20), origin.offset(20, 3, 20))) {
            if (!level().getBlockState(pos).is(ModBlocks.GRAVE.get()) || !(level().getBlockEntity(pos) instanceof GraveBlockEntity grave)) continue;
            UUID id = grave.buried();
            if (id == null || !kinOfDead(id, grave.buriedData()) || recently(id, now, interval)) continue;
            double d = pos.distSqr(origin);
            if (d < bestDist) { best = pos.immutable(); bestId = id; bestDist = d; }
        }
        if (best == null) { lastReason = "no grave of kin in reach"; return false; }
        return begin(Kind.GRAVE, best, bestId, null);
    }
    private boolean recently(UUID id, long now, long interval) { Long last = visited.get(id); return last != null && now - last < interval; }
    /** Cheap guard so living kin without any dead relative never scan blocks. */
    private boolean hasDeadKin() {
        var data = FamilyData.get(level());
        UUID self = entity.getUUID();
        var person = data.person(self);
        if (person == null) return false;
        for (UUID kin : new UUID[]{person.mother, person.father, entity.family().partner()}) {
            var other = kin == null ? null : data.person(kin);
            if (other != null && other.status == FamilyData.Status.DEAD) return true;
        }
        for (UUID child : data.children(self)) {
            var other = data.person(child);
            if (other != null && other.status == FamilyData.Status.DEAD) return true;
        }
        return false;
    }
    private boolean begin(Kind next, BlockPos at, UUID id, RemainsEntity remains) {
        kind = next; spot = at; deadId = id; body = remains; arrived = false; elapsed = 0; stayed = 0; lastReason = next.name().toLowerCase();
        return true;
    }
    @Override public boolean canContinueToUse() {
        if (kind == Kind.NONE || !eligible() || elapsed > 1800) return false;
        if (kind == Kind.BODY && (body == null || !body.isAlive())) return false;
        return !arrived || stayed < YukkuriConfig.GRAVE_VISIT_STAY.get();
    }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { entity.life().interrupt(); entity.social().stop(); }
    @Override public void tick() {
        elapsed++;
        entity.getLookControl().setLookAt(spot.getX() + .5, spot.getY() + .2, spot.getZ() + .5);
        if (!arrived) {
            if (entity.blockPosition().distSqr(spot) <= 6) {
                arrived = true;
                entity.getNavigation().stop();
                visited.put(deadId, level().getGameTime());
                entity.mind().add(3, "grief");
                entity.showReaction("teary", "grave_visit", YukkuriConfig.GRAVE_VISIT_STAY.get());
                MCYukkuri.LOGGER.info("[grief] {} #{} mourns at the {} of {}", entity.getVariant().assetId(), entity.getId(), kind == Kind.BODY ? "body" : "grave", deadId);
            } else if (elapsed % 10 == 1 && !entity.life().navigate(spot, 1, false)) {
                lastReason = "cannot reach"; visited.put(deadId, level().getGameTime() - YukkuriConfig.GRAVE_VISIT_INTERVAL.get() / 2); kind = Kind.NONE;
            }
            return;
        }
        entity.getNavigation().stop();
        stayed++;
    }
    @Override public void stop() {
        if (arrived && stayed >= YukkuriConfig.GRAVE_VISIT_STAY.get()) {
            entity.mind().add(-8, "grief_eased");
            entity.showReaction("neutral", "grave_leave", 80);
            MCYukkuri.LOGGER.info("[grief] {} #{} leaves the {} of {}", entity.getVariant().assetId(), entity.getId(), kind == Kind.BODY ? "body" : "grave", deadId);
        }
        kind = Kind.NONE; spot = null; body = null; deadId = null; arrived = false;
        entity.getNavigation().stop();
        scanWait = Math.max(scanWait, 200);
    }

    // ---- save --------------------------------------------------------------------------------------------------------------------
    public CompoundTag save() {
        var tag = new CompoundTag(); var list = new ListTag();
        long now = entity.level().getGameTime();
        visited.forEach((id, at) -> { var e = new CompoundTag(); e.putUUID("Dead", id); e.putLong("Ago", Math.max(0, now - at)); list.add(e); });
        tag.put("Visited", list); return tag;
    }
    public void load(CompoundTag tag) {
        visited.clear(); long now = entity.level().getGameTime();
        for (var raw : tag.getList("Visited", 10)) { var e = (CompoundTag) raw; if (e.hasUUID("Dead")) visited.put(e.getUUID("Dead"), now - e.getLong("Ago")); }
    }
}
