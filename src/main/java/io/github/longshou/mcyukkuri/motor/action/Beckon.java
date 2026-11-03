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
import io.github.longshou.mcyukkuri.motor.Motor;
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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Draw a floating host to reachable water at the bank, or to her own braid while she swims, with continuous braid effort: 35% of
 *  stick pace, twice its energy. Only the stroke's authored contact window pushes the host; the water's own flow and drag carry it in
 *  between. */
public final class Beckon implements Action {
    public static final String ID = "beckon";
    /** Reach in blocks; a bounded attempt lasting twelve authored cycles. */
    public static final double REACH = 3;
    private static final int CYCLES = 12;
    /** The braid's share of stick pace; twice the stick's energy per tick of the stroke. */
    private static final double BRAID = .35;
    private static final float COST = 1f / 40;
    /** How near (blocks) the host's brim comes to the water she strokes to lie at the bank on her side; how far past the body's
     *  front the braid strokes, and the farthest short of its length it looks for water there. */
    private static final double AT = .1, AHEAD = .05, AHEAD_MOST = 1;

    private final SurfaceHost host;
    /** The water the braid strokes: in front of her stand ashore, at her braid tip when she swims. */
    private Vec3 water;
    private double pace;
    private int t, length, maxTicks, contact, strokeEnd;
    private long startTick;
    private float paid;
    /** Stroking her own hat afloat while swimming, not standing at the bank: the stroke draws it to her braid. */
    private boolean wet;
    private String clip = YukkuriAction.BANK_BECKON.id();

    public Beckon(SurfaceHost host) { this.host = host; }

    @Override public String id() { return ID; }

    @Override public Feasibility check(YukkuriEntity body) {
        if (!ContactGeometry.braided(body)) return Feasibility.of(Reason.NO_TOOL, 0, "no_braid");
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        if (!(Attach.of(body) instanceof AttachState.Free free)) return Feasibility.of(Reason.NO_SUPPORT, 0, "not_ashore");
        if (free.inWater()) return checkWet(body);
        return body.onGround() ? checkFrom(body, body.position(), body.getYRot()) : Feasibility.of(Reason.NO_SUPPORT, 0, "not_ashore");
    }

    /** Admission at a reachable dry stand, facing the host; execution uses this same reach and water test. */
    public Feasibility checkFrom(YukkuriEntity body, Vec3 stand, float yaw) {
        if (!ContactGeometry.braided(body)) return Feasibility.of(Reason.NO_TOOL, 0, "no_braid");
        if (host.entity().isRemoved()) return Feasibility.of(Reason.TARGET_GONE, 0, "host_gone");
        double far = host.entity().position().subtract(stand).horizontalDistance() - REACH;
        if (far > 0) return Feasibility.of(Reason.TOO_FAR, far, "host_far");
        wet = false;
        water = water(body, stand, yaw);
        double gap = water == null ? Double.POSITIVE_INFINITY : ContactGeometry.braidGap(body, stand, water, true);
        if (gap > 0) return Feasibility.of(Reason.NO_CONTACT, Double.isFinite(gap) ? gap : 0, water == null ? "no_water_ahead" : "water_out_of_reach");
        if (gap() <= AT) return Feasibility.pass();
        return body.life().energy() < COST ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    /** Swimming within a braid's stroke of a host afloat: the same stroke draws it in to her own braid. */
    private Feasibility checkWet(YukkuriEntity body) {
        var hull = host.entity();
        if (!hull.isInWaterOrBubble()) return Feasibility.of(Reason.NO_SUPPORT, 0, "host_not_afloat");
        double far = hull.position().subtract(body.position()).horizontalDistance() - REACH;
        if (far > 0) return Feasibility.of(Reason.TOO_FAR, far, "host_far");
        wet = true;
        water = surface(body, ContactGeometry.braidTip(body));
        if (water == null) return Feasibility.of(Reason.NO_CONTACT, 0, "no_water_at_braid");
        return body.life().energy() < COST ? Feasibility.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy()) : Feasibility.pass();
    }

    /** The water's surface the braid can stroke ahead of the body: the nearest one within its own reach past the front (a stand at
     *  the very edge has the water one step farther out), null when no water lies ahead. */
    private static Vec3 water(YukkuriEntity body, Vec3 stand, float facing) {
        double yaw = Math.toRadians(facing);
        var level = body.level();
        for (double out = body.getBbWidth() * Mth.SQRT_OF_TWO / 2 + AHEAD; out <= AHEAD_MOST; out += .25) {
            double x = stand.x - Math.sin(yaw) * out, z = stand.z + Math.cos(yaw) * out;
            for (int dy = 0; dy >= -1; dy--) {
                var p = BlockPos.containing(x, stand.y - .01 + dy, z);
                var fluid = level.getFluidState(p);
                if (fluid.is(FluidTags.WATER)) return new Vec3(x, p.getY() + fluid.getHeight(level, p), z);
            }
        }
        return null;
    }

