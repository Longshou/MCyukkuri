package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import net.minecraft.world.phys.Vec3;

/**
 * Who can lift whom, and what a braid or a mouth can reach: the one capability rule of {@link CrewTransfer}.
 * Strength is {@link BraidRescueGeometry#canLift} (body volume against the carrier's volume and energy). The braid reaches a point
 * when it lies in the capsule shell between the shortest and longest extension of the two authored segments around the carrier's
 * <em>current</em> braid root; a mouth reaches a point within its hold reach of the mouth point. Nothing here scans the hull.
 */
public final class CrewReach {
    /** The single lift-capacity rule, for the braid and for the mouth alike. */
    public static boolean canLift(YukkuriEntity carrier, YukkuriEntity body) {
        return BraidRescueGeometry.canLift(carrier, body);
    }

    /** Whether this carrier owns a braid to lift with; the others (and a braid that cannot reach) use the mouth. */
    public static boolean hasBraid(YukkuriEntity carrier) {
        return carrier.getVariant() == YukkuriVariant.MARISA;
    }

    /** A braid root at the carrier's pose now. */
    public static Vec3 root(YukkuriEntity carrier) {
        return BraidRescueGeometry.root(carrier, 0);
    }

    /** The braid root if the carrier stood at {@code feet} facing {@code yaw} (degrees). */
    public static Vec3 rootAt(YukkuriEntity carrier, Vec3 feet, float yaw) {
        return BraidRescueGeometry.rootAtYaw(carrier, feet, yaw);
    }

    /** The braid capsule rule: a contact point is in reach of a root. */
    public static boolean braidReaches(YukkuriEntity carrier, Vec3 root, Vec3 contact) {
        return BraidRescueGeometry.reachable(carrier, root, contact);
    }

    /** The mouth point if the carrier stood at {@code feet} facing {@code yaw} (degrees). */
    public static Vec3 mouthAt(YukkuriEntity carrier, Vec3 feet, float yaw) {
        return BraidRescueGeometry.mouthPoint(carrier, feet, yaw);
    }

    /** The mouth rule: the contact point is within the hold reach of the mouth. */
    public static boolean mouthReaches(YukkuriEntity carrier, Vec3 mouth, Vec3 contact) {
        return mouth.distanceTo(contact) <= BraidRescueGeometry.holdReach(carrier);
    }

    /** The point of the body the carrier takes hold of, nearest her root or mouth. */
    public static Vec3 contactOn(YukkuriEntity body, Vec3 bodyFeet, Vec3 from) {
        return BraidRescueGeometry.rescueContact(body, bodyFeet, from);
    }

    private CrewReach() {}
}
