package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HatGeometry;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Read-only outlet selection. Admission and the eventual birth remain real, separate transactions. */
public final class BirthExit {
    public record Dropzone(Vec3 position,String kind,double reach,int priority,PlacedHatEntity hat,int slot,PlacedHatEntity.NewbornAdmission admission){
        public Dropzone(Vec3 position,String kind,double reach,int priority){this(position,kind,reach,priority,null,-1,PlacedHatEntity.NewbornAdmission.NONE);}
    }
    public record Exit(float yaw,Vec3 outletPos,String targetKind,String reason,int candidates,Dropzone target){
        public boolean safe(){return reason.isEmpty();}
    }
    public static Exit choose(YukkuriEntity mother,int index){
        var brood=mother.family().brood();
        if(brood==null||index<0||index>=brood.children.size())return none(mother,index,0);
        var child=brood.children.get(index);var zones=new ArrayList<Dropzone>();
        for(var hat:mother.level().getEntitiesOfClass(PlacedHatEntity.class,mother.getBoundingBox().inflate(3),h->h.catches()
                &&(mother.getUUID().equals(h.ownerYukkuri())||brood.father!=null&&brood.father.equals(h.ownerYukkuri()))))
            addSeats(zones,hat,brood,child,mother.position(),"catch_hat",1.25,0);
        var nest=FamilyNest.largeCentre(mother);
        if(nest!=null)zones.add(new Dropzone(nest,"nest",.8,1));
        return choose(mother,index,zones);
    }
    public static Exit choose(YukkuriEntity mother,int index,List<Dropzone> zones){
        var brood=mother.family().brood();
        if(!(mother.level() instanceof ServerLevel level)||brood==null||index<0||index>=brood.children.size())return none(mother,index,0);
        var child=brood.children.get(index);var angles=angles(mother,zones);Exit best=null;double score=Double.MAX_VALUE;
        for(float yaw:angles){
            double turn=Math.abs(Mth.wrapDegrees(yaw-mother.yBodyRot));
            var outlet=LiveBirthGeometry.position(mother,index,yaw);
            var box=LiveBirthGeometry.childDimensions(child,mother.getScale()).makeBoundingBox(outlet);
            if(!level.hasChunkAt(BlockPos.containing(outlet))||!level.getWorldBorder().isWithinBounds(box)
                    ||level.getBlockCollisions(mother,box).iterator().hasNext()||level.containsAnyLiquid(box))continue;
            var origin=mother.position().add(0,Math.max(.05,outlet.y-mother.getY()),0);
            if(level.clip(new ClipContext(origin,outlet,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother)).getType()!=HitResult.Type.MISS)continue;
            String kind=null;int priority=3;double distance=0;Dropzone target=null;
            for(var zone:zones){
                double d=outlet.subtract(zone.position()).horizontalDistance();
                if(d>zone.reach())continue;
                double candidate=zone.priority()*1000+turn+d*.1;
                double bestHere=kind==null?score:Math.min(score,priority*1000+turn+distance*.1);
                if(candidate>=bestHere)continue;
                if(zone.hat()!=null&&!flightClear(mother,child,yaw,outlet,zone))continue;
                if(kind==null||zone.priority()<priority||zone.priority()==priority&&d<distance){kind=zone.kind();priority=zone.priority();distance=d;target=zone;}
            }
            if(kind==null){
                if(!drySupport(mother,outlet))continue;
                kind="ground";priority=3;
            }
            double candidate=priority*1000+turn+distance*.1;
            if(candidate<score){score=candidate;best=new Exit(yaw,outlet,kind,"",angles.size(),target);}
        }
        return best==null?none(mother,index,angles.size()):best;
    }
    private static void addSeats(List<Dropzone> zones,PlacedHatEntity hat,FamilyData.Brood brood,FamilyData.Child child,
                                 Vec3 from,String kind,double reach,int priority){
        for(var seat:hat.birthSeatChoices(brood.kind,child,from))
            zones.add(new Dropzone(seat.position(),kind,reach,priority,hat,seat.slot(),seat.admission()));
    }
    /** Keep the selected real berth, but recheck its current transform and admission before every delivery tick. */
    public static boolean valid(YukkuriEntity mother,int index,Exit selected){
        if(selected==null||!selected.safe())return false;
        var brood=mother.family().brood();
        if(brood==null||index<0||index>=brood.children.size())return false;
        var target=selected.target();
        if(target==null)return clearOutlet(mother,index,selected.yaw())&&drySupport(mother,LiveBirthGeometry.position(mother,index,selected.yaw()));
        if(target.hat()==null){
            if(target.kind().equals("nest")&&FamilyNest.largeCentre(mother)==null)return false;
            return chooseAt(mother,index,selected.yaw(),target);
        }
        var hat=target.hat();
        if(hat.isRemoved()||!mother.getUUID().equals(hat.ownerYukkuri())&&(brood.father==null||!brood.father.equals(hat.ownerYukkuri())))return false;
        if(target.slot()<0)return !hat.isRemoved()&&hat.catches()&&chooseAt(mother,index,selected.yaw(),
                new Dropzone(hat.position(),target.kind(),target.reach(),target.priority(),hat,-1,target.admission()));
        for(var seat:hat.birthSeatChoices(brood.kind,brood.children.get(index),mother.position())){
            if(seat.slot()!=target.slot()||seat.admission()!=target.admission())continue;
            var now=new Dropzone(seat.position(),target.kind(),target.reach(),target.priority(),hat,seat.slot(),seat.admission());
            return chooseAt(mother,index,selected.yaw(),now);
        }
        return false;
    }
    private static boolean chooseAt(YukkuriEntity mother,int index,float yaw,Dropzone zone){
        var child=mother.family().brood().children.get(index);
        var outlet=LiveBirthGeometry.position(mother,index,yaw);
        return clearOutlet(mother,index,yaw)&&outlet.subtract(zone.position()).horizontalDistance()<=zone.reach()
                &&(zone.hat()==null||flightClear(mother,child,yaw,outlet,zone));
    }
    private static boolean clearOutlet(YukkuriEntity mother,int index,float yaw){
        return clearOutlet(mother,index,yaw,false);
    }
    /** Losing a cushion never permits birth through a wall; only liquid/support requirements are relaxed. */
    public static boolean unobstructed(YukkuriEntity mother,int index){return clearOutlet(mother,index,mother.yBodyRot,true);}
    private static boolean clearOutlet(YukkuriEntity mother,int index,float yaw,boolean allowLiquid){
        var child=mother.family().brood().children.get(index);
        var outlet=LiveBirthGeometry.position(mother,index,yaw);
        var box=LiveBirthGeometry.childDimensions(child,mother.getScale()).makeBoundingBox(outlet);
        var origin=mother.position().add(0,Math.max(.05,outlet.y-mother.getY()),0);
        return mother.level().hasChunkAt(BlockPos.containing(outlet))&&mother.level().getWorldBorder().isWithinBounds(box)
                &&!mother.level().getBlockCollisions(mother,box).iterator().hasNext()&&(allowLiquid||!mother.level().containsAnyLiquid(box))
                &&mother.level().clip(new ClipContext(origin,outlet,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother)).getType()==HitResult.Type.MISS;
    }
    /** The same local outlet projection also aligns a proposed land catch station. */
    public static float yawToward(YukkuriEntity mother,int index,Vec3 target){
        float best=mother.yBodyRot;double distance=Double.MAX_VALUE;
        for(float yaw:angles(mother,List.of(new Dropzone(target,"target",0,0)))){
            double d=LiveBirthGeometry.position(mother,index,yaw).distanceToSqr(target);
            if(d<distance){distance=d;best=yaw;}
        }
        return best;
    }
    /** After the finite safety wait, a real unobstructed outlet may have no cushion; water/fall consequences remain. */
    public static Exit unprotected(YukkuriEntity mother,int index){
        var brood=mother.family().brood();
        if(brood==null||index<0||index>=brood.children.size())return none(mother,index,0);
        for(float yaw:angles(mother,List.of())){
            var outlet=LiveBirthGeometry.position(mother,index,yaw);
            if(clearOutlet(mother,index,yaw,true))
                return new Exit(yaw,outlet,"unprotected","",16,null);
        }
        return none(mother,index,16);
    }
    private static List<Float> angles(YukkuriEntity mother,List<Dropzone> zones){
        var out=new ArrayList<Float>();
        for(int i=0;i<16;i++)out.add(Mth.wrapDegrees(mother.yBodyRot+i*22.5f));
        for(var zone:zones)out.add(SurfaceApproach.yaw(mother.position(),zone.position()));
        return out;
    }
    private static boolean drySupport(YukkuriEntity mother,Vec3 outlet){
        var from=outlet.add(0,.02,0);var to=outlet.add(0,-1.25,0);
        var hit=mother.level().clip(new ClipContext(from,to,ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,mother));
        return hit.getType()==HitResult.Type.BLOCK&&mother.level().getFluidState(hit.getBlockPos()).isEmpty();
    }
    private static boolean flightClear(YukkuriEntity mother,FamilyData.Child child,float yaw,Vec3 outlet,Dropzone zone){
        return BirthFlightPath.plan(BirthFlightPath.unborn(mother,child,yaw),zone.hat(),outlet,zone.position(),
                Math.max(HatGeometry.TMIN,LiveBirthGeometry.CATCH_ARC_TICKS))>0;
    }
    private static Exit none(YukkuriEntity mother,int index,int candidates){
        return new Exit(mother.yBodyRot,LiveBirthGeometry.position(mother,index),"none","no_safe_exit",candidates,null);
    }
    private BirthExit(){}
}
