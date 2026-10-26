package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.world.phys.Vec3;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;

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
    public static double mouthDistance(YukkuriView caregiver, YukkuriView child) {
        return (caregiver.getBbWidth() + child.getBbWidth()) * .5 + MOUTH_CLEARANCE;
    }

    /** A tiny shell tolerance prevents a numerical pose overlap, while never accepting coincident body centres. */
    public static double minimumMouthDistance(YukkuriView caregiver, YukkuriView child) {
        return Math.max(.02, (caregiver.getBbWidth() + child.getBbWidth()) * .5 - MOUTH_CLEARANCE);
    }

    /** Contact spacing; approachSpot also checks the square body footprints and the actual floor. */
    public static double standingDistance(YukkuriEntity caregiver, YukkuriEntity child) {
        return mouthDistance(caregiver, child) - MOUTH_CLEARANCE * .5;
    }

    /** Supported stand beside the child, or null when no candidate is reachable; contact is checked separately. */
    public static Vec3 approachSpot(YukkuriEntity caregiver, YukkuriEntity child) {
        return SurfaceApproach.beside(caregiver,child,standingDistance(caregiver,child),MAX_MOUTH_HEIGHT_DELTA);
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

    /** The original client contact query through synchronized geometry and read-only sight checks. */
    public static boolean visibleMouthsMeet(YukkuriView caregiver, YukkuriView child) {
        if (caregiver == child || !caregiver.isAlive() || !child.isAlive() || caregiver.level() != child.level()
                || !caregiver.hasSightTo(child) || !child.hasSightTo(caregiver)) return false;
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

    /** The same low rear anchor is used by the server outlet and the client's contact target. */
    public static Vec3 rearPoint(YukkuriView child) {
        return child.position().subtract(Vec3.directionFromRotation(0, child.getYRot())
                .scale(child.getBbWidth() * .55)).add(0, .04, 0);
    }

    /** Rear care requires aligned, separated bodies and an unobstructed tongue path, also inside hats. */
    public static boolean rearContact(YukkuriEntity caregiver, YukkuriEntity child) {
        // The infertile bean form's high mouth cannot reach this low care anchor; never award an invisible lick.
        if (caregiver.subspecies().congenital() == SubspeciesLife.Congenital.BEAN_BODY) return false;
        if (caregiver == child || !caregiver.isAlive() || !child.isAlive() || caregiver.level() != child.level()
                || !caregiver.hasLineOfSight(child) || !child.hasLineOfSight(caregiver)) return false;
        Vec3 delta = child.position().subtract(caregiver.position()).multiply(1, 0, 1);
        double distance = delta.length();
        if (distance < minimumMouthDistance(caregiver, child) || distance > mouthDistance(caregiver, child)
                || Math.abs(caregiver.getY() - child.getY()) > .18) return false;
        Vec3 toward = delta.scale(1 / distance);
        if (horizontalForward(caregiver).dot(toward) < .97
                || Vec3.directionFromRotation(0, child.getYRot()).dot(toward) < .97) return false;
        Vec3 rear = rearPoint(child);
        // The parent's authored low lean ends at this mouth envelope. Trace the remaining tongue path.
        Vec3 mouth = caregiver.position().add(toward.scale(caregiver.getBbWidth() * .46))
                .add(0, .04 + caregiver.getBbHeight() * .10, 0);
        return caregiver.level().clip(new ClipContext(mouth, rear,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                caregiver)).getType() == HitResult.Type.MISS;
    }

    /** Keep the caregiver collision context when the client checks the visible tongue path. */
    public static boolean visibleRearContact(YukkuriView caregiver, YukkuriView child) {
        if (caregiver.congenitalForm() == SubspeciesLife.Congenital.BEAN_BODY) return false;
        if (caregiver == child || !caregiver.isAlive() || !child.isAlive() || caregiver.level() != child.level()
                || !caregiver.hasSightTo(child) || !child.hasSightTo(caregiver)) return false;
        Vec3 delta = child.position().subtract(caregiver.position()).multiply(1, 0, 1);
        double distance = delta.length();
        if (distance < minimumMouthDistance(caregiver, child) || distance > mouthDistance(caregiver, child)
                || Math.abs(caregiver.getY() - child.getY()) > .18) return false;
        Vec3 toward = delta.scale(1 / distance);
        if (horizontalForward(caregiver).dot(toward) < .97
                || Vec3.directionFromRotation(0, child.getYRot()).dot(toward) < .97) return false;
        Vec3 rear = rearPoint(child);
        Vec3 mouth = caregiver.position().add(toward.scale(caregiver.getBbWidth() * .46))
                .add(0, .04 + caregiver.getBbHeight() * .10, 0);
        return caregiver.clearColliderPath(mouth, rear);
    }

    private static Vec3 horizontalForward(YukkuriView entity) {
        return Vec3.directionFromRotation(0, entity.getYHeadRot()).multiply(1, 0, 1).normalize();
    }

    private static float yawTo(YukkuriEntity from, YukkuriEntity to) {
        Vec3 delta = to.position().subtract(from.position());
        return (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90);
    }

    private static void face(YukkuriEntity entity, float yaw) {
        SurfaceApproach.face(entity, yaw);
    }
}
