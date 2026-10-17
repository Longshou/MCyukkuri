package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** One family leaves through actual adjacent dry footing, children first and captain last. */
final class AquaticLanding {
    private final HatBoatEntity boat;
    private final LinkedHashMap<UUID,Vec3> waiting=new LinkedHashMap<>();
    private UUID ownerId,rescuer;
    private BlockPos bank;
    /** The whole operation and each single crossing are bounded; a stalled physical landing falls back to a direct one. */
    private static final int MAX_TICKS=1500, STALL_TICKS=240, DOCK_TICKS=100;
    private long started,progress;
    private int dockTicks;
    private boolean active,docked;
    private Vec3 retrievalStand;
    private long nextRetrievalSearch;

    AquaticLanding(HatBoatEntity boat){this.boat=boat;}
    boolean active(){return active;}
    boolean begin(YukkuriEntity owner,BlockPos stand,UUID helper,java.util.Map<UUID,Vec3> spots){
        if(active)return true;
        if(!boat.isAfloat()||boat.unsteadyMotion()||boat.towed()||owner.getVehicle()!=boat||owner.family().flying()
                ||owner.family().braidRescueActive()||owner.aquaticLife().familyLife().alarm().preventsOuting())return false;
        waiting.clear();
        // A nearby child in the water or another boat is never collected by this shore operation.
        for(var passenger:boat.getPassengers())if(passenger instanceof YukkuriEntity child&&child!=owner){
            var at=spots.get(child.getUUID());
            if(at==null||child.family().flying()||child.family().braidRescueActive())return false;
            waiting.put(child.getUUID(),at);
        }
        waiting.put(owner.getUUID(),Vec3.atBottomCenterOf(stand));
        ownerId=owner.getUUID();rescuer=helper;bank=stand.immutable();started=progress=boat.level().getGameTime();
        dockTicks=0;docked=false;active=true;boat.holdPosition();return true;
    }
    void tick(){
        if(!active||!(boat.level() instanceof ServerLevel level))return;
        if(!boat.isAfloat()||boat.unsteadyMotion()
                ||!(level.getEntity(ownerId) instanceof YukkuriEntity owner)||!owner.isAlive()){
            cancel();return;
        }
        long now=level.getGameTime();
        if(now-started>MAX_TICKS||now-progress>STALL_TICKS){
            // The contact choreography is not converging (gap, headroom, an infant's helper). Never hold the family on the hat forever.
            boat.forceShoreLanding(owner,rescuer);cancel();return;
        }
        if(owner.family().braidRescueActive()||owner.aquaticLife().familyLife().alarm().preventsOuting()){
            cancel();return;
        }
        boat.holdPosition();
        if(!docked){
            // Press the hull's long brim against the bank first; a one-cell stop short of it leaves a water gap no crawl can cross.
            docked=++dockTicks>DOCK_TICKS||boat.dockToward(Vec3.atBottomCenterOf(bank));
            return;
        }
        if(waiting.isEmpty()){
            if(boat.finishShoreLanding(owner,bank,rescuer)){cancel();return;}
            approachHat(owner,level);
            return;
        }
        var first=waiting.entrySet().iterator().next();
        if(!(level.getEntity(first.getKey()) instanceof YukkuriEntity actor)||!actor.isAlive()){
            cancel();return;
        }
        var destination=first.getValue();
        if(actor.family().flying())return;
        if(actor.getVehicle()!=boat){
            if(actor.onGround()&&!actor.isInWaterOrBubble()&&actor.position().distanceToSqr(destination)<2.25){
                actor.getNavigation().stop();waiting.remove(first.getKey());progress=now;
            }else cancel(); // Interrupted movement retains the real hat and cargo at their current positions.
            return;
        }
        if(actor.isNoAi()||actor.isCommandPlaying()||actor.operations().active()||actor.family().braidRescueActive())return;
        actor.life().wakeInHat();
        if(!boat.approachBoardingEdge(actor,destination)||boat.changingSeat(actor))return;
        if(actor!=owner&&(actor.family().stage()==io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY||!actor.childhood().canJump())){
            if(!boat.approachBoardingEdge(owner,destination)||boat.changingSeat(owner))return;
        }
        actor.family().startHatLanding(boat,destination);
    }
    /** The dry owner actually approaches the brim before returning her token. */
    private void approachHat(YukkuriEntity owner,ServerLevel level){
        if(owner.isNoAi()||owner.isPassenger()||owner.family().flying()||!owner.onGround()
                ||owner.isInWaterOrBubble()||owner.isCommandPlaying()||owner.operations().active())return;
        owner.getLookControl().setLookAt(boat,30,30);
        if(level.getGameTime()>=nextRetrievalSearch){
            nextRetrievalSearch=level.getGameTime()+20;
            retrievalStand=findRetrievalStand(owner,level);
        }
        if(retrievalStand!=null&&!dryPoint(owner,retrievalStand))retrievalStand=null;
        if(retrievalStand==null)return;
        var gap=retrievalStand.subtract(owner.position());
        if(gap.horizontalDistance()>.55){
            if(level.getGameTime()%10==0)owner.life().navigate(BlockPos.containing(retrievalStand),.55,false);
            return;
        }
        // Navigation targets block centres; the last mouth reach is a small supported physical crawl.
        owner.getNavigation().stop();
        if(Math.abs(gap.y)>.08)return;
        var step=gap.length()>.025?gap.normalize().scale(.025):gap;
        var next=owner.position().add(step);
        if(!dryPoint(owner,next))return;
        owner.move(net.minecraft.world.entity.MoverType.SELF,step);
        owner.setDeltaMovement(Vec3.ZERO);owner.hasImpulse=true;
        var toward=boat.position().subtract(owner.position());
        float yaw=(float)(Math.toDegrees(Math.atan2(toward.z,toward.x))-90);
        owner.setYRot(net.minecraft.util.Mth.approachDegrees(owner.getYRot(),yaw,8));owner.yBodyRot=owner.getYRot();
    }
    private Vec3 findRetrievalStand(YukkuriEntity owner,ServerLevel level){
        Vec3 best=null;double score=Double.POSITIVE_INFINITY;
        for(var block:BlockPos.betweenClosed(bank.offset(-1,0,-1),bank.offset(1,0,1))){
            if(!owner.life().dryStand(block)||!owner.life().reachable(block,false))continue;
            for(double x:new double[]{.2,.35,.5,.65,.8})for(double z:new double[]{.2,.35,.5,.65,.8}){
                var at=new Vec3(block.getX()+x,block.getY(),block.getZ()+z);
                if(at.distanceToSqr(Vec3.atBottomCenterOf(bank))>4||!dryPoint(owner,at))continue;
                var body=owner.getBoundingBox().move(at.subtract(owner.position())).inflate(.12);
                boolean contact=false;
                for(int i=0;i<32&&!contact;i++){
                    double a=i*Math.PI/16,dx=Math.cos(a),dz=Math.sin(a);
                    double reach=Math.max(0,HatVessel.extent(boat.mode(),0,0,dx,dz,0)-.125);
                    var local=new Vec3(dx*reach,HatVessel.FREEBOARD,dz*reach);
                    var rim=boat.toWorld(local).subtract(0,boat.rimLift(local),0);
                    contact=body.contains(rim);
                }
                double distance=at.distanceToSqr(owner.position());
                if(contact&&distance<score){score=distance;best=at;}
            }
        }
        return best;
    }
    private boolean dryPoint(YukkuriEntity actor,Vec3 at){
        var level=actor.level();var block=BlockPos.containing(at);
        if(!level.hasChunkAt(block)||!actor.life().dryStand(block))return false;
        var box=actor.getBoundingBox().move(at.subtract(actor.position()));
        if(level.containsAnyLiquid(box)||!level.noCollision(actor,box))return false;
        var hit=level.clip(new net.minecraft.world.level.ClipContext(at.add(0,.025,0),at.add(0,-.04,0),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,actor));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK&&Math.abs(hit.getLocation().y-at.y)<.04;
    }
    /** Drop the operation without side effects, after a direct landing already settled everyone. */
    void abort(){cancel();}
    private void cancel(){active=false;docked=false;dockTicks=0;waiting.clear();retrievalStand=null;nextRetrievalSearch=0;}
    CompoundTag save(){
        var tag=new CompoundTag();if(!active)return tag;
        tag.putUUID("Owner",ownerId);if(rescuer!=null)tag.putUUID("Rescuer",rescuer);
        tag.putLong("Bank",bank.asLong());tag.putLong("Started",started);
        var list=new ListTag();
        for(var entry:waiting.entrySet()){
            var r=new CompoundTag();r.putUUID("Id",entry.getKey());var at=entry.getValue();
            r.putDouble("X",at.x);r.putDouble("Y",at.y);r.putDouble("Z",at.z);list.add(r);
        }
        tag.put("Waiting",list);return tag;
    }
    void load(CompoundTag tag){
        cancel();if(!tag.hasUUID("Owner")||!tag.contains("Bank"))return;
        ownerId=tag.getUUID("Owner");rescuer=tag.hasUUID("Rescuer")?tag.getUUID("Rescuer"):null;
        bank=BlockPos.of(tag.getLong("Bank"));started=progress=boat.level().getGameTime();
        for(var raw:tag.getList("Waiting",Tag.TAG_COMPOUND)){
            var r=(CompoundTag)raw;if(!r.hasUUID("Id")||waiting.size()>HatBoatEntity.MAX_YOUNG)continue;
            var at=new Vec3(r.getDouble("X"),r.getDouble("Y"),r.getDouble("Z"));
            if(!Double.isFinite(at.lengthSqr())||at.distanceToSqr(Vec3.atBottomCenterOf(bank))>16){cancel();return;}
            waiting.put(r.getUUID("Id"),at);
        }
        active=true;
    }
}
