package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.BraidRescueGeometry;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One physical hat and one existing braid. All positions are world coordinates; the mesh is the
 * selected marisa_hat_turned prop, before its freeboard translation. No client pose interpolation. */
public final class SelfRescueHatGeometry {
    public static final int REACH=8, CONTACT=6, TURN=28, LOWER=16, REMOVE_TICKS=REACH+CONTACT+TURN+LOWER;
    private record Mesh(List<Vec3> vertices,List<Vec3[]> rimEdges){}
    private static final Mesh MESH=read();
    private static Mesh read(){
        try(var stream=SelfRescueHatGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/marisa_hat_turned.json")){
            if(stream==null)throw new IllegalStateException("Missing selected physical hat");
            var data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var raw=data.getAsJsonArray("vertices");var vertices=new ArrayList<Vec3>();var edges=new ArrayList<Vec3[]>();
            for(int i=0;i<raw.size();i+=8)vertices.add(new Vec3(raw.get(i).getAsDouble(),raw.get(i+1).getAsDouble(),raw.get(i+2).getAsDouble()));
            for(var entry:data.getAsJsonArray("parts")){
                var part=entry.getAsJsonObject();if(!part.get("name").getAsString().startsWith("brim"))continue;
                int begin=part.get("start").getAsInt(),end=begin+part.get("count").getAsInt();
                for(int i=begin;i<end;i+=3)for(int j=0;j<3;j++){
                    var a=vertices.get(i+j);var b=vertices.get(i+(j+1)%3);
                    // The owner's right OUTER edge. This is an existing mesh edge, not a point on an invented circle.
                    if(a.z<-HatVessel.BRIM[1]+.3&&b.z<-HatVessel.BRIM[1]+.3&&Math.abs(a.y)<.2&&Math.abs(b.y)<.2)edges.add(new Vec3[]{a,b});
                }
            }
            return new Mesh(List.copyOf(vertices),List.copyOf(edges));
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    public record Plan(Vec3 feet,float bodyYaw,Vec3 localRim,Vec3 firstGrip,Vec3 finalGrip,Vec3 finalCentre,float boatYaw){}
    public record Pose(Vec3 origin,Quaternionf rotation,float squash,Vec3 grip,Vec3 tip,Vec3 joint){
        public Vec3 world(Vec3 local,double unit){
            var p=new Vector3f((float)(local.x*unit),(float)(local.y*unit*squash),(float)(local.z*unit));rotation.transform(p);
            return origin.add(p.x,p.y,p.z);
        }
        /** Matrix of the original WORN model coordinates, including the aquatic scale about its real anchor. */
        public Matrix4f wornMatrix(Vec3 feet,float bodyYaw,double bodyUnit,double hatUnit){
            var offset=origin.subtract(feet);var inv=new Quaternionf().rotationY((float)Math.toRadians(bodyYaw-180));
            var p=new Vector3f((float)offset.x,(float)offset.y,(float)offset.z);inv.transform(p);p.mul((float)(1/bodyUnit));
            return new Matrix4f().translation(p).rotate(new Quaternionf(inv).mul(rotation))
                    .scale((float)(hatUnit/bodyUnit),(float)(hatUnit*squash/bodyUnit),(float)(hatUnit/bodyUnit))
                    .rotateY((float)Math.PI/2).rotateX((float)Math.PI).translate(HatVessel.anchor().negate());
        }
    }
    private static Vec3 transform(Quaternionf q,Vec3 p){var v=q.transform(new Vector3f((float)p.x,(float)p.y,(float)p.z));return new Vec3(v.x,v.y,v.z);}
    public static double bodyUnit(YukkuriEntity actor){return actor.growthScale()*actor.getScale()/16;}
    public static double hatUnit(YukkuriEntity actor){return bodyUnit(actor)*HatVessel.hatMultiplier(actor.subspeciesFlags());}
    private static Quaternionf facing(float yaw){return new Quaternionf().rotationY((float)Math.toRadians(180-yaw));}
    private static Vec3 anchor(){var a=HatVessel.anchor();return new Vec3(a.x,a.y,a.z);}
    public static Vec3 root(YukkuriEntity actor){return BraidRescueGeometry.root(actor,0);}
    public static Plan plan(YukkuriEntity actor,double waterY){
        var q=facing(actor.yBodyRot).rotateY((float)Math.PI/2).rotateX((float)Math.PI);
        var origin=actor.position().add(transform(facing(actor.yBodyRot),anchor().scale(bodyUnit(actor))));
        var desired=new Vec3(-BraidRescueGeometry.modelRoot().z/HatVessel.hatMultiplier(actor.subspeciesFlags()),0,-HatVessel.BRIM[1]);
        Plan best=null;double cost=Double.POSITIVE_INFINITY;var visited=new java.util.HashSet<Vec3>();
        for(var edge:MESH.rimEdges){
            var d=edge[1].subtract(edge[0]);double t=d.lengthSqr()<1e-10?0:Math.clamp(desired.subtract(edge[0]).dot(d)/d.lengthSqr(),0,1);
            var local=edge[0].add(d.scale(t));var grip=origin.add(transform(q,local.scale(hatUnit(actor))));
            if(!visited.add(local))continue;
            var finalGrip=new Vec3(grip.x,waterY+(HatVessel.FREEBOARD+local.y*HatVessel.SQUASH_FLOAT)*hatUnit(actor),grip.z);
            var endRotation=facing(actor.yBodyRot).rotateY((float)Math.PI/2);
            var propOrigin=finalGrip.subtract(transform(endRotation,new Vec3(local.x,local.y*HatVessel.SQUASH_FLOAT,local.z).scale(hatUnit(actor))));
            var centre=propOrigin.subtract(transform(endRotation,new Vec3(0,HatVessel.FREEBOARD*hatUnit(actor),0)));
            var candidate=new Plan(actor.position(),actor.yBodyRot,local,grip,finalGrip,centre,actor.yBodyRot-90);
            if(!BraidRescueGeometry.reachable(actor,root(actor),grip)||!BraidRescueGeometry.reachable(actor,root(actor),finalGrip))continue;
            double score=local.distanceToSqr(desired);if(score>=cost||!validPath(actor,candidate))continue;
            best=candidate;cost=score;
        }
        return best;
    }
    public static Pose pose(YukkuriEntity actor,Plan plan,double elapsed){
        double turn=smooth((elapsed-REACH-CONTACT)/TURN),lower=smooth((elapsed-REACH-CONTACT-TURN)/LOWER);
        var body=facing(plan.bodyYaw);var rotation=new Quaternionf(body).rotateZ((float)(-Math.PI*turn)).rotateY((float)Math.PI/2).rotateX((float)Math.PI);
        float squash=(float)(1+(HatVessel.SQUASH_FLOAT-1)*lower);
        var grip=plan.firstGrip.lerp(plan.finalGrip,lower);
        var local=new Vec3(plan.localRim.x,plan.localRim.y*squash,plan.localRim.z).scale(hatUnit(actor));
        var origin=grip.subtract(transform(rotation,local));
        var rest=BraidRescueGeometry.restTip(actor,0);var tip=rest.lerp(grip,smooth(elapsed/REACH));
        var bend=actor.position().add(transform(body,BraidRescueGeometry.modelJoint().scale(bodyUnit(actor))));
        var joint=BraidRescueGeometry.joint(actor,root(actor),tip,bend);
        return new Pose(origin,rotation,squash,grip,tip,joint);
    }
    private static double smooth(double q){q=Math.clamp(q,0,1);return q*q*(3-2*q);}
    /** A complete removal is planned only once. At runtime validate just the swept interval and current reach. */
    private static boolean validPath(YukkuriEntity actor,Plan plan){
        Pose previous=null;
        for(int tick=0;tick<=REMOVE_TICKS;tick+=2){var current=pose(actor,plan,tick);if(!valid(actor,plan,current,previous,tick>=REACH))return false;previous=current;}
        return true;
    }
    public static boolean valid(YukkuriEntity actor,Plan plan,Pose pose,Pose previous,boolean gripping){
        if(plan==null||pose==null||Math.abs(net.minecraft.util.Mth.wrapDegrees(actor.yBodyRot-plan.bodyYaw))>8)return false;
        if(gripping&&!BraidRescueGeometry.reachable(actor,root(actor),pose.grip))return false;
        var ray=actor.level().clip(new ClipContext(root(actor),pose.tip,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor));
        if(ray.getType()!=HitResult.Type.MISS)return false;
        WoundSurface skin;
        try{skin=WoundSurface.get(actor.subspecies().assetId());}catch(IllegalStateException unsupported){return false;}
        double body=bodyUnit(actor),unit=hatUnit(actor);
        var inverse=facing(actor.yBodyRot).conjugate();
        var verts=MESH.vertices;
        for(int i=0;i<verts.size();i+=3){
            var points=new Vector3f[3];
            for(int j=0;j<3;j++){
                var world=pose.world(verts.get(i+j),unit);
                if(!actor.level().hasChunkAt(net.minecraft.core.BlockPos.containing(world)))return false;
                if(previous!=null){
                    var from=previous.world(verts.get(i+j),unit);
                    if(actor.level().clip(new ClipContext(from,world,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()!=HitResult.Type.MISS)return false;
                }
                var relative=world.subtract(actor.position());var p=new Vector3f((float)relative.x,(float)relative.y,(float)relative.z);inverse.transform(p).mul((float)(1/body));points[j]=p;
            }
            // The actual hat triangle edges must not cross the selected body skin. Cast both directions:
            // WoundSurface intentionally uses front faces for wound hits, whereas custody needs both sides.
            for(int j=0;j<3;j++){
                var a=points[j];var b=points[(j+1)%3];var delta=new Vector3f(b).sub(a);float distance=delta.length();if(distance<1e-6)continue;delta.div(distance);
                var hit=skin.ray(a,delta,null,List.of());if(hit!=null&&hit.distance()<distance-.01)return false;
                hit=skin.ray(b,new Vector3f(delta).negate(),null,List.of());if(hit!=null&&hit.distance()<distance-.01)return false;
            }
        }
        return true;
    }
    private SelfRescueHatGeometry(){}
}
