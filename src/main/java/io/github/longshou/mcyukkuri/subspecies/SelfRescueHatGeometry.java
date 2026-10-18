package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One physical hat shaken off the head. All positions are world coordinates; the mesh is the selected
 * marisa_hat_turned prop, before its freeboard translation. No braid takes part: the hat is shaken loose,
 * slides over the face to the planned water spot and settles there. No client pose interpolation. */
public final class SelfRescueHatGeometry {
    /** Shaking loose, sliding off over the face while turning over, then settling on the water. */
    public static final int SHAKE=12, TURN=14, LOWER=10, REMOVE_TICKS=SHAKE+TURN+LOWER;
    private record Mesh(List<Vec3> vertices){}
    private static final Mesh MESH=read();
    private static Mesh read(){
        try(var stream=SelfRescueHatGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/marisa_hat_turned.json")){
            if(stream==null)throw new IllegalStateException("Missing selected physical hat");
            var data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var raw=data.getAsJsonArray("vertices");var vertices=new ArrayList<Vec3>();
            for(int i=0;i<raw.size();i+=8)vertices.add(new Vec3(raw.get(i).getAsDouble(),raw.get(i+1).getAsDouble(),raw.get(i+2).getAsDouble()));
            return new Mesh(List.copyOf(vertices));
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    public record Plan(Vec3 feet,float bodyYaw,Vec3 finalCentre,float boatYaw){}
    private static String lastFailure;
    /** grip and tip are unused now (nothing grips the hat); they remain so the client can rebuild the same pose. */
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
    private static Vec3 wornOrigin(YukkuriEntity actor,Plan plan){return plan.feet().add(transform(facing(plan.bodyYaw()),anchor().scale(bodyUnit(actor))));}
    private static Vec3 settledOrigin(YukkuriEntity actor,Plan plan){return plan.finalCentre().add(0,HatVessel.FREEBOARD*hatUnit(actor),0);}
    /** The first spot in front of the face, along a small choice of headings, that the caller finds usable and the whole shake keeps clear. */
    public static Plan plan(YukkuriEntity actor,double waterY,Predicate<Plan> usable){
        double size=Math.min(1,actor.growthScale()*actor.getScale()),depth=HatVessel.BRIM[0]*hatUnit(actor),half=actor.getBbWidth()*.5;
        int unusable=0,invalid=0;
        for(float turn:new float[]{0,25,-25})for(double gap:new double[]{.05,.12,.22}){
            float yaw=actor.yBodyRot+turn;double radians=Math.toRadians(yaw),distance=half+depth+gap*Math.max(.3,size);
            var centre=new Vec3(actor.getX()-Math.sin(radians)*distance,waterY,actor.getZ()+Math.cos(radians)*distance);
            var candidate=new Plan(actor.position(),yaw,centre,yaw-90);
            if(!usable.test(candidate)){unusable++;continue;}
            lastFailure=null;
            if(validPath(actor,candidate))return candidate;
            invalid++;
        }
        AquaDiag.log(actor,"self.geometry."+actor.getId(),40,"no hat spot: unusable="+unusable+" invalid="+invalid+" lastFailure="+lastFailure);
        return null;
    }
    public static Pose pose(YukkuriEntity actor,Plan plan,double elapsed){
        double up=smooth((elapsed-3)/(SHAKE-3.0)),slide=smooth((elapsed-SHAKE)/TURN),lower=smooth((elapsed-SHAKE-TURN)/LOWER);
        double wobble=Math.sin(elapsed*Math.PI/2)*.08*(1-up);
        // The hat is wider than the body: it first travels upright and high over the face, and only turns over (roll about the
        // facing axis) once it hangs in front of the body, on its way down to the water. Rolling above the body swept the
        // brim through its flank (project fix: the earlier order made every shake-off path invalid).
        var rotation=new Quaternionf(facing(plan.bodyYaw())).rotateY((float)wobble).rotateZ((float)(-Math.PI*lower)).rotateY((float)Math.PI/2).rotateX((float)Math.PI);
        var worn=wornOrigin(actor,plan);var settled=settledOrigin(actor,plan);
        double lifted=worn.y+9*bodyUnit(actor)*up;
        var origin=new Vec3(worn.x+(settled.x-worn.x)*slide,lifted+(settled.y-lifted)*lower,worn.z+(settled.z-worn.z)*slide);
        float squash=(float)(1+(HatVessel.SQUASH_FLOAT-1)*lower);
        return new Pose(origin,rotation,squash,origin,origin,null);
    }
    private static double smooth(double q){q=Math.clamp(q,0,1);return q*q*(3-2*q);}
    /** A complete shake is planned only once. At runtime validate just the swept interval. */
    private static boolean validPath(YukkuriEntity actor,Plan plan){
        Pose previous=null;
        for(int tick=0;tick<=REMOVE_TICKS;tick+=2){var current=pose(actor,plan,tick);if(!clear(actor,plan,current,previous)){lastFailure="tick "+tick+" yaw="+plan.bodyYaw()+": "+lastFailure;return false;}previous=current;}
        return true;
    }
    public static boolean valid(YukkuriEntity actor,Plan plan,Pose pose,Pose previous){
        return plan!=null&&pose!=null&&Math.abs(net.minecraft.util.Mth.wrapDegrees(actor.yBodyRot-plan.bodyYaw()))<=8&&clear(actor,plan,pose,previous);
    }
    private static boolean clear(YukkuriEntity actor,Plan plan,Pose pose,Pose previous){
        WoundSurface skin;
        try{skin=WoundSurface.get(actor.subspecies().assetId());}catch(IllegalStateException unsupported){lastFailure="no skin "+unsupported.getMessage();return false;}
        double body=bodyUnit(actor),unit=hatUnit(actor);
        var inverse=facing(plan.bodyYaw()).conjugate();
        var verts=MESH.vertices();
        for(int i=0;i<verts.size();i+=3){
            var points=new Vector3f[3];
            for(int j=0;j<3;j++){
                var world=pose.world(verts.get(i+j),unit);
                if(!actor.level().hasChunkAt(net.minecraft.core.BlockPos.containing(world))){lastFailure="chunk";return false;}
                if(previous!=null){
                    var from=previous.world(verts.get(i+j),unit);
                    if(actor.level().clip(new ClipContext(from,world,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()!=HitResult.Type.MISS){lastFailure="block hit between "+from+" and "+world;return false;}
                }
                var relative=world.subtract(actor.position());var p=new Vector3f((float)relative.x,(float)relative.y,(float)relative.z);inverse.transform(p).mul((float)(1/body));points[j]=p;
            }
            // The actual hat triangle edges must not cross the selected body skin. Cast both directions:
            // WoundSurface intentionally uses front faces for wound hits, whereas custody needs both sides.
            for(int j=0;j<3;j++){
                var a=points[j];var b=points[(j+1)%3];var delta=new Vector3f(b).sub(a);float distance=delta.length();if(distance<1e-6)continue;delta.div(distance);
                var hit=skin.ray(a,delta,null,List.of());if(hit!=null&&hit.distance()<distance-.01){lastFailure="hat edge crosses the body skin (front) tri="+(i/3)+" d="+hit.distance()+"<"+distance+" a="+a+" b="+b;return false;}
                hit=skin.ray(b,new Vector3f(delta).negate(),null,List.of());if(hit!=null&&hit.distance()<distance-.01){lastFailure="hat edge crosses the body skin (back) tri="+(i/3)+" d="+hit.distance()+"<"+distance+" a="+a+" b="+b;return false;}
            }
        }
        return true;
    }
    private SelfRescueHatGeometry(){}
}
