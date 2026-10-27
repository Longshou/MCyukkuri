package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
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
    public record Dropzone(Vec3 position,String kind,double reach,int priority,HatBoatEntity hat){
        public Dropzone(Vec3 position,String kind,double reach,int priority){this(position,kind,reach,priority,null);}
    }
    public record Exit(float yaw,Vec3 outletPos,String targetKind,String reason,int candidates){
        public boolean safe(){return reason.isEmpty();}
    }
    public static Exit choose(YukkuriEntity mother,int index){
        var brood=mother.family().brood();
        if(brood==null||index<0||index>=brood.children.size())return none(mother,index,0);
        var child=brood.children.get(index);var zones=new ArrayList<Dropzone>();
        var mine=mother.family().boatHome();
        if(mine!=null&&mine.isAfloat()){
            for(var seat:mine.birthSeats(brood.kind,child,true,mother.position()))zones.add(new Dropzone(seat,"own_hat",1.25,0,mine));
            if(brood.father!=null&&mother.level() instanceof ServerLevel level&&level.getEntity(brood.father) instanceof YukkuriEntity father){
                var partner=father.family().boatHome();
                if(partner!=null&&partner!=mine&&partner.isAfloat())for(var seat:partner.birthSeats(brood.kind,child,false,mother.position()))
                    zones.add(new Dropzone(seat,"partner_hat",FamilyLife.SHARE_REACH,1,partner));
            }
        }else{
            for(var hat:mother.level().getEntitiesOfClass(HatBoatEntity.class,mother.getBoundingBox().inflate(3),h->h.catches()
                    &&(mother.getUUID().equals(h.ownerYukkuri())||brood.father!=null&&brood.father.equals(h.ownerYukkuri()))))
                zones.add(new Dropzone(hat.position(),"catch_hat",Math.max(.2,hat.getBbWidth()*.45),0));
            var nest=FamilyNest.largeCentre(mother);
            if(nest!=null)zones.add(new Dropzone(nest,"nest",.8,1));
        }
        return choose(mother,index,zones);
    }
    public static Exit choose(YukkuriEntity mother,int index,List<Dropzone> zones){
        var brood=mother.family().brood();
        if(!(mother.level() instanceof ServerLevel level)||brood==null||index<0||index>=brood.children.size())return none(mother,index,0);
        var child=brood.children.get(index);var angles=angles(mother,zones);Exit best=null;double score=Double.MAX_VALUE;
        boolean afloat=mother.family().boatHome()!=null;
        for(float yaw:angles){
            var outlet=LiveBirthGeometry.position(mother,index,yaw);
            var box=LiveBirthGeometry.childDimensions(child,mother.getScale()).makeBoundingBox(outlet);
            if(!level.hasChunkAt(BlockPos.containing(outlet))||!level.getWorldBorder().isWithinBounds(box)
                    ||level.getBlockCollisions(mother,box).iterator().hasNext()||level.containsAnyLiquid(box))continue;
            var origin=mother.position().add(0,Math.max(.05,outlet.y-mother.getY()),0);
            if(level.clip(new ClipContext(origin,outlet,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mother)).getType()!=HitResult.Type.MISS)continue;
            String kind=null;int priority=3;double distance=0;
            for(var zone:zones){
                double d=outlet.subtract(zone.position()).horizontalDistance();
                if(d>zone.reach()||afloat&&Math.abs(outlet.y-zone.position().y)>1)continue;
                if(zone.hat()!=null&&!flightClear(mother,child,outlet,zone))continue;
                if(kind==null||zone.priority()<priority||zone.priority()==priority&&d<distance){kind=zone.kind();priority=zone.priority();distance=d;}
            }
            if(kind==null){
                if(afloat||!drySupport(mother,outlet))continue;
                kind="ground";priority=3;
            }
            double candidate=priority*1000+Math.abs(Mth.wrapDegrees(yaw-mother.yBodyRot))+distance*.1;
            if(candidate<score){score=candidate;best=new Exit(yaw,outlet,kind,"",angles.size());}
        }
        return best==null?none(mother,index,angles.size()):best;
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
        var child=brood.children.get(index);
        for(float yaw:angles(mother,List.of())){
            var outlet=LiveBirthGeometry.position(mother,index,yaw);
            var box=LiveBirthGeometry.childDimensions(child,mother.getScale()).makeBoundingBox(outlet);
            if(mother.level().hasChunkAt(BlockPos.containing(outlet))&&mother.level().getWorldBorder().isWithinBounds(box)
                    &&!mother.level().getBlockCollisions(mother,box).iterator().hasNext())
                return new Exit(yaw,outlet,"unprotected","",16);
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
    private static boolean flightClear(YukkuriEntity mother,FamilyData.Child child,Vec3 outlet,Dropzone zone){
        var size=LiveBirthGeometry.childDimensions(child,mother.getScale());
        double radius=HatVessel.radius(size.width());
        int ticks=HatVessel.solve(outlet,zone.position(),zone.hat(),radius,LiveBirthGeometry.CATCH_ARC_TICKS);
        var points=new ArrayList<Vec3>();
        int samples=Math.max(32,ticks*4);
        for(int i=0;i<=samples;i++){
            var at=HatVessel.arc(outlet,zone.position(),ticks,ticks*i/(double)samples);
            points.add(at);
            if(mother.level().getBlockCollisions(mother,size.makeBoundingBox(at).deflate(.001)).iterator().hasNext())return false;
        }
        return HatVessel.clears(points,zone.hat(),radius);
    }
    private static Exit none(YukkuriEntity mother,int index,int candidates){
        return new Exit(mother.yBodyRot,LiveBirthGeometry.position(mother,index),"none","no_safe_exit",candidates);
    }
    private BirthExit(){}
}
