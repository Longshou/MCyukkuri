package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.random.ModRandom;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.VillageBin;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-side physical contact for ordinary ground food, sharing and interrupted meals. */
public final class ItemContact {
    public static boolean reaches(YukkuriEntity actor,Vec3 target,double radius) {
        Vec3 mouth=MouthPlacement.position(actor);
        double reach=Math.max(.045,.12*actor.getScale()*actor.growthScale())+Math.max(0,radius);
        if(mouth.distanceToSqr(target)>reach*reach)return false;
        Vec3 offset=target.subtract(mouth);
        // A nearby stack behind the cheeks is not in the mouth.
        if(offset.horizontalDistanceSqr()>.0025&&offset.dot(MouthPlacement.outward(actor))<-.025)return false;
        return clear(actor,mouth,target);
    }

    public static boolean reaches(YukkuriEntity actor,ItemEntity item) {
        return item!=null&&item.isAlive()&&item.level()==actor.level()&&!item.hasPickUpDelay()
                &&reaches(actor,item.position().add(0,.06,0),.06);
    }

    public static boolean mouthsMeet(YukkuriEntity giver,YukkuriEntity receiver) {
        if(giver==receiver||giver.level()!=receiver.level()||!giver.isAlive()||!receiver.isAlive())return false;
        return reaches(giver,MouthPlacement.position(receiver),.04*receiver.getScale()*receiver.growthScale())
                &&reaches(receiver,MouthPlacement.position(giver),.04*giver.getScale()*giver.growthScale());
    }

    public static boolean clear(YukkuriEntity actor,Vec3 from,Vec3 to) {
        return actor.level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,actor)).getType()==HitResult.Type.MISS;
    }

    /** Navigation destination, keeping the mouth forward of the colliding body. */
    public static Vec3 approach(YukkuriEntity actor,Vec3 target) {
        Vec3 direction=target.subtract(actor.position()).multiply(1,0,1);
        if(direction.lengthSqr()<1.0e-6)direction=MouthPlacement.outward(actor).multiply(1,0,1);
        double offset=MouthPlacement.position(actor).subtract(actor.position()).horizontalDistance();
        Vec3 stand=target.subtract(direction.normalize().scale(Math.max(.04,offset)));
        return new Vec3(stand.x,actor.getY(),stand.z);
    }

    public record BowlStand(Vec3 feet, Vec3 contact) {}

    /** A flat-sided bowl needs a body-sized stand off an exposed face. A radial step toward a corner can overlap its rim. */
    public static BowlStand bowlStand(YukkuriEntity actor, BlockPos bowl) {
        var level = actor.level();
        var rim = level.getBlockState(bowl).getCollisionShape(level, bowl).bounds().move(bowl);
        var center = rim.getCenter();
        double mouth = MouthPlacement.position(actor).subtract(actor.position()).horizontalDistance();
        double clearance = Math.max(actor.getBbWidth() * .5 + .02, mouth);
        double shortest = Double.POSITIVE_INFINITY;
        BowlStand chosen = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            var outward = Vec3.atLowerCornerOf(side.getNormal());
            double depth = side.getAxis() == Direction.Axis.X ? rim.getXsize() : rim.getZsize();
            var face = center.add(outward.scale(depth * .5));
            var foot = SurfaceApproach.floor(actor, new Vec3(face.x + outward.x * clearance, bowl.getY(), face.z + outward.z * clearance));
            if (foot == null) continue;
            double distance = SurfaceApproach.routeLength(actor, foot, null);
            if (distance >= shortest) continue;
            shortest = distance;
            chosen = new BowlStand(foot, new Vec3(face.x, rim.maxY + .02, face.z));
        }
        return chosen;
    }

    /** A bowl spill uses the same spawn boundary and retains the bowl's serving if the world refuses it. */
    public static ItemEntity release(YukkuriEntity actor, ItemStack owned, int pickupDelay) {
        return release(actor, owned, MouthPlacement.position(actor),
                MouthPlacement.outward(actor).scale(.025).add(0, -.015, 0), drop -> drop.setPickUpDelay(pickupDelay));
    }

    /** A deliberate put-down needs a dry collision top under the actual release point. */
    public static boolean hasReleaseSupport(Entity actor, Vec3 at) {
        var below = new Vec3(at.x, Math.min(at.y - .05, actor.getY() - .10), at.z);
        var hit = actor.level().clip(new ClipContext(at, below, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, actor));
        return hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == Direction.UP
                && actor.level().getFluidState(hit.getBlockPos()).isEmpty();
    }

    public static ItemEntity releaseSupported(YukkuriEntity actor, ItemStack owned, int pickupDelay) {
        Vec3 mouth = MouthPlacement.position(actor);
        return hasReleaseSupport(actor, mouth)
                ? release(actor, owned, mouth, Vec3.ZERO, drop -> drop.setPickUpDelay(pickupDelay)) : null;
    }

    /** The caller supplies the physical contact and retains the payload on a refused spawn. */
    public static ItemEntity release(Entity holder, ItemStack owned, Vec3 at, Vec3 velocity) {
        return release(holder, owned, at, velocity, ItemEntity::setDefaultPickUpDelay);
    }

    public static void shearFeedback(YukkuriEntity source,Player player,ItemEntity drop){
        var at=drop.position();
        drop.level().playSound(null,at.x,at.y,at.z,SoundEvents.SHEEP_SHEAR,SoundSource.PLAYERS,1,1);
        drop.level().gameEvent(GameEvent.SHEAR,at,GameEvent.Context.of(player));
        GameplayLog.event(source,"interaction","shear drop; item={} drop={} contact={} above_feet={}",
                drop.getItem().getItem(),drop.getUUID(),at,at.y-source.getY());
    }

    private static ItemEntity release(Entity holder, ItemStack owned, Vec3 at, Vec3 velocity, Consumer<ItemEntity> pickupDelay) {
        if (holder.level().isClientSide || owned.isEmpty()) return null;
        var dropped = ModRandom.created(new ItemEntity(holder.level(), at.x, at.y, at.z, owned.copy()), holder.getRandom());
        Motor.velocity(dropped, velocity, "item_release");
        pickupDelay.accept(dropped);
        VillageBin.markLitter(holder, dropped);
        return holder.level().addFreshEntity(dropped) ? dropped : null;
    }

    private ItemContact() {}
}
