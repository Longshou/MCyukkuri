package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Get to a stand (spec 14 WP3b): walk there (around a partner body when one is named), turn to the stand's yaw, then do at
 * most one closing action there (set the hat down, take it back, wait for the partner). Arrival is the shared
 * {@link SurfaceApproach#arrived} fact and facing is {@link Face#FACE_ARRIVED}; the deadline is absolute and the caller's.
 */
public final class GoTo implements Intent {
    private final String id;
    private final YukkuriEntity body;
    private final Vec3 target;
    private final long deadline;
    private Entity avoid;
    private float yaw = Float.NaN;
    private BooleanSupplier until;
    private Supplier<Action> then;

    public GoTo(String id, YukkuriEntity body, Vec3 target, long deadline) {
        if (deadline == Long.MAX_VALUE) throw new IllegalArgumentException("GoTo needs a finite deadline");
        this.id = id;
        this.body = body;
        this.target = target;
        this.deadline = deadline;
    }

    public GoTo around(Entity other) { avoid = other; return this; }

    public GoTo facing(float value) { yaw = value; return this; }

    /** The fact that ends the intent instead of "at the stand, facing its yaw". */
    public GoTo until(BooleanSupplier fact) { until = fact; return this; }

    /** The one action done at the stand. */
    public GoTo then(Supplier<Action> action) { then = action; return this; }

    /** At the stand and, when a yaw is given, turned to it. */
    public static boolean at(YukkuriEntity body, Vec3 stand, float yaw) {
        return SurfaceApproach.arrived(body, stand)
                && (Float.isNaN(yaw) || Math.abs(Mth.wrapDegrees(yaw - body.getYRot())) <= Face.FACE_ARRIVED);
    }

    @Override public String id() { return id; }

    @Override public boolean satisfied() { return until != null ? until.getAsBoolean() : at(body, target, yaw); }

    @Override public long deadline() { return deadline; }

    /** Around the partner first, the plain route second; one method only when an action closes it (no second try of it). */
    @Override public List<Method> methods() {
        return avoid == null || then != null ? List.of(new Route(avoid)) : List.of(new Route(avoid), new Route(null));
    }

    private final class Route implements Method {
        private final Entity around;
        private boolean acted;

        private Route(Entity around) { this.around = around; }

        @Override public String id() { return around == null ? "walk" : "walk_around"; }

        @Override public Feasibility applicable() {
            return SurfaceApproach.arrived(body, target) || SurfaceApproach.canApproach(body, target)
                    ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_route");
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.NO_PATH, Reason.NO_PROGRESS); }

        @Override public Step next() {
            // Pushed off the stand: walk and turn back, and the closing action may run again from there (a wait, not a second try).
            if (!SurfaceApproach.arrived(body, target)) { acted = false; return new Step.Do(around == null ? new Walk(target) : Walk.around(target, around)); }
            if (!at(body, target, yaw)) { acted = false; return new Step.Do(new Face(yaw)); }
            if (then == null || acted) return null;
            acted = true;
            return new Step.Do(then.get());
        }
    }
}
