package io.github.longshou.mcyukkuri.intent;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.action.Walk;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Walk to one world point (spec 14 WP3 step 1): the small generic "get there" intent a {@link Rendezvous} hands each
 * participant. Two methods: the ordinary shared walk, and a walk around a body the route would otherwise pass through
 * (the partner at a rendezvous). Arrival is the shared {@link SurfaceApproach#arrived} fact; the deadline is absolute and
 * supplied by the caller, so a rendezvous has exactly one.
 */
public final class GoTo implements Intent {
    public static final String ID = "go_to";

    private final YukkuriEntity body;
    private final Vec3 target;
    private final long deadline;
    private Entity avoid;

    /** Walks to {@code target} within {@code ticks} of now. */
    public GoTo(YukkuriEntity body, Vec3 target, int ticks) {
        this(body, target, now(body) + Math.max(1, ticks));
    }

    /** Walks to {@code target} by the absolute {@code deadline}. */
    public GoTo(YukkuriEntity body, Vec3 target, long deadline) {
        this.body = Objects.requireNonNull(body, "body");
        this.target = Objects.requireNonNull(target, "target");
        if (deadline == Long.MAX_VALUE) throw new IllegalArgumentException("GoTo needs a finite deadline");
        this.deadline = deadline;
    }

    /** Goes around {@code other} instead of through it; the second method needs this body. */
    public GoTo around(Entity other) {
        this.avoid = other;
        return this;
    }

    public Vec3 target() { return target; }

    @Override public String id() { return ID; }

    @Override public boolean satisfied() {
        return SurfaceApproach.arrived(body, target);
    }

    @Override public long deadline() { return deadline; }

    @Override public List<Method> methods() {
        return List.of(new WalkTo(), new WalkAround());
    }

    /** The shared ground walk; the route planner owns the whole route. */
    private final class WalkTo implements Method {
        @Override public String id() { return "walk"; }

        @Override public Feasibility applicable() {
            return SurfaceApproach.canApproach(body, target)
                    ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_route");
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.NO_PATH); }

        @Override public Step next() { return new Step.Do(new Walk(target)); }
    }

    /** The same walk, planned around the body the caller named (a rendezvous partner). */
    private final class WalkAround implements Method {
        @Override public String id() { return "walk_around"; }

        @Override public Feasibility applicable() {
            if (avoid == null) return Feasibility.of(Reason.NO_PATH, 0, "no_body_to_avoid");
            return SurfaceApproach.canApproach(body, target)
                    ? Feasibility.pass() : Feasibility.of(Reason.NO_PATH, 0, "no_route");
        }

        @Override public Set<Reason> reduces() { return Set.of(Reason.NO_PATH); }

        @Override public Step next() { return new Step.Do(Walk.around(target, avoid)); }
    }

    private static long now(YukkuriEntity body) {
        return body == null ? 0 : body.level().getGameTime();
    }
}
