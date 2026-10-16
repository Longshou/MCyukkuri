package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Finite, server-owned boarding from actual feet, or continuous near-brim climbing. */
public final class HatBoarding {
    public enum Phase { IDLE, ALIGN, HOP, GRIP, LIFT, SETTLE }
    private final YukkuriEntity actor;
    private Phase phase=Phase.IDLE;
    private UUID sourceId,hatId,abandonedHat;
    private Vec3 seat,from,to,grip,crest;
    private long start,lastTick=Long.MIN_VALUE;
    private int duration,hopTicks,steadyTicks,totalTicks;
    private float lastHatYaw,lastSourceYaw;
    private Vec3 lastVelocity=Vec3.ZERO;
    public HatBoarding(YukkuriEntity actor){this.actor=actor;}
    private ServerLevel level(){return (ServerLevel)actor.level();}
    private HatBoatEntity boat(UUID id){return id!=null&&level().getEntity(id) instanceof HatBoatEntity h?h:null;}
    public boolean active(){return phase!=Phase.IDLE;}
    public boolean targets(HatBoatEntity hat){return active()&&hat!=null&&hat.getUUID().equals(hatId);}
    public boolean climbing(){return phase==Phase.GRIP||phase==Phase.LIFT||phase==Phase.SETTLE;}
    private boolean capable(){return actor.isAlive()&&!actor.isNoAi()&&!actor.isCommandPlaying()&&!actor.operations().active()
            &&!actor.isOnFire()&&!actor.family().braidRescueActive()&&actor.family().stage()!=FamilyLife.Stage.BABY&&actor.childhood().canJump()
            &&actor.life().energy()>=8&&!actor.life().isSleeping();}
    public static boolean stable(HatBoatEntity hat){
        return hat!=null&&!hat.isRemoved()&&(hat.isAfloat()||hat.catches())&&!hat.unsteadyMotion()
                &&!hat.towed()&&hat.flooding()==0;
    }
    /** Natural small jumps use the same gravity as the authored hat geometry, with finite speed and height. */
    public static Vec3 hopPoint(Vec3 from,Vec3 to,int ticks,double elapsed){return HatVessel.arc(from,to,ticks,elapsed);}
    public static Vec3 climbPoint(Vec3 from,Vec3 to,double progress){
        double t=Mth.clamp(progress,0,1);return from.lerp(to,t*t*(3-2*t));
    }
    public static double maximumGap(YukkuriEntity actor){return .28+.8*Math.min(1,actor.growthScale()*actor.getScale());}
    public static int hopDuration(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to){
        double scale=Math.min(1,actor.growthScale()*actor.getScale()),distance=from.subtract(to).horizontalDistance();
        if(distance>maximumGap(actor)||to.y-from.y>.14+.5*scale||from.y-to.y>.40+.5*scale)return 0;
        double gravity=HatVessel.GRAVITY/16;
        for(int ticks=4;ticks<=10;ticks++){
            double launch=(to.y-from.y)/ticks+gravity*ticks*.5;
            if(distance/ticks>.05+.06*scale||launch<0||launch>.22+.14*scale)continue;
            boolean clear=true;Vec3 previous=from;
            for(int step=1;step<=ticks*4;step++){
                var at=hopPoint(from,to,ticks,step*.25);
                if(!sweepClear(actor,source,hat,previous,at)){clear=false;break;}previous=at;
            }
            if(clear)return ticks;
        }
        return 0;
    }
    public boolean begin(HatBoatEntity source,HatBoatEntity hat,Vec3 localSeat){
        if(active())return hat.getUUID().equals(hatId);
        if(!capable()||!stable(hat)||actor.isInWaterOrBubble()||localSeat==null
                ||source!=null&&(!stable(source)||actor.getVehicle()!=source||!source.protects(actor)||source.changingSeat(actor))
                ||source==null&&(actor.isPassenger()||!actor.onGround()||!actor.life().dryStand(actor.blockPosition())))return false;
        var origin=actor.position();var destination=hat.toWorld(localSeat);
        int ticks=hopDuration(actor,source,hat,origin,destination);if(ticks==0)return false;
        sourceId=source==null?null:source.getUUID();hatId=hat.getUUID();seat=localSeat;
        from=origin;to=destination;grip=null;hopTicks=ticks;steadyTicks=totalTicks=0;
        lastHatYaw=hat.getYRot();lastSourceYaw=source==null?0:source.getYRot();
        lastVelocity=Vec3.ZERO;set(Phase.ALIGN,6);hold(source,hat);return true;
    }
    public boolean climb(HatBoatEntity hat,Vec3 localSeat,Vec3 actualGrip){
        if(active())return climbing()&&hat.getUUID().equals(hatId);
        if(!capable()||!stable(hat)||actor.isPassenger()||!actor.isInWaterOrBubble()||localSeat==null||actualGrip==null)return false;
        var destination=hat.toWorld(localSeat);var origin=actor.position();
        double scale=Math.min(1,actor.growthScale()*actor.getScale());
        if(origin.subtract(destination).horizontalDistance()>.22+.7*scale||destination.y-origin.y>.18+.55*scale)return false;
        var lip=hat.toWorld(new Vec3(localSeat.x,HatVessel.top(hat.mode()),localSeat.z));
        var top=new Vec3(origin.x,Math.max(Math.max(origin.y,destination.y),lip.y+.015),origin.z);
        for(int i=1;i<=32;i++){
            var a=climbPoint(origin,top,i/32.0);var b=climbPoint(top,destination,i/32.0);
            if(!clearAt(actor,null,hat,a)||!clearAt(actor,null,hat,b)||!withinGrip(actor,actualGrip,a))return false;
        }
        if(!actor.life().spendEnergy(.5f))return false;
        sourceId=null;hatId=hat.getUUID();seat=localSeat;from=origin;to=destination;grip=actualGrip;crest=top;
        steadyTicks=totalTicks=0;lastVelocity=Vec3.ZERO;set(Phase.GRIP,8);hat.holdPosition();return true;
    }
    private void set(Phase next,int ticks){phase=next;duration=ticks;start=level().getGameTime();}
    private void hold(HatBoatEntity source,HatBoatEntity hat){
        actor.getNavigation().stop();hat.holdPosition();if(source!=null)source.holdPosition();
    }
    public void tick(){
        if(actor.level().isClientSide)return;
        if(abandonedHat!=null)for(var loaded:level().getServer().getAllLevels()){
            if(loaded.getEntity(abandonedHat) instanceof HatBoatEntity old){
                old.cancelIncoming(actor);
                if(actor.getUUID().equals(old.ownerYukkuri()))old.releaseOwnBoardingSeat(actor);
                abandonedHat=null;break;
            }
        }
        if(!active())return;
        long now=level().getGameTime();if(lastTick==now)return;lastTick=now;
        var hat=boat(hatId);var source=boat(sourceId);
        if(!capable()||!stable(hat)||sourceId!=null&&!stable(source)||++totalTicks>120
                ||hat.toWorld(seat).distanceToSqr(to)>.0004){cancel();return;}
        if(!actor.getUUID().equals(hat.ownerYukkuri())){
            var reserved=hat.peekSeat(actor);
            if(reserved==null||reserved.distanceToSqr(seat)>.000001){cancel();return;}
        }
        hold(source,hat);int elapsed=(int)(now-start);
        if(phase==Phase.ALIGN){
            if(source!=null&&(actor.getVehicle()!=source||source.changingSeat(actor))
                    ||source==null&&(!actor.onGround()||actor.isInWaterOrBubble())
                    ||actor.position().distanceToSqr(from)>.0036){cancel();return;}
            var delta=to.subtract(actor.position());float yaw=(float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90);
            actor.setYRot(Mth.approachDegrees(actor.getYRot(),yaw,12));actor.yBodyRot=actor.getYRot();actor.setYHeadRot(actor.getYRot());
            boolean still=hat.getDeltaMovement().horizontalDistanceSqr()<.000225&&Math.abs(Mth.wrapDegrees(hat.getYRot()-lastHatYaw))<2
                    &&(source==null||source.getDeltaMovement().horizontalDistanceSqr()<.000225&&Math.abs(Mth.wrapDegrees(source.getYRot()-lastSourceYaw))<2);
            lastHatYaw=hat.getYRot();lastSourceYaw=source==null?0:source.getYRot();
            steadyTicks=still&&Math.abs(Mth.wrapDegrees(yaw-actor.getYRot()))<12?steadyTicks+1:0;
            if(steadyTicks<6)return;
            from=actor.position();hopTicks=hopDuration(actor,source,hat,from,to);
            if(hopTicks==0||!actor.life().spendEnergy(.25f)){cancel();return;}
            if(source!=null)actor.stopRiding();actor.setDeltaMovement(Vec3.ZERO);set(Phase.HOP,hopTicks);return;
        }
        if(actor.isPassenger()){cancel();return;}
        Vec3 at=phase==Phase.HOP?hopPoint(from,to,hopTicks,Math.min(elapsed,hopTicks))
                :phase==Phase.GRIP?from:phase==Phase.LIFT?climbPoint(from,crest,(double)elapsed/duration)
                :climbPoint(crest,to,(double)elapsed/duration);
        if(climbing()&&(!actor.life().spendEnergy(.025f)||!supported(hat,actor,at)&&!withinGrip(actor,grip,at))){cancel();return;}
        var delta=at.subtract(actor.position());
        if(delta.lengthSqr()>.45*.45||!sweepClear(actor,source,hat,actor.position(),at)){cancel();return;}
        // A bounded physical boarding trajectory replaces only the two hollow hats' coarse AABBs.
        // Its endpoint is fixed at takeoff; a moving boat cannot pull a flying child after itself.
        actor.setPos(at.x,at.y,at.z);actor.setDeltaMovement(Vec3.ZERO);actor.hasImpulse=true;
        actor.fallDistance=0;lastVelocity=delta;
        if(elapsed<duration)return;
        if(phase==Phase.GRIP){set(Phase.LIFT,16);return;}
        if(phase==Phase.LIFT){set(Phase.SETTLE,16);return;}
        boolean landed=actor.getUUID().equals(hat.ownerYukkuri())?hat.board(actor):hat.boardYoung(actor);
        if(!landed){cancel();return;}
        phase=Phase.IDLE;hat.cancelIncoming(actor);
        if(actor.getUUID().equals(hat.ownerYukkuri()))hat.releaseOwnBoardingSeat(actor);
        hatId=sourceId=null;lastVelocity=Vec3.ZERO;
    }
    private static boolean supported(HatBoatEntity hat,YukkuriEntity actor,Vec3 feet){
        var local=hat.toLocal(feet);double floor=HatVessel.footprint(hat.mode(),local.x,local.z,HatVessel.radius(actor.getBbWidth())/hat.unit());
        return Double.isFinite(floor)&&Math.abs(local.y-floor)*hat.unit()<.02;
    }
    private static boolean withinGrip(YukkuriEntity actor,Vec3 grip,Vec3 feet){
        return grip!=null&&actor.getBoundingBox().move(feet.subtract(actor.position())).inflate(.06+.25*actor.growthScale()*actor.getScale()).contains(grip);
    }
    public static boolean sweepClear(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 from,Vec3 to){
        int steps=Math.max(1,(int)Math.ceil(from.distanceTo(to)/.025));
        for(int i=1;i<=steps;i++)if(!clearAt(actor,source,hat,from.lerp(to,(double)i/steps)))return false;
        return true;
    }
    private static boolean clearAt(YukkuriEntity actor,HatBoatEntity source,HatBoatEntity hat,Vec3 feet){
        var level=actor.level();var box=actor.getBoundingBox().move(feet.subtract(actor.position())).deflate(.002);
        if(!level.getWorldBorder().isWithinBounds(BlockPos.containing(feet))||level.getBlockCollisions(actor,box).iterator().hasNext())return false;
        for(var other:level.getEntities(actor,box))
            if(other!=source&&other!=hat&&other.isAlive()&&!other.isSpectator()&&(other instanceof YukkuriEntity||other.canBeCollidedWith())
                    &&box.intersects(other.getBoundingBox()))return false;
        for(var vessel:new HatBoatEntity[]{source,hat})if(vessel!=null){
            var local=vessel.toLocal(feet);double floor=HatVessel.footprint(vessel.mode(),local.x,local.z,HatVessel.radius(actor.getBbWidth())/vessel.unit());
            if(Double.isFinite(floor)&&local.y<floor-.025/vessel.unit())return false;
        }
        return true;
    }
    public void cancel(){
        if(!active())return;
        var hat=boat(hatId);boolean airborne=phase!=Phase.ALIGN;
        if(hat!=null){hat.cancelIncoming(actor);if(actor.getUUID().equals(hat.ownerYukkuri()))hat.releaseOwnBoardingSeat(actor);}
        else abandonedHat=hatId;
        phase=Phase.IDLE;hatId=sourceId=null;
        if(airborne&&!actor.isPassenger()){
            actor.setDeltaMovement(lastVelocity.x,Math.min(-.02,lastVelocity.y),lastVelocity.z);actor.hasImpulse=true;
        }
    }
    public CompoundTag state(){
        var tag=new CompoundTag();if(!active())return tag;
        var hat=boat(hatId);if(hat==null)return tag;
        tag.putBoolean("Active",true);tag.putString("Phase",phase.name());tag.putLong("Start",start);tag.putInt("Duration",duration);
        tag.putInt("HatId",hat.getId());tag.putUUID("Hat",hatId);
        put(tag,"From",phase==Phase.SETTLE?crest:from);put(tag,"To",phase==Phase.LIFT?crest:to);put(tag,"Grip",grip);return tag;
    }
    public CompoundTag save(){var tag=new CompoundTag();if(hatId!=null)tag.putUUID("ReservedHat",hatId);else if(abandonedHat!=null)tag.putUUID("ReservedHat",abandonedHat);return tag;}
    public void load(CompoundTag tag){
        // An unloaded child resumes at its actual saved body position, never a replayed remote jump.
        phase=Phase.IDLE;hatId=sourceId=null;abandonedHat=tag.hasUUID("ReservedHat")?tag.getUUID("ReservedHat"):null;
        lastTick=Long.MIN_VALUE;lastVelocity=Vec3.ZERO;
    }
    private static void put(CompoundTag tag,String key,Vec3 point){if(point!=null){tag.putDouble(key+"X",point.x);tag.putDouble(key+"Y",point.y);tag.putDouble(key+"Z",point.z);}}
}
