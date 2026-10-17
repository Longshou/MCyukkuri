package io.github.longshou.mcyukkuri.interaction;

import io.github.longshou.mcyukkuri.body.MouthPlacement;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
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

    /** Caller retires its owned stack only after this returns a spawned entity. */
    public static ItemEntity release(YukkuriEntity actor,ItemStack owned,int pickupDelay) {
        if(actor.level().isClientSide||owned.isEmpty())return null;
        Vec3 mouth=MouthPlacement.position(actor);
        var dropped=new ItemEntity(actor.level(),mouth.x,mouth.y,mouth.z,owned.copy());
        dropped.setDeltaMovement(MouthPlacement.outward(actor).scale(.025).add(0,-.015,0));
        dropped.setPickUpDelay(pickupDelay);
        return actor.level().addFreshEntity(dropped)?dropped:null;
    }

    private ItemContact() {}
}
