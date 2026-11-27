package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.PlacedDollEntity;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.perception.Senses;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Object-agnostic keepsake rules and derived ownership: nothing is stored on the item or the body.
 * A keepsake within {@link #BED_RADIUS} of a bed belongs beside it; one within {@link #WITH_RADIUS}
 * of an awake body (or in its mouth) is with that body.
 */
public final class Keepsakes {
    public static final double BED_RADIUS = 1.5;
    public static final double WITH_RADIUS = 1.5;

    private Keepsakes() {}

    /** The one doll-specific predicate; other keepsakes can join here later. */
    public static boolean fond(YukkuriEntity entity, ItemStack stack) {
        if (stack.isEmpty() || !Facilities.doll(stack)) return false;
        var variant = entity.getVariant();
        return variant == YukkuriVariant.REIMU || variant == YukkuriVariant.MARISA;
    }

    /** True when the doll matches this body's own species. */
    public static boolean ownKindDoll(YukkuriEntity entity, ItemStack stack) {
        if (stack.isEmpty()) return false;
        return switch (entity.getVariant()) {
            case REIMU -> stack.is(Facilities.ITEMS.get("reimu_doll").get());
            case MARISA -> stack.is(Facilities.ITEMS.get("marisa_doll").get());
            default -> false;
        };
    }

    /**
     * A clever child only treats a doll of its own kind as kin; a dull child (intelligence < 40)
     * or a baby takes any doll for kin.
     */
    public static boolean kinDoll(YukkuriEntity entity, ItemStack stack) {
        if (!fond(entity, stack)) return false;
        if (entity.family().stage() == FamilyLife.Stage.BABY || entity.life().personality().intelligence() < 40) return true;
        return ownKindDoll(entity, stack);
    }

    /** Bold, unattached bodies never seek comfort or talk to a doll. */
    public static boolean attachedOrTimid(YukkuriEntity entity) {
        var personality = entity.life().personality();
        return personality.attachment() >= 30 || personality.temperament().timid() >= Temperament.TOP35;
    }

    /** Loneliness (existing affection need) or timid unease drives doll comfort and child talk. */
    public static boolean wantsComfort(YukkuriEntity entity) {
        if (!attachedOrTimid(entity)) return false;
        var personality = entity.life().personality();
        if (entity.dailyLife().affectionNeed() >= personality.affectionThreshold()) return true;
        return personality.temperament().timid() >= Temperament.TOP35 && entity.mind().unease() >= 25;
    }

    public static boolean hasAwakePeerNear(ServerLevel level, YukkuriEntity self, double radius) {
        double maxSq = radius * radius;
        return !level.getEntitiesOfClass(YukkuriEntity.class, self.getBoundingBox().inflate(radius),
                other -> other != self && Senses.awake(other) && self.distanceToSqr(other) <= maxSq).isEmpty();
    }

    /** Nearest placed, dry doll visible through the shared perception layer. */
    public static PlacedDollEntity nearestVisibleDoll(ServerLevel level, YukkuriEntity entity, double radius, boolean requireKin) {
        double maxSq = radius * radius;
        return level.getEntitiesOfClass(PlacedDollEntity.class, entity.getBoundingBox().inflate(radius, 3, radius),
                doll -> doll.isAlive() && !doll.isRemoved() && !doll.isInWater()
                        && (requireKin ? kinDoll(entity, doll.doll()) : fond(entity, doll.doll()))
                        && entity.life().dryStand(doll.blockPosition())
                        && Senses.sees(entity, doll)
                        && entity.distanceToSqr(doll) <= maxSq)
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    public static boolean hasNearBed(ServerLevel level, YukkuriEntity entity, Vec3 bedCentre) {
        var box = new AABB(bedCentre, bedCentre).inflate(BED_RADIUS);
        return !level.getEntitiesOfClass(PlacedDollEntity.class, box,
                doll -> doll.isAlive() && fond(entity, doll.doll())
                        && doll.position().distanceToSqr(bedCentre) <= BED_RADIUS * BED_RADIUS).isEmpty();
    }

    public static boolean isWithSomeone(ServerLevel level, PlacedDollEntity doll, YukkuriEntity self) {
        var box = doll.getBoundingBox().inflate(WITH_RADIUS);
        return !level.getEntitiesOfClass(YukkuriEntity.class, box,
                other -> other != self && Senses.awake(other)
                        && other.distanceToSqr(doll) <= WITH_RADIUS * WITH_RADIUS).isEmpty();
    }

    public static PlacedDollEntity nearestBeside(ServerLevel level, YukkuriEntity entity, double radius) {
        var box = entity.getBoundingBox().inflate(radius);
        double maxSq = radius * radius;
        return level.getEntitiesOfClass(PlacedDollEntity.class, box,
                doll -> doll.isAlive() && fond(entity, doll.doll())
                        && entity.distanceToSqr(doll) <= maxSq)
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    /** Total count of placed + mouth-held dolls in the vicinity for conservation logging. */
    public static int countNearbyDolls(ServerLevel level, Vec3 center, double radius) {
        var box = new AABB(center, center).inflate(radius);
        int count = 0;
        for (var doll : level.getEntitiesOfClass(PlacedDollEntity.class, box, d -> d.isAlive() && Facilities.doll(d.doll()))) {
            count += doll.doll().getCount();
        }
        for (var body : level.getEntitiesOfClass(YukkuriEntity.class, box, YukkuriEntity::isAlive)) {
            var stack = body.component(YukkuriComponents.MOUTH).stack(Purpose.KEEPSAKE);
            if (!stack.isEmpty() && Facilities.doll(stack)) count += stack.getCount();
        }
        return count;
    }

    public static double rimDistance(Vec3 point, BlockPos bedPos, double rimReach, Vec3 bedCentre) {
        double dx = Math.max(0, Math.abs(point.x - bedCentre.x) - rimReach);
        double dz = Math.max(0, Math.abs(point.z - bedCentre.z) - rimReach);
        return Math.hypot(dx, dz);
    }
}
