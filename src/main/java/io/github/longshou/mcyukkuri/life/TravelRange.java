package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyLife.Stage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Project travel distances. Contact, hearing and attack reach deliberately keep their own rules. */
public final class TravelRange {
    private TravelRange() {}
    public static final int SCALE = 2;
    public static final int PATH_RANGE = 64;
    public static final int BABY_RADIUS = 3;

    public static int search(YukkuriEntity animal, int previous) {
        return animal.family().stage() == Stage.BABY && !animal.family().emergencyForaging() ? Math.min(previous, BABY_RADIUS) : previous * SCALE;
    }

    public static int stroll(YukkuriEntity animal) {
        return animal.family().stage() == Stage.BABY ? BABY_RADIUS
                : (int)Math.round(20 * animal.life().personality().exploreMultiplier());
    }

    public static boolean keepBabyHome(YukkuriEntity animal) {
        return animal.family().stage() == Stage.BABY && !animal.family().emergencyForaging() && animal.life().homeStand() != null
                && babyDestination(animal,animal.blockPosition()) && !animal.family().familyTravelling() && !BehaviorContinuity.emergency(animal)
                && !animal.family().crew().holdsBody() && !animal.isPassenger();
    }
    /** A home limits voluntary baby errands, never escape, carrying or the family's march. */
    public static boolean babyDestination(YukkuriEntity animal, BlockPos destination) {
        if (animal.family().stage() != Stage.BABY || animal.family().emergencyForaging()) return true;
        BlockPos home = animal.life().homeStand();
        if (home == null) return animal.distanceToSqr(Vec3.atBottomCenterOf(destination)) <= BABY_RADIUS * BABY_RADIUS;
        var shelter = animal.habitat().shelter();
        return shelter != null && shelter.contains(destination) || destination.distSqr(home) <= BABY_RADIUS * BABY_RADIUS;
    }
}
