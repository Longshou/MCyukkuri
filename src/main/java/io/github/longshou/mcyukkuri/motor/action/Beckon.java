package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.Attach;
import io.github.longshou.mcyukkuri.motor.AttachState;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.MotionPose;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Effort;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

/**
 * Draw a floating host in to the bank from the bank (招帽): standing on dry ground at the water's edge, facing it, the body leans
 * over and strokes the water with its braid toward itself ({@code boat_paddle}, its power stroke); each stroke drives the host
 * toward the stroke through {@link SurfaceHost#thrust} at the braid's share of its swim pace (AQUATIC 无桨手划: about 35% of a
 * stick's, twice its energy). Done once the host lies against the bank; a host farther than {@link #REACH}, no water within the
 * leaning braid's reach in front, no braid, or no energy is refused; still out after {@link #STROKES} strokes it is {@code TOO_FAR}.
 */
public final class Beckon implements Action {
    public static final String ID = "beckon";
    private static final String CLIP = "boat_paddle";
    /** The farthest host a stroke draws (blocks between the body and its middle); the most strokes one beckoning takes. */
    public static final double REACH = 3;
    private static final int STROKES = 12;
    /** The braid's share of a stick's stroke; a stroke's energy (twice a stick's, Paddle's .4). */
    private static final double BRAID = .35;
    private static final float COST = .8f;
    /** How near (blocks) the host's side comes to a block to lie against the bank; how far past the body's front the braid strokes. */
    private static final double AGAINST = .05, AHEAD = .05;

    private final SurfaceHost host;
    private Vec3 water;
    private double pace;
    private int t, start, end, length;
    private long startTick;

    public Beckon(SurfaceHost host) {
        if (host == null) throw new IllegalArgumentException("Beckon needs a host");
        this.host = host;
    }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!ContactGeometry.braided(body)) return Feasibility.of(Reason.NO_TOOL, 0, "no_braid");
        if (!(Attach.of(body) instanceof AttachState.Free free) || free.inWater() || !body.onGround()) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_ashore");
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        if (host.capsized()) return Feasibility.of(Reason.NO_SUPPORT, 0, "capsized");
        double far = host.entity().position().subtract(body.position()).horizontalDistance() - REACH;
        if (far > 0) return Feasibility.of(Reason.TOO_FAR, far, "host_far");
        water = water(body);
        double gap = water == null ? Double.POSITIVE_INFINITY : ContactGeometry.braidGap(body, water, true);
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(gap) ? gap : 0, water == null ? "no_water_ahead" : "water_out_of_reach");
        return body.life().energy() < COST ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    /** The water's surface just past the body's front, where the braid strokes; null when no water lies there. */
    private static Vec3 water(YukkuriEntity body) {
        double yaw = Math.toRadians(body.getYRot()), out = body.getBbWidth() / 2 + AHEAD;
        double x = body.getX() - Math.sin(yaw) * out, z = body.getZ() + Math.cos(yaw) * out;
        var level = body.level();
        for (int dy = 0; dy >= -1; dy--) {
            var p = BlockPos.containing(x, body.getY() - .01 + dy, z);
            var fluid = level.getFluidState(p);
            if (fluid.is(FluidTags.WATER)) return new Vec3(x, p.getY() + fluid.getHeight(level, p), z);
        }
        return null;
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        pace = BodyAbility.of(body).swimSpeed() * BRAID;
        var clip = ClipMarkers.of(CLIP);
        length = clip.length();
        start = clip.ticks("power_start", 4);
        end = clip.ticks("power_end", 20);
    }

    @Override public Progress tick(YukkuriEntity body) {
        var hull = host.entity();
        if (hull.isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        Vec3 to = water.subtract(hull.position()).multiply(1, 0, 1);
        double distance = to.length();
        if (against()) return new Outcome.Done(String.format(Locale.ROOT, "against_bank strokes=%d at=%s", t / Math.max(1, length), GameplayLog.at(hull.position())));
        if (t % Math.max(1, length) == 0) {
            if (t / Math.max(1, length) >= STROKES) return Outcome.of(Reason.TOO_FAR, distance, "strokes=" + STROKES);
            if (!body.life().spendEnergy(COST)) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
        }
        double power = Effort.stroke(t++, start, end, length);
        if (power > 0 && distance > 1E-4 && !host.thrust(body, to.scale(pace * power / distance), 0)) return Outcome.of(Reason.NO_SUPPORT, 0, "not_afloat");
        return new Running(distance, TravelMode.CONSUMED);
    }

    /** The host's side lies against a block (a bank) or nearly. */
    private boolean against() {
        var hull = host.entity();
        return hull.level().getBlockCollisions(hull, hull.getBoundingBox().inflate(AGAINST, 0, AGAINST)).iterator().hasNext();
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, true); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "host=%s distance=%.2f water=%s pace=%.3f strokes<=%d", host.entity().getUUID(),
                host.entity().position().distanceTo(water), GameplayLog.at(water), pace, STROKES);
    }

    @Override public String endDetail(Outcome outcome) { return "strokes=" + t / Math.max(1, length); }
}
