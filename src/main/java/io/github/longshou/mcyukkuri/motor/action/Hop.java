package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactResolver;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.Occupancy;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Jump to a foot target: solve the lowest arc that clears the sweep, crouch to the take-off marker, apply one impulse and let vanilla fly it, resolving host contact each tick. */
public final class Hop implements Action {
    public static final String ID = "hop";
    /** The clip whose take-off marker starts the flight. */
    private static final String CLIP = "hop";
    /** Used when the exported clip data does not author {@code takeoff}. */
    private static final int TAKEOFF_FALLBACK = 4;
    /** Air ticks allowed past the solved arc before the hop settles where it actually is. */
    static final int SETTLE = 10;
    /** Feet this far under a host surface still count as level with it. */
    private static final double TOUCH = .05;

    private final Vec3 target;
    private final List<SurfaceHost> hosts = new ArrayList<>();
    private ArcSolver.Plan plan;
    private BodyAbility ability;
    private Vec3 from;
    private int takeoff;
    private int crouch;
    private int air;
    private double peak;
    private boolean launched;
    private long startTick;

    public Hop(Vec3 target) {
        if (target == null) throw new IllegalArgumentException("Hop needs a target");
        this.target = target;
    }

    @Override public String id() { return ID; }

    /** How many of the nearest free hollow spots {@link #onto} asks the arc solver about. */
    private static final int ONTO_TRIES = 6;

    /** The free spot in {@code host}'s hollow nearest the body that a hop lands on from here (world), or null: the inside cells with room, nearest first, the first the solver takes. */
    public static Vec3 onto(YukkuriEntity body, SurfaceHost host) {
        var grid = host.walkGrid(body, true);
        var frame = host.frame();
        double foot = Occupancy.foot(body.getBbWidth());
        var spots = new ArrayList<Vec3>();
        for (int j = 0; j < grid.nz(); j++) for (int i = 0; i < grid.nx(); i++) {
            var height = grid.supported(i, j) ? host.support(new Vec3(grid.centreX(i), 0, grid.centreZ(j)), foot) : null;
            if (height == null || height.isEmpty()) continue;
            var at = frame.toWorld(new Vec3(grid.centreX(i), height.getAsDouble(), grid.centreZ(j)));
            if (Occupancy.at(host.container(), at, body.getBbWidth(), true) == Occupancy.INSIDE_SUPPORTED) spots.add(at);
        }
        spots.sort(Comparator.comparingDouble(p -> p.distanceToSqr(body.position())));
        for (int k = 0; k < spots.size() && k < ONTO_TRIES; k++) if (new Hop(spots.get(k)).check(body).ok()) return spots.get(k);
        return null;
    }

    /** Pure: solves the arc over the block and host sweep. The plan is kept for {@link #start}. */
    @Override public Feasibility check(YukkuriEntity body) {
        ability = BodyAbility.of(body);
        from = body.position();
        hosts.clear();
        for (Entity entity : body.level().getEntities(body, sweep(body),
                e -> e instanceof SurfaceHost host && host.steady())) hosts.add((SurfaceHost) entity);
        ArcSolver.Result result = ArcSolver.lowest(vec(from), vec(target), ability.hopHeight(), ability.hopReach(),
                ability.scale(), points -> clear(body, points));
        if (result instanceof ArcSolver.Plan solved) {
            plan = solved;
            return Feasibility.pass();
        }
        ArcSolver.Blocked blocked = (ArcSolver.Blocked) result;
        plan = null;
        Reason reason = blocked.kind() == ArcSolver.Kind.TOO_FAR ? Reason.TOO_FAR : Reason.TOO_HIGH;
        return Feasibility.of(reason, blocked.shortfall(), String.format(Locale.ROOT,
                "dx=%.2f dy=%.2f hopHeight=%.2f hopReach=%.2f", from.subtract(target).horizontalDistance(),
                target.y - from.y, ability.hopHeight(), ability.hopReach()));
    }

    /** The clip's take-off marker in ticks: the crouch a hop and a small hop share before their one impulse. */
    static int takeoffTicks() { return ClipMarkers.of(CLIP).ticks("takeoff", TAKEOFF_FALLBACK); }

