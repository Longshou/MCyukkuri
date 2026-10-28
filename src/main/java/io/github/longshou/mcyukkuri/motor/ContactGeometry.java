package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.surface.SurfaceHost;
import java.util.Optional;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The one place that answers "can this body reach that" (spec 10). WP1 keeps only the mouth and the brim; the braid tip
 * and the grip test belong to WP5, which also folds the four existing brim geometries into this class.
 */
public final class ContactGeometry {
    /** The reach beyond the two body envelopes; the margin {@code ParentCarry.inReach} used, unchanged. */
    public static final double GRAB_MARGIN = .35;
    /** The height window of the grab rule, in blocks of difference between the two bodies. */
    public static final double GRAB_HEIGHT = 1.2;

    /** The mouth tip in world coordinates: the point the mouth pose hangs a held body from. */
    public static Vec3 mouth(YukkuriEntity body) {
        return MouthPlacement.position(body);
    }

    /**
     * The grab gap: the horizontal distance left between the two bodies' envelopes, so 0 means they touch. The full rule
     * (height window and sight included) is {@link #inMouthReach}, and it reproduces {@code ParentCarry.inReach} exactly.
     */
    public static double mouthGap(YukkuriEntity body, Entity target) {
        if (body == null || target == null) return Double.POSITIVE_INFINITY;
        double dx = target.getX() - body.getX(), dz = target.getZ() - body.getZ();
        return Math.max(0, Math.hypot(dx, dz) - (body.getBbWidth() + target.getBbWidth()) / 2);
    }

    /** Whether the mouth can reach the target now: {@code ParentCarry.inReach}, moved here so there is one copy. */
    public static boolean inMouthReach(YukkuriEntity body, Entity target) {
        return body != null && target != null && mouthGap(body, target) <= GRAB_MARGIN
                && Math.abs(target.getY() - body.getY()) < GRAB_HEIGHT && body.hasLineOfSight(target);
    }

    /** The brim sample of {@code host} nearest the mouth tip, already tilted into world coordinates. */
    public static Optional<Vec3> nearestRim(YukkuriEntity body, SurfaceHost host) {
        if (body == null || host == null) return Optional.empty();
        Vec3 mouth = mouth(body);
        Vec3 nearest = null;
        double best = Double.MAX_VALUE;
        for (Vec3 point : host.rim()) {
            double distance = point.distanceToSqr(mouth);
            if (distance < best) { best = distance; nearest = point; }
        }
        return Optional.ofNullable(nearest);
    }

    private ContactGeometry() {}
}
