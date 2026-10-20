package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.mind.EmotionEvent;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Interruptible physical routes. No route teleports the body or advances while unloaded. */
public final class PlayLife {
    private final YukkuriEntity entity;
    private PlayBlockEntity facility;
    private List<Vec3> route=List.of();
    private int cooldown,scanWait,elapsed,waypoint,bounces,bounceWait,stalled,bounceLimit;
    private double lastDistance=Double.MAX_VALUE;
    private boolean completed,bouncing;
    public PlayLife(YukkuriEntity entity){this.entity=entity;}
    public boolean active(){return facility!=null;}
    public boolean completed(){return completed;}
    public int bounces(){return bounces;}
    public String activity(){return !active()?Behaviors.IDLE:facility.kind()==PlayBlock.Kind.SLIDE?(waypoint>=6?Behaviors.SLIDE_DOWN:Behaviors.SLIDE_CLIMB):
            facility.kind()==PlayBlock.Kind.TRAMPOLINE?Behaviors.TRAMPOLINE:facility.kind()==PlayBlock.Kind.POOL?Behaviors.POOL_EDGE:Behaviors.TUNNEL;}
    private boolean available(){return (active() || entity.childhood().canExercise())&&entity.isAlive()&&!entity.isRemoved()&&!entity.isNoAi()&&!entity.isPanicking()&&!entity.isCommandPlaying()
            &&!entity.isPassenger()
            &&!entity.operations().active()&&!entity.groupInteraction().active()
            &&!(active()?entity.life().nutrition()<15||entity.life().energy()<15||entity.isOnFire()||entity.isInWaterOrBubble():entity.life().hasUrgentNeed())&&!entity.life().isSleeping()
            &&entity.life().mealTicks()==0&&entity.life().energy()>=(active()?15:35)&&entity.body().condition().readyForPlay()&&!entity.family().active()&&!entity.house().active();}
    public void clock(){if(cooldown>0)cooldown--;if(scanWait>0)scanWait--;if(active()&&!valid())stop();}
    public boolean canUse(){
        if(active())return valid();
        if(cooldown>0||scanWait>0||!available()||entity.isNoAi())return false;
        scanWait=160;
        if(entity.dailyLife().active()||entity.social().active())return false;
        var center=entity.blockPosition();
        for(var pos:BlockPos.betweenClosed(center.offset(-6,-1,-6),center.offset(6,1,6))){
            if(!entity.level().hasChunkAt(pos))continue;
            if(entity.level().getBlockEntity(pos) instanceof PlayBlockEntity found&&begin(found))return true;
        }
        return false;
    }
    public boolean begin(PlayBlockEntity target){
        if(active()||cooldown>0||!available()||target==null||target.getLevel()!=entity.level()||target.isRemoved()
                ||target.kind()==PlayBlock.Kind.TRAMPOLINE&&!entity.childhood().canJump()
                ||entity.distanceToSqr(Vec3.atCenterOf(target.getBlockPos()))>81||target.kind()==PlayBlock.Kind.POOL&&target.water()==0)return false;
        var local=switch(target.kind()){
            case PAPER,CERAMIC->List.of(new Vec3(0,0,-2),new Vec3(0,.125,-1),new Vec3(0,.125,0),new Vec3(0,.125,1),new Vec3(0,0,2));
            case SLIDE->List.of(new Vec3(0,0,-2.8),new Vec3(0,.25,-1.9),new Vec3(0,.5,-1.4),new Vec3(0,.75,-.9),new Vec3(0,1,-.4),new Vec3(0,1,0),new Vec3(0,0,2.8));
            case TRAMPOLINE->List.of(new Vec3(0,0,-1.7),new Vec3(0,.3125,0),new Vec3(0,0,1.7));
            case POOL->List.of(new Vec3(-.95,0,-1.2),new Vec3(-.95,.375,0),new Vec3(-.95,0,1.2),new Vec3(0,0,1.5));
        };
        var points=local.stream().map(v->PlayGeometry.world(target.getBlockPos(),target.facing(),v.x,v.y,v.z)).toList();
        var start=points.getFirst();var path=entity.getNavigation().createPath(BlockPos.containing(start),0);
        if(path==null||!path.canReach()||!target.reserve(entity))return false;
        entity.social().stop();facility=target;route=points;elapsed=waypoint=bounces=bounceWait=stalled=0;lastDistance=Double.MAX_VALUE;completed=bouncing=false;bounceLimit=entity.life().personality().workSegments();cooldown=1600;
        entity.getNavigation().moveTo(path,.8);
        GameplayLog.limited(entity,"play","started",1200,"facility session started kind={} at={} energy={} bounceLimit={}",
                target.kind(),target.getBlockPos(),entity.life().energy(),bounceLimit);
        return true;
    }
    private boolean valid(){return active()&&available()&&elapsed<600&&!facility.isRemoved()&&facility.getLevel()==entity.level()
            &&entity.level() instanceof ServerLevel level&&level.hasChunkAt(facility.getBlockPos())&&level.getBlockEntity(facility.getBlockPos())==facility
            &&entity.distanceToSqr(Vec3.atCenterOf(facility.getBlockPos()))<144&&facility.reserve(entity);}
    public boolean canContinue(){return valid();}
    public void tickGoal(){
        if(!valid()){stop();return;}elapsed++;if(bounceWait>0)bounceWait--;
        Vec3 target=route.get(waypoint);double distance=target.subtract(entity.position()).horizontalDistance();
        entity.getLookControl().setLookAt(target.x,entity.getEyeY(),target.z,25,25);
        boolean onMat=facility.kind()==PlayBlock.Kind.TRAMPOLINE&&waypoint==1&&distance<.25;
        if(onMat){
            entity.getNavigation().stop();
            if(!entity.onGround())return;
            // Let the client's position interpolation settle on the mat before the next launch.
            if(bouncing){bouncing=false;bounceWait=6;return;}
            if(bounces<bounceLimit){
                if(bounceWait==0){
                    if(!entity.life().spendEnergy(2)){stop();return;}
                    entity.setDeltaMovement(0,.48,0);entity.hasImpulse=true;entity.hurtMarked=true;entity.fallDistance=0;
                    bounces++;bouncing=true;bounceWait=12;entity.mind().felt(EmotionEvent.PLAYED); entity.showReaction("happy","toy",45);
                }
                return;
            }
            if(bounceWait>0)return;
        }
        if(distance<.22&&Math.abs(entity.getY()-target.y)<.6){
            waypoint++;lastDistance=Double.MAX_VALUE;stalled=0;
            if(waypoint>=route.size()){
                completed=true;entity.mind().felt(EmotionEvent.PLAYED); entity.showReaction("happy","toy",70);stop();return;
            }
            if(!entity.life().spendEnergy(.5f)){stop();return;}target=route.get(waypoint);
        }
        if(distance<lastDistance-.015){stalled=0;lastDistance=distance;}else if(++stalled>100){stop();return;}
        if(facility.kind()==PlayBlock.Kind.SLIDE&&waypoint==6){
            entity.getNavigation().stop();Vec3 direction=target.subtract(entity.position()).multiply(1,0,1).normalize();
            face(direction);
            entity.setDeltaMovement(direction.x*.13,entity.getDeltaMovement().y,direction.z*.13);entity.hurtMarked=true;
        }else if(distance<2.5){
            entity.getNavigation().stop();entity.getMoveControl().setWantedPosition(entity.getX(),entity.getY(),entity.getZ(),0);
            // Vanilla MoveControl interprets the roof in a hollow cell as a step and jumps into it.
            // For the short authored route, drive horizontal motion; collision and gravity still resolve each step.
            Vec3 direction=target.subtract(entity.position()).multiply(1,0,1).normalize();
            face(direction);
            entity.setDeltaMovement(direction.x*.065,entity.getDeltaMovement().y,direction.z*.065);entity.hurtMarked=true;
        }else if(elapsed%10==1&&!entity.getNavigation().moveTo(target.x,target.y,target.z,.8)){stop();}
    }
    public void stop(){stop(true);}
    private void stop(boolean log){
        if(facility!=null){
            facility.release(entity);
            if(log)GameplayLog.limited(entity,"play","ended",1200,
                    "facility session ended kind={} at={} completed={} elapsed={} waypoint={} bounces={} stalled={} nutrition={} energy={}",
                    facility.kind(),facility.getBlockPos(),completed,elapsed,waypoint,bounces,stalled,entity.life().nutrition(),entity.life().energy());
        }
        facility=null;route=List.of();entity.getNavigation().stop();
    }
    private void face(Vec3 direction){entity.setYRot((float)(Math.atan2(-direction.x,direction.z)*180/Math.PI));entity.yBodyRot=entity.getYRot();}
    public CompoundTag save(){var tag=new CompoundTag();tag.putInt("Cooldown",cooldown);return tag;}
    public void load(CompoundTag tag){stop(false);cooldown=Math.clamp(tag.getInt("Cooldown"),0,24000);completed=false;}
}
