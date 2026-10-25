package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.life.TravelRange;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/**
 * Dry-area routines may route around an exposed gap; hunger and danger can release the preference.
 * A walker that stops making progress with another yukkuri in front of it first steps aside, then re-plans around
 * that body, and finally asks an idle blocker to make room. Bodies never close the path finder for good.
 */
final class YukkuriGroundNavigation extends GroundPathNavigation {
    private static final int WINDOW = 15, REPLAN_GAP = 60;
    private Vec3 sample;
    private long sampleTick, lastReplan;
    private int blockedWindows;
    private Set<BlockPos> avoidCells = Set.of();

    YukkuriGroundNavigation(YukkuriEntity animal, Level level) { super(animal, level); }

    @Override protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy) {
        return super.createPath(targets, margin, above, accuracy, TravelRange.PATH_RANGE);
    }
    @Override protected PathFinder createPathFinder(int nodes) {
        nodeEvaluator = new WalkNodeEvaluator() {
            private boolean keepDry, keepHome;
            @Override public void prepare(PathNavigationRegion region, Mob mob) {
                super.prepare(region, mob);
                keepDry = mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal);
                keepHome = mob instanceof YukkuriEntity animal && TravelRange.keepBabyHome(animal);
            }
            @Override protected PathType getCachedPathType(int x, int y, int z) {
                PathType type = super.getCachedPathType(x, y, z);
                if (!avoidCells.isEmpty() && avoidCells.contains(new BlockPos(x, y, z))) return PathType.BLOCKED;
                if (keepHome && mob instanceof YukkuriEntity animal
                        && !TravelRange.babyDestination(animal,new BlockPos(x,y,z))) return PathType.BLOCKED;
                if (keepDry && mob instanceof YukkuriEntity animal
                        && Reactions.Weather.rainAt(animal, new BlockPos(x, y, z))) return PathType.BLOCKED;
                return type;
            }
        };
        nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(nodeEvaluator, Math.max(nodes, TravelRange.PATH_RANGE * 16));
    }

    @Override public void tick() {
        if (mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal) && path != null && !path.isDone()) {
            var next = path.getNextNodePos();
            if (Reactions.Weather.rainAt(animal, next)) stop();
        }
        if (mob instanceof YukkuriEntity animal && !animal.level().isClientSide) watchBodyBlock(animal);
        super.tick();
    }

    private void resetBlock() { sample = null; blockedWindows = 0; }

    /** Samples progress every WINDOW ticks; only a stalled walker with a body ahead escalates, so nothing runs per tick. */
    private void watchBodyBlock(YukkuriEntity animal) {
        long now = animal.level().getGameTime();
        if (path == null || path.isDone() || !animal.onGround() || animal.isPassenger() || animal.isCommandPlaying()) {
            resetBlock();
            return;
        }
        if (sample == null) { sample = animal.position(); sampleTick = now; return; }
        if (now - sampleTick < WINDOW) return;
        double moved = Math.hypot(animal.getX() - sample.x, animal.getZ() - sample.z);
        sample = animal.position(); sampleTick = now;
        if (moved >= (animal.isCrawling() ? .02 : .12)) { blockedWindows = 0; return; }
        Vec3 heading = heading(animal);
        YukkuriEntity blocker = bodyAhead(animal, heading);
        if (blocker == null) { blockedWindows = 0; return; }
        int stage = ++blockedWindows;
        String action;
        if (stage == 1 || stage == 4) action = sidestep(animal, blocker, heading) ? "sidestep" : "no_room";
        else if (stage == 2) action = replan(animal, blocker, now) ? "replanned" : "no_alternative";
        else action = yieldRequest(animal, blocker, heading) ? "blocker_yields" : "blocker_busy";
        if (stage >= 4) blockedWindows = 0;
        GameplayLog.limited(animal, "move", "body_block_" + action, 100,
                "blocked by yukkuri {} #{} action={} window={} target={}",
                blocker.getUUID(), blocker.getId(), action, stage, getTargetPos());
    }

    private Vec3 heading(YukkuriEntity animal) {
        Vec3 next = Vec3.atBottomCenterOf(path.getNextNodePos());
        Vec3 flat = new Vec3(next.x - animal.getX(), 0, next.z - animal.getZ());
        if (flat.lengthSqr() < 1e-4) flat = new Vec3(animal.getLookAngle().x, 0, animal.getLookAngle().z);
        return flat.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : flat.normalize();
    }

    /** The nearest living yukkuri right in front of the walker, ignoring partners it is meant to reach. */
    private YukkuriEntity bodyAhead(YukkuriEntity animal, Vec3 heading) {
        if (path.getNodeCount() - path.getNextNodeIndex() <= 1) return null;
        BlockPos end = path.getEndNode().asBlockPos();
        YukkuriEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (YukkuriEntity other : animal.level().getEntitiesOfClass(YukkuriEntity.class,
                animal.getBoundingBox().inflate(.6, .1, .6),
                o -> o != animal && o.isAlive() && !o.isPassenger() && !animal.isPassengerOfSameVehicle(o))) {
            if (animal.careApproach().cooperatingWith(other) || other.family().crew().holdsBody()) continue;
            if (other.blockPosition().distSqr(end) <= 4) continue;
            double dx = other.getX() - animal.getX(), dz = other.getZ() - animal.getZ(), distance = Math.hypot(dx, dz);
            double touching = (animal.getBbWidth() + other.getBbWidth()) * .5;
            boolean ahead = distance < touching * .5 || (dx * heading.x + dz * heading.z) / Math.max(distance, 1e-4) > .25;
            if (ahead && distance < touching + .5 && distance < bestDistance) { best = other; bestDistance = distance; }
        }
        return best;
    }

    private static Vec3 right(Vec3 heading) { return new Vec3(-heading.z, 0, heading.x); }

    /** One short walking step to the freer side (own right first, so opposing traffic parts instead of mirroring). */
    private boolean sidestep(YukkuriEntity animal, YukkuriEntity blocker, Vec3 heading) {
        Vec3 right = right(heading);
        double span = (animal.getBbWidth() + blocker.getBbWidth()) * .6;
        for (int sign : new int[]{1, -1}) {
            Vec3 shift = right.scale(sign * span);
            var box = animal.getBoundingBox().move(shift.x, 0, shift.z);
            if (!animal.level().noCollision(animal, box)) continue;
            if (!isStableDestination(BlockPos.containing(animal.getX() + shift.x, animal.getY(), animal.getZ() + shift.z))) continue;
            animal.nudge(right.x * sign * .1, right.z * sign * .1);
            return true;
        }
        return false;
    }

    /** Rebuilds the route with the blocker's cells closed; the old route is kept when no other one reaches. */
    private boolean replan(YukkuriEntity animal, YukkuriEntity blocker, long now) {
        BlockPos target = getTargetPos();
        if (target == null || now - lastReplan < REPLAN_GAP) return false;
        lastReplan = now;
        var box = blocker.getBoundingBox();
        Set<BlockPos> cells = new HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(BlockPos.containing(box.minX, blocker.getY(), box.minZ),
                BlockPos.containing(box.maxX, blocker.getY(), box.maxZ))) cells.add(cell.immutable());
        cells.remove(animal.blockPosition());
        avoidCells = cells;
        Path detour;
        try { detour = createPath(target, 1); } finally { avoidCells = Set.of(); }
        if (detour == null || !detour.canReach()) return false;
        moveTo(detour, speedModifier);
        return true;
    }

    /** An idle, awake blocker moves out of the way; sleeping, busy or boarded ones keep their place. */
    private boolean yieldRequest(YukkuriEntity animal, YukkuriEntity blocker, Vec3 heading) {
        if (!blocker.getNavigation().isDone() || blocker.isCommandPlaying() || blocker.operations().active()
                || blocker.life().isSleeping() || !blocker.nudgeable()) return false;
        Vec3 right = right(heading);
        double side = Math.signum((blocker.getX() - animal.getX()) * right.x + (blocker.getZ() - animal.getZ()) * right.z);
        if (side == 0) side = 1;
        blocker.nudge(right.x * side * .16, right.z * side * .16);
        return true;
    }
}
