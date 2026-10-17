package io.github.longshou.mcyukkuri.command;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Operator-directed museum walks still use the mob's pathfinder, movement and collision. */
public final class MuseumWalkGoal extends Goal {
    private record Route(Vec3 target, boolean noAi, long deadline) {}
    private static final Map<Mob, Route> ROUTES = new WeakHashMap<>();
    private final Mob mob;
    private int repath;

    public MuseumWalkGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    public static boolean begin(Mob mob, Vec3 target) {
        if (!mob.getTags().contains("pm_actor") || !mob.isAlive() || mob.isPassenger()
                || mob.distanceToSqr(target) > 1024 || !mob.level().hasChunkAt(net.minecraft.core.BlockPos.containing(target))) return false;
        cancel(mob);
        if (mob instanceof YukkuriEntity y) y.playAction("idle");
        ROUTES.put(mob, new Route(target, mob.isNoAi(), mob.level().getGameTime() + 600));
        mob.getPersistentData().putBoolean("MuseumWalkNoAI", mob.isNoAi());
        mob.addTag("pm_walking");
        mob.removeTag("pm_walk_failed");
        mob.setNoAi(false);
        return true;
    }

    public static void cancel(Mob mob) {
        var route = ROUTES.remove(mob);
        if (route != null || mob.getTags().contains("pm_walking")) {
            mob.getNavigation().stop();
            mob.setNoAi(route != null ? route.noAi : mob.getPersistentData().getBoolean("MuseumWalkNoAI"));
            mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
        }
        mob.getPersistentData().remove("MuseumWalkNoAI");
        mob.removeTag("pm_walking");
        mob.removeTag("pm_walk_failed");
    }

    @Override public boolean canUse() {
        if (mob.getTags().contains("pm_walking") && !ROUTES.containsKey(mob)) {
            cancel(mob); // Reloaded mid-walk: do not leave the fixed actor wandering.
            mob.addTag("pm_walk_failed");
        }
        return ROUTES.containsKey(mob);
    }
    @Override public boolean canContinueToUse() { return ROUTES.containsKey(mob); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() { repath = 0; }
    @Override public void tick() {
        var route = ROUTES.get(mob);
        if (route == null) return;
        var delta = route.target.subtract(mob.position());
        if (delta.horizontalDistance() < .18 && Math.abs(delta.y) < .55) { cancel(mob); return; }
        if (!mob.isAlive() || mob.isPassenger() || mob.level().getGameTime() > route.deadline) {
            cancel(mob); mob.addTag("pm_walk_failed"); return;
        }
        if (--repath <= 0) {
            repath = 20;
            if (!mob.getNavigation().moveTo(route.target.x, route.target.y, route.target.z, .75)) {
                cancel(mob); mob.addTag("pm_walk_failed"); return;
            }
        }
        // The final fraction of a block uses MoveControl, never setPos or teleport.
        if (mob.getNavigation().isDone() && delta.horizontalDistance() < 1)
            mob.getMoveControl().setWantedPosition(route.target.x, route.target.y, route.target.z, .5);
        mob.getLookControl().setLookAt(route.target.x, mob.getEyeY(), route.target.z);
    }
    @Override public void stop() {
        if (ROUTES.containsKey(mob)) { cancel(mob); mob.addTag("pm_walk_failed"); }
    }
}
