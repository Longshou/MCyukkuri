package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Movement on the actor's actual support. Domain goals retain their own decisions and rewards. */
public final class EcologyMovement {
    private EcologyMovement() {}

    public static boolean supported(YukkuriEntity actor) {
        return !actor.isPassenger() && actor.onGround();
    }

    public static boolean reachable(YukkuriEntity actor, BlockPos target) {
        return !actor.isPassenger() && actor.life().reachable(target, false);
    }

    /** A true result means a physical route was accepted, never that the destination was reached. */
    public static boolean navigate(YukkuriEntity actor, Vec3 destination, double speed) {
        return !actor.isPassenger() && actor.life().navigate(BlockPos.containing(destination), speed, false);
    }

    /** Supported final approach; contact remains the caller's existing geometric test. */
    public static boolean approach(YukkuriEntity actor, Entity target, double speed) {
        if (target == null || !target.isAlive() || actor.level() != target.level()) return false;
        Vec3 at = target instanceof YukkuriEntity other
                ? FamilyFeedingContact.approachSpot(actor, other) : target.position();
        if(at==null){SurfaceApproach.halt(actor);return false;}
        return !actor.isPassenger() && (target instanceof YukkuriEntity
                ?SurfaceApproach.walkAround(actor,at,target,speed):SurfaceApproach.walk(actor,at,speed));
    }

    public static void halt(YukkuriEntity actor) {
        SurfaceApproach.halt(actor);
    }
}
