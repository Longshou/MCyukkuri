package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Chooses between the people who actually share the dependants and the same home. */
public final class FamilyProvision {
    private FamilyProvision() {}

    static BlockPos home(YukkuriEntity body) {
        var shelter = body.habitat().shelter();
        return shelter == null ? body.life().homeStand() : shelter.getBlockPos();
    }

    static BlockPos entrance(YukkuriEntity body) {
        var shelter = body.habitat().shelter();
        if (shelter != null) return shelter.entrance();
        var nest = body.life().homeStand();
        if (nest == null) return null;
        var door = body.life().entrance(nest);
        return door == null ? nest : door;
    }

    static boolean sharesHome(YukkuriEntity a, YukkuriEntity b) {
        var home = home(a);
        return home != null && home.equals(home(b));
    }

    /** An older sibling can help at their shared home when neither parent is available there. */
    static boolean siblingCare(YukkuriEntity caregiver, YukkuriEntity child) {
        if (caregiver == child || !(caregiver.family().adult() || caregiver.family().subadult())
                || !child.childhood().shouldStayWithParents() || !sharesHome(caregiver, child)) return false;
        var a = caregiver.family();
        var b = child.family();
        if (!(a.mother() != null && a.mother().equals(b.mother())
                || a.father() != null && a.father().equals(b.father()))) return false;
        if (!(caregiver.level() instanceof ServerLevel level)) return false;
        for (UUID id : new UUID[]{b.mother(), b.father()})
            if (id != null && level.getEntity(id) instanceof YukkuriEntity parent && parent.isAlive()
                    && !parent.body().dying() && sharesHome(parent, child)
                    && !outForFood(parent) && !caregiver.family().kinOverdue(id)) return false;
        return true;
    }

    public static List<YukkuriEntity> caregivers(YukkuriEntity body) {
        var home = home(body);
        if (home == null) return List.of();
        var area = new AABB(home).inflate(24, 8, 24);
        var children = body.level().getEntitiesOfClass(YukkuriEntity.class, area,
                child -> child.isAlive() && child.childhood().shouldStayWithParents() && sharesHome(body, child)
                        && body.family().caresFor(child));
        if (children.isEmpty()) return List.of();
        var members = new LinkedHashSet<>(body.level().getEntitiesOfClass(YukkuriEntity.class, area,
                adult -> adult.isAlive() && !adult.body().dying() && sharesHome(body, adult)
                        && children.stream().anyMatch(adult.family()::caresFor)));
        var level = (ServerLevel) body.level();
        for (var child : children) {
            for (UUID id : new UUID[]{child.family().mother(), child.family().father()}) {
                if (id != null && level.getEntity(id) instanceof YukkuriEntity parent && parent.isAlive()
                        && !parent.body().dying() && sharesHome(body, parent) && parent.family().caresFor(child))
                    members.add(parent);
            }
        }
        return List.copyOf(members);
    }

    public static YukkuriEntity choose(List<YukkuriEntity> caregivers, Predicate<YukkuriEntity> available) {
        return caregivers.stream().filter(available)
                .max(Comparator.comparing(FamilyProvision::outForFood)
                        .thenComparingDouble(FamilyProvision::drive)
                        .thenComparing(YukkuriEntity::getUUID)).orElse(null);
    }

    public static boolean outForFood(YukkuriEntity body) {
        return body.forageTrip().familyOuting() || body.family().feeding().fetchingOutside();
    }

    private static double drive(YukkuriEntity body) {
        return (100 - body.life().nutrition()) * .5 + body.life().personality().boldness() * .25
                + body.getAttributeValue(Attributes.MOVEMENT_SPEED) * 100;
    }

    static boolean canFetchOutside(YukkuriEntity body) {
        var pick = choose(caregivers(body), candidate -> !body.family().absence().overdue(candidate.getUUID())
                && (outForFood(candidate) || candidate.family().feeding().careReady()));
        return pick == null || pick == body;
    }

    static boolean nearHome(YukkuriEntity body, BlockPos point) {
        var home = home(body);
        if (home == null) return true;
        var shelter = body.habitat().shelter();
        return shelter != null && shelter.contains(point) || point.distSqr(home) <= 16;
    }

    static Vec3 entranceStand(YukkuriEntity body) {
        var door = entrance(body);
        return door == null ? null : SurfaceApproach.floor(body, Vec3.atBottomCenterOf(door));
    }
}
