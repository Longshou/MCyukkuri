package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
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
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Draw a floating host to reachable water at the bank with continuous braid effort: 35% of stick pace, twice its energy. */
public final class Beckon implements Action {
    public static final String ID = "beckon";
    private static final String CLIP = YukkuriAction.BANK_BECKON.id();
    /** Reach in blocks; a bounded attempt lasting twelve authored cycles. */
    public static final double REACH = 3;
    private static final int MAX_TICKS = 12 * ClipMarkers.of(CLIP).length();
    /** The braid's share of stick pace; twice the stick's energy per tick. */
    private static final double BRAID = .35;
    private static final float COST = 1f / 40;
    /** How near (blocks) the host's brim comes to the water she strokes to lie at the bank on her side; how far past the body's
     *  front the braid strokes, and the farthest short of its length it looks for water there. */
    private static final double AT = .1, AHEAD = .05, AHEAD_MOST = 1;

    private final SurfaceHost host;
    private Vec3 water;
    private double pace;
    private int t;
    private long startTick;

    public Beckon(SurfaceHost host) { this.host = host; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!ContactGeometry.braided(body)) return Feasibility.of(Reason.NO_TOOL, 0, "no_braid");
        if (!(Attach.of(body) instanceof AttachState.Free free) || free.inWater() || !body.onGround()) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_ashore");
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        double far = host.entity().position().subtract(body.position()).horizontalDistance() - REACH;
        if (far > 0) return Feasibility.of(Reason.TOO_FAR, far, "host_far");
        water = water(body);
        double gap = water == null ? Double.POSITIVE_INFINITY : ContactGeometry.braidGap(body, water, true);
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(gap) ? gap : 0, water == null ? "no_water_ahead" : "water_out_of_reach");
        if (gap() <= AT) return Feasibility.of(Reason.NO_PROGRESS, 0, "at_bank_already");
        return body.life().energy() < COST ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    /** The water's surface the braid can stroke ahead of the body: the nearest one within its own reach past the front (a stand at
     *  the very edge has the water one step farther out), null when no water lies ahead. */
    private static Vec3 water(YukkuriEntity body) {
        double yaw = Math.toRadians(body.getYRot());
        var level = body.level();
        for (double out = body.getBbWidth() * Mth.SQRT_OF_TWO / 2 + AHEAD; out <= AHEAD_MOST; out += .25) {
            double x = body.getX() - Math.sin(yaw) * out, z = body.getZ() + Math.cos(yaw) * out;
            for (int dy = 0; dy >= -1; dy--) {
                var p = BlockPos.containing(x, body.getY() - .01 + dy, z);
                var fluid = level.getFluidState(p);
                if (fluid.is(FluidTags.WATER)) return new Vec3(x, p.getY() + fluid.getHeight(level, p), z);
            }
        }
        return null;
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        pace = BodyAbility.of(body).swimSpeed() * BRAID;
    }

    @Override public Progress tick(YukkuriEntity body) {
        var hull = host.entity();
        if (hull.isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        Vec3 to = water.subtract(hull.position()).multiply(1, 0, 1);
        double distance = to.length();
        double gap = gap();
        if (gap <= AT) return new Outcome.Done("at_bank at=" + GameplayLog.at(hull.position()));
        if (t >= MAX_TICKS) return Outcome.of(Reason.TOO_FAR, gap, "ticks=" + t);
        if (body.life().energy() < COST) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
        pace = BodyAbility.of(body).swimSpeed() * BRAID;
        if (!host.thrust(body, to.scale(Math.min(pace, distance) / distance), 0)) return Outcome.of(Reason.NO_SUPPORT, 0, "not_afloat");
        body.life().spendEnergy(COST); t++;
        return new Running(gap, TravelMode.CONSUMED);
    }

    /** How far (blocks) the host's brim lies from the water she strokes: 0 once its surface is over it. */
    private double gap() {
        if (host.support(host.frame().toLocal(water), 0).isPresent()) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (var p : host.rim()) best = Math.min(best, Math.hypot(p.x - water.x, p.z - water.z));
        return best;
    }

    @Override public MotionPose pose() { return new MotionPose(CLIP, startTick, true); }
    @Override public int motionSource() { return host.entity().getId(); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "host=%s distance=%.2f water=%s pace=%.3f ticks<=%d", host.entity().getUUID(),
                host.entity().position().distanceTo(water), GameplayLog.at(water), pace, MAX_TICKS);
    }

    @Override public String endDetail(Outcome outcome) { return "ticks=" + t + " paid=" + t * COST; }
}
