package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared physical boundary for a parent passing food directly from its mouth to a child's mouth.
 *
 * <p>The values are deliberately common-side values rather than model coordinates: the server owns
 * the transfer decision while the client uses the same target and progress to lean the two mouth
 * anchors together. A serving never becomes a projectile.</p>
 */
public final class FamilyFeedingContact {
    /** Number of uninterrupted mouth-contact ticks required before ownership may change. */
    public static final int REQUIRED_CONTACT_TICKS = 20;
    /** Extra clearance beyond two body radii while their mouths meet. */
    public static final double MOUTH_CLEARANCE = .10;
    /** Maximum difference between the two rendered head-height envelopes. */
    public static final double MAX_MOUTH_HEIGHT_DELTA = .50;
    /** Both faces must point substantially toward the other before food can change hands. */
    public static final double MIN_FACING_DOT = .70;

    private FamilyFeedingContact() {}

    /** Horizontal body-envelope distance at which the client should close the two mouth anchors. */
    public static double mouthDistance(YukkuriEntity caregiver, YukkuriEntity child) {
        return (caregiver.getBbWidth() + child.getBbWidth()) * .5 + MOUTH_CLEARANCE;
    }

    /** A tiny shell tolerance prevents a numerical pose overlap, while never accepting coincident body centres. */
    public static double minimumMouthDistance(YukkuriEntity caregiver, YukkuriEntity child) {
        return Math.max(.02, (caregiver.getBbWidth() + child.getBbWidth()) * .5 - MOUTH_CLEARANCE);
    }

    /** The parent walks to this point beside the child before beginning the contact count. */
    public static Vec3 approachSpot(YukkuriEntity caregiver, YukkuriEntity child) {
        Vec3 offset = caregiver.position().subtract(child.position()).multiply(1, 0, 1);
        if (offset.lengthSqr() < 1.0e-6) offset = horizontalForward(caregiver);
        else offset = offset.normalize();
        // Aim inside the accepted envelope: MoveControl may stop just short of its exact target.
        double contact = (minimumMouthDistance(caregiver, child) + mouthDistance(caregiver, child)) * .5;
        return child.position().add(offset.scale(contact));
    }

    /** Point both heads at one another before testing the shared physical envelope. */
    public static void faceEachOther(YukkuriEntity caregiver, YukkuriEntity child) {
        caregiver.getLookControl().setLookAt(child, 30, 30);
        child.getLookControl().setLookAt(caregiver, 30, 30);
        face(caregiver, yawTo(caregiver, child));
        face(child, yawTo(child, caregiver));
    }

    /**
     * Actual service contact: same live world, clear sight, compatible head heights, non-overlapping
     * body envelopes, and both faces directed at the other. Callers still own relationship and safety checks.
     */
    public static boolean mouthsMeet(YukkuriEntity caregiver, YukkuriEntity child) {
        if (caregiver == child || !caregiver.isAlive() || !child.isAlive() || caregiver.level() != child.level()
                || !caregiver.hasLineOfSight(child) || !child.hasLineOfSight(caregiver)) return false;
        Vec3 towardChild = child.position().subtract(caregiver.position()).multiply(1, 0, 1);
        double horizontal = towardChild.length();
        if (horizontal < minimumMouthDistance(caregiver, child) || horizontal > mouthDistance(caregiver, child)) return false;
        double caregiverMouth = caregiver.getY() + caregiver.getBbHeight() * .5;
        double childMouth = child.getY() + child.getBbHeight() * .5;
        if (Math.abs(caregiverMouth - childMouth) > MAX_MOUTH_HEIGHT_DELTA) return false;
        Vec3 unit = towardChild.scale(1 / horizontal);
        return horizontalForward(caregiver).dot(unit) >= MIN_FACING_DOT
                && horizontalForward(child).dot(unit.scale(-1)) >= MIN_FACING_DOT;
    }

    private static Vec3 horizontalForward(YukkuriEntity entity) {
        return Vec3.directionFromRotation(0, entity.getYHeadRot()).multiply(1, 0, 1).normalize();
    }

    private static float yawTo(YukkuriEntity from, YukkuriEntity to) {
        Vec3 delta = to.position().subtract(from.position());
        return (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
    }

    private static void face(YukkuriEntity entity, float yaw) {
        io.github.longshou.mcyukkuri.life.SurfaceApproach.face(entity, yaw);
    }
}
