package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

/**
 * The single doorway to writing a body (spec 4): velocity, position, facing and riding of a {@link YukkuriEntity}
 * may only be written from here (plus the entity's own overrides that forward to {@link BodyDriver}).
 * Nothing here decides anything: it writes what an action decided and records the transition.
 */
public final class Motor {
    /** No nudge may move a body further than this in one call (blocks). */
    public static final double NUDGE_MAX = 0.25;
    /** Invariant I4: a dismount may not move the body further than this on either side of the vehicle relation (blocks). */
    public static final double DISMOUNT_MAX = 0.25;

    /**
     * True only while {@link #dismount} runs. A host's {@code getDismountLocationForPassenger} then returns the
     * passenger's own position, so vanilla cannot relocate it to the vehicle centre.
     */
    public static final ThreadLocal<Boolean> DISMOUNTING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private Motor() {}

    /** Sets the velocity and marks the impulse; only for take-off, launch, push and swim propulsion. */
    public static void velocity(YukkuriEntity body, Vec3 v, String cause) {
        if (body == null || v == null) return;
        body.setDeltaMovement(v);
        body.hasImpulse = true;
    }

    /** A collision-resolved move (MoverType.SELF); returns the movement that actually happened. */
    public static Vec3 move(YukkuriEntity body, Vec3 delta, String cause) {
        if (body == null || delta == null) return Vec3.ZERO;
        Vec3 from = body.position();
        body.move(MoverType.SELF, delta);
        return body.position().subtract(from);
    }

    /** Turns the whole body at once; the caller has already limited the step to the ability turn rate. */
    public static void face(YukkuriEntity body, float yaw) {
        if (body == null) return;
        body.setYRot(yaw);
        body.setYBodyRot(yaw);
        body.setYHeadRot(yaw);
    }

    /** Rides the host; records {@code [body] mount} only after vanilla accepted it, then settles the moved body (spec 12). */
    public static boolean mount(YukkuriEntity body, Entity host, String cause) {
        if (body == null || host == null || host == body) return false;
        if (!body.startRiding(host, true)) return false;
        GameplayLog.event(body, "body", "mount host={} cause={}", host.getUUID(), cause);
        // The mount is the transition: the moved body's own attach slot and nudge-out run here, once (settle never mounts).
        Attach.settle(body, cause);
        return true;
    }

    /**
     * Leaves the vehicle where the body actually stands: the position stays {@code at} (or where it is when null),
     * the velocity becomes {@code v}. Never re-seats, never rolls back.
     */
    public static void dismount(YukkuriEntity body, Vec3 at, Vec3 v, String cause) {
        if (body == null) return;
        Vec3 before = body.position();
        boolean previous = DISMOUNTING.get();
        DISMOUNTING.set(true);
        try { body.stopRiding(); } finally { DISMOUNTING.set(previous); }
        if (at != null) body.setPos(at.x, at.y, at.z);
        body.setDeltaMovement(v == null ? Vec3.ZERO : v);
        body.hasImpulse = true;
        GameplayLog.event(body, "body", "dismount at={} v={} cause={}",
                GameplayLog.at(body.position()), GameplayLog.at(body.getDeltaMovement()), cause);
        double moved = body.position().distanceTo(before);
        if (moved > DISMOUNT_MAX) {
            invariant(body, "I4", String.format(Locale.ROOT,
                    "dismount moved %.3f blocks (max %.2f) cause=%s", moved, DISMOUNT_MAX, cause));
        }
        // The dismount is the transition: the moved body's own attach slot and nudge-out run here, once (settle never dismounts).
        Attach.settle(body, cause);
    }

    /** Pushes a body out of a collision by at most {@link #NUDGE_MAX}; anything longer is refused and logged. */
    public static boolean nudge(YukkuriEntity body, Vec3 delta, String cause) {
        if (body == null || delta == null) return false;
        if (delta.lengthSqr() > NUDGE_MAX * NUDGE_MAX) {
            warn(body, "nudge refused length={} max={} cause={}",
                    String.format(Locale.ROOT, "%.3f", delta.length()), NUDGE_MAX, cause);
            return false;
        }
        body.setPos(body.getX() + delta.x, body.getY() + delta.y, body.getZ() + delta.z);
        return true;
    }

    /** Records a violated invariant (spec 5.3) as a WARN; the state is never "corrected" here. */
    public static void invariant(YukkuriEntity body, String id, String detail) {
        warn(body, "invariant id={} detail={}", id, detail);
    }

    /** A {@code [body]} WARN with the usual party (variant, id, uuid, tick, dimension, position). */
    public static void warn(YukkuriEntity body, String message, Object... values) {
        if (body == null || body.level().isClientSide) return;
        Object[] args = new Object[6 + values.length];
        args[0] = body.getVariant().assetId();
        args[1] = body.getId();
        args[2] = body.getUUID();
        args[3] = body.level().getGameTime();
        args[4] = body.level().dimension().location();
        args[5] = body.blockPosition().toShortString();
        System.arraycopy(values, 0, args, 6, values.length);
        MCYukkuri.LOGGER.warn("[body] {} #{} uuid={} t={} dim={} pos={} " + message, args);
    }
}
