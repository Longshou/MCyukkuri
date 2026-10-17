package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Supported toilet positions and real portions; no inventory change is earned by proximity alone. */
public final class ToiletContact {
    public static final int RELEASE_TICKS = 40;
    private ToiletContact() {}

    public static Vec3 rear(YukkuriEntity actor) {
        return FamilyFeedingContact.rearPoint(actor);
    }

    /** The supported brim outlet must also be behind the actor's current body, after turning. */
    public static Vec3 hatOutlet(YukkuriEntity actor, io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat) {
        var outlet = boat.toiletDropPosition(actor);
        if (outlet == null) return null;
        var outward = outlet.subtract(actor.position()).multiply(1, 0, 1).normalize();
        return Vec3.directionFromRotation(0, actor.getYRot()).dot(outward) <= -.98 ? outlet : null;
    }

    /** A blocked rear outlet never falls back to the body's centre. */
    public static boolean drop(YukkuriEntity actor, Vec3 outlet) {
        if (!(actor.level() instanceof ServerLevel server) || outlet == null) return false;
        var waste = new ItemEntity(server, outlet.x, outlet.y, outlet.z, DetachedItems.waste(actor));
        waste.setDefaultPickUpDelay();
        waste.setDeltaMovement(Vec3.ZERO);
        return server.noCollision(waste, waste.getBoundingBox()) && server.addFreshEntity(waste);
    }

    /** Stand on the actual collision surface, facing along the tray's longer axis. */
    public static Vec3 facilitySeat(YukkuriEntity actor, BlockPos pos) {
        if (pos == null || !(actor.level().getBlockEntity(pos) instanceof CareBlockEntity care)
                || care.kind() != CareBlock.Kind.TOILET || care.waste() >= care.capacity()) return null;
        var shape = actor.level().getBlockState(pos).getCollisionShape(actor.level(), pos);
        if (shape.isEmpty()) return null;
        var bounds = shape.bounds();
        double rear = actor.getBbWidth() * .55;
        // The outlet must fit over the collecting tray; large forms cannot use a tiny tray.
        if (rear > bounds.getXsize() - .12 || actor.getBbWidth() > bounds.getZsize() + .25) return null;
        double x = Math.max((bounds.minX + bounds.maxX) * .5, bounds.minX + .08 + rear);
        if (x > bounds.maxX - .06) return null;
        var seat = new Vec3(pos.getX() + x, pos.getY() + bounds.maxY, pos.getZ() + (bounds.minZ + bounds.maxZ) * .5);
        var body = actor.getBoundingBox().move(seat.subtract(actor.position())).deflate(.005);
        if (!actor.level().getFluidState(pos).isEmpty() || !actor.level().noCollision(actor, body)) return null;
        return seat;
    }

    public static boolean approachFacility(YukkuriEntity actor, BlockPos pos) {
        var seat = facilitySeat(actor, pos);
        if (seat == null) return false;
        if (SurfaceApproach.arrived(actor, seat)) {
            SurfaceApproach.halt(actor);
            SurfaceApproach.face(actor, -90); // face east; rear points into the tray
            return facilityContact(actor, pos);
        }
        // Once on the low tray, use ordinary MoveControl for the final centimetres.
        if (actor.onGround() && Math.abs(actor.getY() - seat.y) < .08
                && actor.position().subtract(seat).horizontalDistanceSqr() < .36) {
            actor.getNavigation().stop();
            actor.getMoveControl().setWantedPosition(seat.x, seat.y, seat.z, .55);
        } else if (actor.getNavigation().isDone() || actor.tickCount % 10 == 0) {
            actor.life().navigate(pos.above(), 1, false);
        }
        return false;
    }

    public static boolean facilityContact(YukkuriEntity actor, BlockPos pos) {
        var seat = facilitySeat(actor, pos);
        if (seat == null || !actor.onGround() || !SurfaceApproach.arrived(actor, seat)
                || Vec3.directionFromRotation(0, actor.getYRot()).dot(new Vec3(1, 0, 0)) < .98) return false;
        var rear = rear(actor);
        var shape = actor.level().getBlockState(pos).getCollisionShape(actor.level(), pos).bounds().move(pos);
        if (rear.x < shape.minX || rear.x > shape.maxX || rear.z < shape.minZ || rear.z > shape.maxZ) return false;
        var hit = actor.level().clip(new ClipContext(rear, rear.add(0, -.15, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getDirection() == Direction.UP;
    }
}
