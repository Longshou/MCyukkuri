package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Movement on the actor's actual support. Domain goals retain their own decisions and rewards. */
public final class EcologyMovement {
    private EcologyMovement() {}

    public static HatBoatEntity boat(YukkuriEntity actor) {
        return actor.getVehicle() instanceof HatBoatEntity hat && hat.protects(actor) ? hat : null;
    }

    public static boolean supported(YukkuriEntity actor) {
        var hat = boat(actor);
        return hat == null ? !actor.isPassenger() && actor.onGround()
                : hat.isAfloat() && !hat.unsteadyMotion() && !hat.towed() && hat.flooding() == 0
                && !hat.landing() && !hat.driving() && !hat.transportingInHat(actor)
                && !hat.cargo().handling() && !hat.hasIncoming() && !actor.family().braidRescueActive()
                && !actor.aquaticLife().firstVoyage().active();
    }

    public static boolean captain(YukkuriEntity actor) {
        var hat = boat(actor);
        return hat != null && actor.getUUID().equals(hat.ownerYukkuri());
    }

    /** Ordinary vehicle passengers cannot steer their carrier. */
    public static boolean mobile(YukkuriEntity actor) {
        return !actor.isPassenger() || captain(actor) && supported(actor);
    }

    public static boolean reachable(YukkuriEntity actor, BlockPos target) {
        var hat = boat(actor);
        if (hat == null) return !actor.isPassenger() && actor.life().reachable(target, false);
        if (!captain(actor) || !supported(actor)) return false;
        BlockPos water = HatBoatRoute.calmSurface(actor.level(), target) ? target : target.below();
        if (!HatBoatRoute.calmSurface(actor.level(), water)) return false;
        Vec3 point = HatBoatRoute.surface(actor.level(), water);
        return hat.position().distanceToSqr(point) < 1
                || !HatBoatRoute.find(hat, point, p -> actor.aquaticLife().familyLife().allowsDestination(hat, p)).isEmpty();
    }

    /** A true result means a physical route was accepted, never that the destination was reached. */
    public static boolean navigate(YukkuriEntity actor, Vec3 destination, double speed) {
        var hat = boat(actor);
        if (hat == null) return !actor.isPassenger() && actor.life().navigate(BlockPos.containing(destination), speed, false);
        if (!supported(actor)) return false;
        if (!captain(actor)) return actor.position().distanceToSqr(destination) <= .04;
        return hat.paddleFor(actor, destination);
    }

    /** Supported final approach; contact remains the caller's existing geometric test. */
    public static boolean approach(YukkuriEntity actor, Entity target, double speed) {
        if (target == null || !target.isAlive() || actor.level() != target.level()) return false;
        var hat = boat(actor);
        if (hat == null) {
            Vec3 at = target instanceof YukkuriEntity other
                    ? FamilyFeedingContact.approachSpot(actor, other) : target.position();
            return !actor.isPassenger() && SurfaceApproach.walk(actor, at, speed);
        }
        if (!supported(actor)) return false;
        if (target instanceof YukkuriEntity other && other.getVehicle() == hat) {
            hat.holdPosition();
            if (captain(actor) && hat.young(other)) {
                return hat.prepareCareSeat(other, actor) && hat.careSeatReady(other);
            }
            return FamilyFeedingContact.mouthsMeet(actor, other);
        }
        if (!captain(actor)) return hat.approachBoardingEdge(actor, target.position());
        if (target.getVehicle() instanceof HatBoatEntity otherHat) {
            Vec3 away = hat.position().subtract(otherHat.position()).multiply(1, 0, 1);
            if (away.horizontalDistanceSqr() < .0001) return false;
            away = away.normalize();
            double separation = (hat.getBbWidth() + otherHat.getBbWidth()) * .5
                    / Math.max(Math.abs(away.x), Math.abs(away.z)) + .08;
            Vec3 berth = otherHat.position().add(away.scale(separation));
            berth = new Vec3(berth.x, hat.getY(), berth.z);
            if (hat.position().subtract(berth).horizontalDistanceSqr() > .01) {
                navigate(actor, berth, speed);
                return false;
            }
        } else {
            double reach = hat.getBbWidth() * .5 + target.getBbWidth() * .5 + .75;
            if (hat.position().subtract(target.position()).horizontalDistanceSqr() > reach * reach) {
                navigate(actor, new Vec3(target.getX(), hat.getY(), target.getZ()), speed);
                return false;
            }
        }
        hat.holdPosition();
        hat.approachBoardingEdge(actor, target.position());
        return false;
    }

    public static void halt(YukkuriEntity actor) {
        if (captain(actor)) boat(actor).holdPosition();
        SurfaceApproach.halt(actor);
    }

    public static void releaseContact(YukkuriEntity actor, YukkuriEntity other) {
        var hat = boat(actor);
        if (hat != null && other != null && other.getVehicle() == hat && captain(actor))
            hat.releaseCareSeat(other.getUUID());
    }
}