    /** The water's surface at a point: the highest water in the block it lies in or the two below it (a braid tip under the surface
     *  lies in the water it strokes), null when all three are dry. */
    private static Vec3 surface(YukkuriEntity body, Vec3 at) {
        var level = body.level();
        for (int dy = 0; dy >= -2; dy--) {
            var p = BlockPos.containing(at.x, at.y + dy, at.z);
            var fluid = level.getFluidState(p);
            if (fluid.is(FluidTags.WATER)) return new Vec3(at.x, p.getY() + fluid.getHeight(level, p), at.z);
        }
        return null;
    }

    @Override public void start(YukkuriEntity body) {
        startTick = body.level().getGameTime();
        pace = BodyAbility.of(body).swimSpeed() * BRAID;
        clip = wet ? YukkuriAction.WATER_BECKON.id() : YukkuriAction.BANK_BECKON.id();
        var markers = ClipMarkers.of(clip);
        length = markers.length();
        maxTicks = CYCLES * length;
        contact = markers.ticks("contact", 0);
        strokeEnd = markers.ticks("stroke", length);
    }

    @Override public Progress tick(YukkuriEntity body) {
        var hull = host.entity();
        if (hull.isRemoved()) return Outcome.of(Reason.TARGET_GONE, 0, "host_gone");
        // Ashore she pulls it to the water her braid strokes; swimming she pulls it to herself.
        Vec3 at = wet ? body.position() : water;
        Vec3 to = at.subtract(hull.position()).multiply(1, 0, 1);
        double distance = to.length();
        if (wet && distance > 1E-4)
            Motor.face(body, body.getYRot() + (float) Effort.turn(body.getYRot(),
                    Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG - 90, BodyAbility.of(body).turnRate()));
        if (ContactGeometry.reachesHull(body, hull, true, false))
            return new Outcome.Done(wet ? "at_braid at=" + GameplayLog.at(hull.position()) : "at_bank at=" + GameplayLog.at(hull.position()));
        double gap = wet ? distance : gap();
        if (t >= maxTicks) return Outcome.of(Reason.TOO_FAR, gap, "ticks=" + t);
        int phase = t % length;
        if (phase >= contact && phase < strokeEnd) {
            if (body.life().energy() < COST) return Outcome.of(Reason.EXHAUSTED, 0, "energy=" + body.life().energy());
            pace = BodyAbility.of(body).swimSpeed() * BRAID;
            if (!host.thrust(body, to.scale(Math.min(pace, distance) / distance), 0)) return Outcome.of(Reason.NO_SUPPORT, 0, "not_afloat");
            body.life().spendEnergy(COST);
            paid += COST;
            // The stroke meets the water once per cycle, where it really pulls.
            if (phase == contact) splash(body);
        }
        t++;
        return new Running(gap, TravelMode.CONSUMED);
    }

    /** The braid's stroke meeting the water it pulls, at the point it really pulls (splash is display only: the flow it stirs is the
     *  host's own drift). */
    private void splash(YukkuriEntity body) {
        if (body.level() instanceof ServerLevel level)
            level.sendParticles(ParticleTypes.SPLASH, water.x, water.y, water.z, 4, .1, .02, .1, .01);
    }

    /** How far (blocks) the host's brim lies from the water she strokes: the stroke's own progress, 0 once its surface is over it. */
    private double gap() {
        if (host.support(host.frame().toLocal(water), 0).isPresent()) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (var p : host.rim()) best = Math.min(best, Math.hypot(p.x - water.x, p.z - water.z));
        return best;
    }

    @Override public MotionPose pose() { return new MotionPose(clip, startTick, true); }
    @Override public int motionSource() { return host.entity().getId(); }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "host=%s distance=%.2f water=%s wet=%b stroke=%d..%d/%d pace=%.3f ticks<=%d",
                host.entity().getUUID(), host.entity().position().distanceTo(water), GameplayLog.at(water), wet,
                contact, strokeEnd, length, pace, maxTicks);
    }

    @Override public String endDetail(Outcome outcome) { return String.format(Locale.ROOT, "ticks=%d paid=%.2f", t, paid); }
}