    @Override public void start(YukkuriEntity body) {
        takeoff = takeoffTicks();
        crouch = 0;
        air = 0;
        peak = 0;
        launched = false;
        startTick = body.level().getGameTime();
    }

    @Override public Progress tick(YukkuriEntity body) {
        if (!launched) {
            crouch++;
            if (plan == null) return Outcome.of(Reason.TOO_HIGH, 0, "no_arc");
            if (crouch < takeoff) return new Running(metric(), TravelMode.CONSUMED);
            // A body standing on a host leaves it at the take-off tick, then takes the impulse from where it stands.
            if (Attach.of(body) instanceof AttachState.OnHost) Motor.dismount(body, body.position(), Vec3.ZERO, "hop_takeoff");
            Motor.velocity(body, new Vec3(plan.vh() * plan.dir().x(), plan.vy(), plan.vh() * plan.dir().z()), "hop_takeoff");
            launched = true;
            return new Running(metric(), TravelMode.PASS);
        }
        air++;
        peak = Math.max(peak, body.getY() - from.y);
        ContactResolver.Contact contact = ContactResolver.resolve(body);
        if (contact != null && contact.refused()) return Outcome.of(Reason.NO_ROOM, 0, "mount_refused");
        if (contact != null) return new Outcome.Done("contact at=" + GameplayLog.at(body.position()));
        if (body.isInWater()) return new Outcome.Done("water at=" + GameplayLog.at(body.position()));
        if (body.onGround()) return new Outcome.Done("landed at=" + GameplayLog.at(body.position()));
        if (air > plan.ticks() + SETTLE) return new Outcome.Done("settled at=" + GameplayLog.at(body.position()));
        return new Running(metric(), TravelMode.PASS);
    }

    /** Before take-off the action can be abandoned with no physical consequence; after it, the flight owns the body. */
    @Override public boolean interruptible() { return !launched; }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, false); }

    @Override public String startDetail() {
        if (plan == null || ability == null || from == null) return "";
        return String.format(Locale.ROOT, "from=%s to=%s apex=%.2f vy=%.2f vh=%.2f ticks=%.0f ability=h%.2f/r%.2f/s%.2f fp=%s",
                GameplayLog.at(from), GameplayLog.at(target), plan.apex(), plan.vy(), plan.vh(), plan.ticks(),
                ability.hopHeight(), ability.hopReach(), ability.stepUp(), ability.fingerprint());
    }

    @Override public String endDetail(Outcome outcome) {
        return String.format(Locale.ROOT, "peak=%.2f airTicks=%d", peak, air);
    }

    /** Ticks left before the hop ends on its own; it only ever drops, so the watchdog never fires on a long arc. */
    private double metric() {
        double span = plan == null ? 0 : plan.ticks() + SETTLE;
        return launched ? span - air : (takeoff - crouch) + span;
    }

    /** The volume this hop can touch: the two foot points, the body box and the whole hop height. */
    private AABB sweep(YukkuriEntity body) {
        double reach = ability.hopHeight() + body.getBbHeight() + 1;
        return new AABB(from, target).inflate(reach);
    }

    /** The clearance sweep: every tick sample's body box must be free of blocks and must not pass under a host surface; the footprint is tested once, at the contact. */
    private boolean clear(YukkuriEntity body, List<Vec> points) {
        AABB base = body.getBoundingBox();
        Vec3 origin = body.position();
        for (Vec point : points) {
            Vec3 at = from.add(point.x(), point.y(), point.z());
            if (!body.level().noCollision(body, base.move(at.subtract(origin)).deflate(.002))) return false;
            for (SurfaceHost host : hosts) {
                double unit = Math.max(1E-6, host.frame().unit());
                Vec3 local = host.frame().toLocal(at);
                var height = host.support(local, 0);
                // The height field is in hat units, so the tolerance in blocks has to be expressed in the same units.
                if (height.isPresent() && local.y < height.getAsDouble() - TOUCH / unit) return false;
            }
        }
        return true;
    }

    private static Vec vec(Vec3 at) { return new Vec(at.x, at.y, at.z); }
}
