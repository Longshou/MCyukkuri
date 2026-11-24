package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriComponents;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.motor.MouthOccupancy.Purpose;
import io.github.longshou.mcyukkuri.perception.Senses;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
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

    public static boolean hasNearBed(ServerLevel level, YukkuriEntity entity, Vec3 bedCentre) {
        var box = new AABB(bedCentre, bedCentre).inflate(BED_RADIUS);
        return !level.getEntitiesOfClass(ItemEntity.class, box,
                item -> item.isAlive() && fond(entity, item.getItem())
                        && item.position().distanceToSqr(bedCentre) <= BED_RADIUS * BED_RADIUS).isEmpty();
    }

    public static boolean isWithSomeone(ServerLevel level, ItemEntity item, YukkuriEntity self) {
        var box = item.getBoundingBox().inflate(WITH_RADIUS);
        return !level.getEntitiesOfClass(YukkuriEntity.class, box,
                other -> other != self && Senses.awake(other)
                        && other.distanceToSqr(item) <= WITH_RADIUS * WITH_RADIUS).isEmpty();
    }

    public static ItemEntity nearestBeside(ServerLevel level, YukkuriEntity entity, double radius) {
        var box = entity.getBoundingBox().inflate(radius);
        double maxSq = radius * radius;
        return level.getEntitiesOfClass(ItemEntity.class, box,
                item -> item.isAlive() && fond(entity, item.getItem())
                        && entity.distanceToSqr(item) <= maxSq)
                .stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
    }

    /** Total count of loose + mouth-held dolls in the vicinity for conservation logging. */
    public static int countNearbyDolls(ServerLevel level, Vec3 center, double radius) {
        var box = new AABB(center, center).inflate(radius);
        int count = 0;
        for (var item : level.getEntitiesOfClass(ItemEntity.class, box, i -> i.isAlive() && Facilities.doll(i.getItem()))) {
            count += item.getItem().getCount();
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
