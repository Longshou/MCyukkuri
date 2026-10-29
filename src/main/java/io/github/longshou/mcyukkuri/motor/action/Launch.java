package io.github.longshou.mcyukkuri.motor.action;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BirthLaunch;
import io.github.longshou.mcyukkuri.motor.Action;
import io.github.longshou.mcyukkuri.motor.ContactResolver;
import io.github.longshou.mcyukkuri.motor.Feasibility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.Outcome;
import io.github.longshou.mcyukkuri.motor.Progress;
import io.github.longshou.mcyukkuri.motor.Reason;
import io.github.longshou.mcyukkuri.motor.Running;
import io.github.longshou.mcyukkuri.motor.TravelMode;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A body thrown by someone else: a newborn pushed out of the outlet, out of a hat or off a host. The caller has already drawn
 * the impulse (spec 9.4 Launch, design 5.2 "no wind-up, straight to physics"), so this action only applies it once and lets
 * vanilla travel integrate it; every airborne tick first resolves host contact (spec 8.5) with the body's envelope. Nothing
 * is corrected, re-aimed or re-drawn here, so where the body comes down is decided by the first real contact. The preview
 * sweeps the same envelope along the predicted arc for the start line only.
 */
public final class Launch implements Action {
    public static final String ID = "launch";
    /** Air ticks allowed past the previewed flight before the body settles where it actually is (same allowance as Hop). */
    private static final int SETTLE = 10;
    /** How far down the preview looks for the first real surface under the launch point (blocks). */
    private static final double PREVIEW_DROP = 32;

    private final Vec3 velocity;
    private final String cause;
    private ContactResolver.Envelope envelope;
    private Vec3 from;
    private int horizon;
    private String preview = "";
    private int air;
    private double peak;

    public Launch(Vec3 velocity, String cause) {
        if (velocity == null) throw new IllegalArgumentException("Launch needs an impulse");
        this.velocity = velocity;
        this.cause = cause == null ? "" : cause;
    }

    /** The body's real boxes (its worn hat included), for the first contact and the preview sweep. */
    public Launch envelope(ContactResolver.Envelope boxes) {
        envelope = boxes;
        return this;
    }

    @Override public String id() { return ID; }

    /** Pure: the impulse must be finite and no faster than the birth draw can produce; the preview is log only. */
    @Override public Feasibility check(YukkuriEntity body) {
        double speed = velocity.length();
        preview = "";
        if (!Double.isFinite(speed)) return Feasibility.of(Reason.TOO_FAR, 0, "velocity=" + GameplayLog.at(velocity));
        double cap = BirthLaunch.MAX_SPEED;
        if (speed > cap) {
            return Feasibility.of(Reason.TOO_FAR, speed - cap, String.format(Locale.ROOT,
                    "speed=%.3f cap=%.3f velocity=%s", speed, cap, GameplayLog.at(velocity)));
        }
        from = body.position();
        Vec3 surface = surfaceBelow(body, from);
        // A non-rising impulse falls at least as fast as one tick of gravity; Ballistic.airTicks only launches upward.
        double lift = velocity.y > 0 ? velocity.y : Ballistic.GRAVITY;
        double drop = surface == null ? -PREVIEW_DROP : surface.y - from.y;
        horizon = Math.max(1, (int) Math.ceil(Ballistic.airTicks(lift, drop)));
        preview = previewText(body, drop);
        return Feasibility.pass();
    }

    @Override public void start(YukkuriEntity body) {
        air = 0;
        peak = 0;
        // No wind-up (design 5.2): the already-drawn impulse is written at once; vanilla travel integrates it from here on.
        Motor.velocity(body, velocity, "launch_" + cause);
    }

    @Override public Progress tick(YukkuriEntity body) {
        air++;
        peak = Math.max(peak, body.getY() - from.y);
        ContactResolver.Contact contact = ContactResolver.resolve(body, envelope);
        // A host that refuses the mount ends the flight where physics left the body (spec 8.5); the landing rules judge the rest.
        if (contact != null && contact.refused()) return Outcome.of(Reason.NO_ROOM, 0, "mount_refused");
        if (contact != null) return new Outcome.Done("contact at=" + GameplayLog.at(body.position()));
        if (body.isInWater()) return new Outcome.Done("water at=" + GameplayLog.at(body.position()));
        if (body.onGround()) return new Outcome.Done("landed at=" + GameplayLog.at(body.position()));
        if (air > horizon + SETTLE) return new Outcome.Done("settled at=" + GameplayLog.at(body.position()));
        return new Running(metric(), TravelMode.PASS);
    }

    /** A thrown body cannot be called back: it flies until it touches something. */
    @Override public boolean interruptible() { return false; }

    @Override public String startDetail() {
        return String.format(Locale.ROOT, "v=%s preview=%s cause=%s", GameplayLog.at(velocity), preview, cause);
    }

    @Override public String endDetail(Outcome outcome) {
        return String.format(Locale.ROOT, "peak=%.2f airTicks=%d", peak, air);
    }

    /** Ticks left before the launch ends on its own; it only ever drops, so the watchdog never fires on a long throw. */
    private double metric() { return horizon + SETTLE - air; }

    /**
     * Where the impulse would come down if nothing interfered, and the first thing its envelope meets on the way (log only):
     * a block, the border or another body's envelope. Hosts are the contact test's; a host's riders and a body already
     * touching this one at the launch point (the mother around her outlet) do not count.
     */
    private String previewText(YukkuriEntity body, double dy) {
        List<Vec> path = Ballistic.samples(new Vec(velocity.x, velocity.y, velocity.z), horizon);
        AABB start = body.getBoundingBox();
        String sweep = "clear";
        for (int i = 1; i < path.size(); i++) {
            Vec point = path.get(i);
            Vec3 feet = from.add(point.x(), point.y(), point.z());
            if (sweep.equals("clear")) sweep = sweep(body, feet, start);
            if (point.y() <= dy + 1E-9) return "landAt=" + GameplayLog.at(feet) + " ticks=" + i + " sweep=" + sweep;
        }
        return "landAt=none ticks=" + horizon + " sweep=" + sweep;
    }

    private String sweep(YukkuriEntity body, Vec3 feet, AABB start) {
        for (AABB box : boxes(body, feet)) {
            AABB shrunk = box.deflate(.002);
            if (!body.level().getWorldBorder().isWithinBounds(shrunk)) return "border";
            if (body.level().getBlockCollisions(body, shrunk).iterator().hasNext()) return "block@" + GameplayLog.at(feet);
            for (Entity other : body.level().getEntities(body, shrunk.inflate(1), e -> e.isAlive() && !(e instanceof SurfaceHost)
                    && !(e.getVehicle() instanceof SurfaceHost) && !e.getBoundingBox().intersects(start))) {
                List<AABB> parts = other instanceof YukkuriEntity y ? boxes(y, y.position()) : List.of(other.getBoundingBox());
                for (AABB part : parts) if (part.intersects(shrunk)) return "entity#" + other.getId();
            }
        }
        return "clear";
    }

    private List<AABB> boxes(YukkuriEntity body, Vec3 feet) {
        return envelope == null ? List.of(body.getBoundingBox().move(feet.subtract(body.position()))) : envelope.at(body, feet);
    }

    /** The first real surface under the launch point: block collision or any fluid, straight down. */
    private static Vec3 surfaceBelow(YukkuriEntity body, Vec3 from) {
        var hit = body.level().clip(new ClipContext(from, from.add(0, -PREVIEW_DROP, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, body));
        return hit.getType() == HitResult.Type.MISS ? null : hit.getLocation();
    }
}
