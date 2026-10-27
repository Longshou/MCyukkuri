package io.github.longshou.mcyukkuri.subspecies;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import net.minecraft.nbt.CompoundTag;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import java.util.function.Function;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/** One physical hat shaken off the head. All positions are world coordinates; the mesh is the selected
 * marisa_hat_turned prop, before its freeboard translation. No braid takes part: the hat is shaken loose,
 * slides over the face to the planned water spot and settles there. No client pose interpolation. */
public final class SelfRescueHatGeometry {
    /** Shaking loose, sliding off over the face while turning over, then settling on the water. */
    public static final int SHAKE=12, TURN=14, LOWER=10, REMOVE_TICKS=SHAKE+TURN+LOWER;
    private record Mesh(List<Vec3> vertices,List<int[]> faces,List<int[]> edges){}
    private static final Mesh MESH=read();
    private static final Map<String,AABB> SKIN_BOUNDS=new HashMap<>();
    private static Mesh read(){
        try(var stream=SelfRescueHatGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/marisa_hat_turned.json")){
            if(stream==null)throw new IllegalStateException("Missing selected physical hat");
            var data=JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
            var raw=data.getAsJsonArray("vertices");var indices=new LinkedHashMap<Vec3,Integer>();
            var faces=new ArrayList<int[]>();var edges=new LinkedHashMap<Long,int[]>();
            for(int i=0;i<raw.size();i+=24){
                var face=new int[3];
                for(int j=0;j<3;j++){int at=i+j*8;var point=new Vec3(raw.get(at).getAsDouble(),raw.get(at+1).getAsDouble(),raw.get(at+2).getAsDouble());
                    face[j]=indices.computeIfAbsent(point,p->indices.size());}
                faces.add(face);
                for(int j=0;j<3;j++){int a=Math.min(face[j],face[(j+1)%3]),b=Math.max(face[j],face[(j+1)%3]);
                    edges.putIfAbsent(((long)a<<32)|b,new int[]{a,b});}
            }
            return new Mesh(List.copyOf(indices.keySet()),List.copyOf(faces),List.copyOf(edges.values()));
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    /** The same physical hat keeps its unit after deployment records the owner's first boat experience. */
    public record Plan(Vec3 feet,float bodyYaw,Vec3 finalCentre,float boatYaw,double hatUnit){}
    private static String lastFailure;
    public static String lastFailure(){return lastFailure==null?"none":lastFailure;}
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
    public static double bodyUnit(YukkuriView actor){return actor.growthScale()*actor.getScale()/16;}
    public static double hatUnit(YukkuriView actor){return bodyUnit(actor)*HatVessel.hatMultiplier(actor.subspeciesFlags());}
    public record Motion(String clip,float seconds) {}
    /** Only observes the executed phase and its clock; presentation and surface queries sample the same frame. */
    public static Motion motion(YukkuriView actor,float partial) {
        var state=actor.aquaticSelfRescueState();
        if(!actor.getBehaviorId().startsWith(Behaviors.WATER_HAT_PREFIX)||state.getString("Phase").isEmpty())return null;
        float elapsed=Math.max(0,(actor.level().getGameTime()-state.getLong("Start")+partial)/20f);
        return switch(state.getString("Phase")){
            case "REMOVE_HAT"->new Motion("water_hat_shake",1.2f*Mth.clamp(elapsed/(state.contains("RemoveTicks")?Math.max(1,state.getInt("RemoveTicks"))/20f:1.8f),0,1));
            case "DEPLOY_HAT"->new Motion("water_hat_shake",1.2f);
            case "GRIP"->new Motion("water_mouth_grip",elapsed%.8f);
            case "RIGHT_HAT"->{int index=Mth.clamp(state.getInt("PushIndex"),0,2);float local=elapsed>=index?elapsed-index:elapsed;
                yield new Motion("water_hat_push",Math.min(.8f,Math.max(0,local)));}
            case "CLIMB"->new Motion("water_mouth_grip",elapsed%.8f);
            case "SLIPPED"->new Motion("water_gasp",elapsed%1.6f);
            case "SAFE"->new Motion("water_recover",Math.min(2.8f,elapsed));
            default->null;
        };
    }
    /** Read only the already executed and synchronized carried-hat pose; this never plans or advances a rescue. */
    public static Matrix4f wornMatrix(YukkuriView actor,float partial,float bodyYaw) {
        var state=actor.aquaticSelfRescueState();
        if(!actor.getBehaviorId().startsWith(Behaviors.WATER_HAT_PREFIX)||!actor.hasVisibleAccessory()||!state.getBoolean("HatPose"))return null;
        float q=Math.clamp(partial,0,1);
        var rotation=rotation(state,"PrevHat").slerp(rotation(state,"Hat"),q);
        var origin=point(state,"PrevHat").lerp(point(state,"Hat"),q);
        float squash=Mth.lerp(q,state.getFloat("PrevHatSquash"),state.getFloat("HatSquash"));
        var pose=new Pose(origin,rotation,squash,Vec3.ZERO,Vec3.ZERO,null);
        double unit=state.contains("RemovalHatUnit")?state.getDouble("RemovalHatUnit"):hatUnit(actor);
        return pose.wornMatrix(actor.getPosition(q),bodyYaw,bodyUnit(actor),unit);
    }
    private static Quaternionf rotation(CompoundTag state,String key) {
        return new Quaternionf(state.getFloat(key+"QX"),state.getFloat(key+"QY"),state.getFloat(key+"QZ"),state.getFloat(key+"QW")).normalize();
    }
    private static Vec3 point(CompoundTag state,String key) {
        return new Vec3(state.getDouble(key+"X"),state.getDouble(key+"Y"),state.getDouble(key+"Z"));
    }
    private static Quaternionf facing(float yaw){return new Quaternionf().rotationY((float)Math.toRadians(180-yaw));}
    private static Vec3 anchor(){var a=HatVessel.anchor();return new Vec3(a.x,a.y,a.z);}
    private static Vec3 wornOrigin(YukkuriView actor,Plan plan){return plan.feet().add(transform(facing(plan.bodyYaw()),anchor().scale(bodyUnit(actor))));}
    private static Vec3 settledOrigin(YukkuriView actor,Plan plan){return plan.finalCentre().add(0,HatVessel.FREEBOARD*plan.hatUnit(),0);}
    /** The first spot in front of the face, along a small choice of headings, that the caller finds usable and the whole shake keeps clear. */
    public static Plan plan(YukkuriEntity actor,double waterY,Predicate<Plan> usable){
        double size=Math.min(1,actor.growthScale()*actor.getScale()),depth=HatVessel.BRIM[0]*hatUnit(actor),half=actor.getBbWidth()*.5;
        int unusable=0,invalid=0;
        for(float turn:new float[]{0,25,-25})for(double gap:new double[]{.05,.12,.22}){
            float yaw=actor.yBodyRot+turn;double radians=Math.toRadians(yaw),distance=half+depth+gap*Math.max(.3,size);
            var centre=new Vec3(actor.getX()-Math.sin(radians)*distance,waterY,actor.getZ()+Math.cos(radians)*distance);
            var candidate=new Plan(actor.position(),yaw,centre,yaw-90,hatUnit(actor));
            if(!usable.test(candidate)){unusable++;continue;}
            lastFailure=null;
            if(validPath(actor,candidate))return candidate;
            invalid++;
        }
        GameplayLog.detail(actor,"self.geometry."+actor.getId(),40,"no hat spot: unusable="+unusable+" invalid="+invalid+" lastFailure="+lastFailure);
        return null;
    }
    public static Pose pose(YukkuriView actor,Plan plan,double elapsed){
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
        for(int tick=0;tick<=REMOVE_TICKS;tick++){var current=pose(actor,plan,tick);if(!clear(actor,plan,current,previous)){lastFailure="tick "+tick+" yaw="+
                plan.bodyYaw()+": "+lastFailure;return false;}previous=current;}
        return true;
    }
    public static boolean valid(YukkuriEntity actor,Plan plan,Pose pose,Pose previous){
        lastFailure=null;
        if(plan==null||pose==null){lastFailure="missing_pose";return false;}
        if(Math.abs(Mth.wrapDegrees(actor.yBodyRot-plan.bodyYaw()))>8){lastFailure="body_yaw_changed";return false;}
        return clear(actor,plan,pose,previous);
    }
    private static boolean clear(YukkuriEntity actor,Plan plan,Pose pose,Pose previous){
        double unit=plan.hatUnit();
        return meshClear(actor,actor.position(),plan.bodyYaw(),p->pose.world(p,unit),
                previous==null?null:p->previous.world(p,unit),true);
    }
    /** Raw selected prop coordinates, with exactly the renderer's top translation and cloth squash. */
    private static Vec3 hullLocal(HatBoatEntity hat,Vec3 raw){
        return new Vec3(raw.x,HatVessel.top(hat.mode())+raw.y*hat.hullSquash(1),raw.z);
    }
    /** The body takes a bounded step past the broad hull box only after its real skin clears the rotated prop. */
    public static boolean bodyStepClear(YukkuriEntity actor,HatBoatEntity hat,Vec3 destination){
        var shift=destination.subtract(actor.position());
        Function<Vec3,Vec3> at=p->hat.hazards().tiltedWorld(hullLocal(hat,p));
        return meshClear(actor,destination,actor.yBodyRot,at,p->at.apply(p).add(shift),false);
    }
    /** Validate the actual rotating hat skin against the holder and blocks before moving the hull around its held rim. */
    public static boolean rightingClear(YukkuriEntity actor,HatBoatEntity hat,Vec3 centre,float progress){
        Function<Vec3,Vec3> before=p->hat.hazards().tiltedWorld(hullLocal(hat,p));
        Function<Vec3,Vec3> after=p->centre.add(hat.selfRightingOffset(hullLocal(hat,p),progress));
        return meshClear(actor,actor.position(),actor.yBodyRot,after,before,true);
    }
    private static boolean meshClear(YukkuriEntity actor,Vec3 feet,float yaw,Function<Vec3,Vec3> current,
                                     Function<Vec3,Vec3> previous,boolean checkBlocks){
        WoundSurface skin;
        try{skin=WoundSurface.get(actor.subspecies().assetId());}catch(IllegalStateException unsupported){lastFailure="no skin "+unsupported.getMessage();return false;}
        double body=bodyUnit(actor);
        var inverse=facing(yaw).conjugate();
        var verts=MESH.vertices();
        var bounds=SKIN_BOUNDS.computeIfAbsent(actor.subspecies().assetId(),id->{
            AABB box=null;
            for(int i=0;i<skin.skinCount;i++){
                var p=skin.rest(i);var point=new Vec3(p.x,p.y,p.z);var at=new AABB(point,point);
                box=box==null?at:box.minmax(at);
            }
            return box.inflate(.01);
        });
        var worldPoints=new Vec3[verts.size()];var oldWorld=new Vec3[verts.size()];AABB swept=null;
        for(int i=0;i<verts.size();i++){
            var at=current.apply(verts.get(i));var before=previous==null?at:previous.apply(verts.get(i));
            worldPoints[i]=at;oldWorld[i]=before;var box=new AABB(at,before);swept=swept==null?box:swept.minmax(box);
        }
        boolean blocks=checkBlocks&&swept!=null&&actor.level().getBlockCollisions(actor,swept.inflate(.001)).iterator().hasNext();
        var points=new Vector3f[verts.size()];var before=new Vector3f[verts.size()];
        for(int i=0;i<verts.size();i++){
            var world=worldPoints[i];var old=oldWorld[i];
            if(!actor.level().hasChunkAt(BlockPos.containing(world))){lastFailure="chunk";return false;}
            if(blocks&&old.distanceToSqr(world)>1e-12&&actor.level().clip(new ClipContext(old,world,
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,actor)).getType()!=HitResult.Type.MISS){lastFailure="block_sweep";return false;}
            points[i]=bodyPoint(world,feet,inverse,body);before[i]=bodyPoint(old,feet,inverse,body);
            if(previous!=null&&box(points[i],before[i]).intersects(bounds)&&crosses(skin,before[i],points[i])){
                lastFailure="swept_hat_vertex_crosses_skin vertex="+i;return false;}
        }
        for(var edge:MESH.edges())if(box(points[edge[0]],points[edge[1]]).intersects(bounds)
                &&crosses(skin,points[edge[0]],points[edge[1]])){lastFailure="hat_edge_crosses_skin";return false;}
        // A small body may enter the middle of a large hat face without touching any hat edge. Query the existing skin BVH
        // only in each swept face box, and test skin edges and reverse vertex sweeps against that exact face.
        var oldToCurrent=previous==null?null:bodyTransform(current,feet,inverse,body)
                .mul(bodyTransform(previous,feet,inverse,body).invert());
        for(var face:MESH.faces()){
            var a=points[face[0]];var b=points[face[1]];var c=points[face[2]];
            var area=box(a,b).minmax(box(c,before[face[0]])).minmax(box(before[face[1]],before[face[2]])).inflate(.01);
            if(!area.intersects(bounds))continue;
            if(skin.anySkinTriangle((float)area.minX,(float)area.minY,(float)area.minZ,
                    (float)area.maxX,(float)area.maxY,(float)area.maxZ,index->{
                for(int j=0;j<3;j++){
                    var at=skin.rest(index+j);var next=skin.rest(index+(j+1)%3);
                    if(segmentHits(at,next,a,b,c)){lastFailure="skin_edge_crosses_hat_face";return true;}
                    if(oldToCurrent!=null&&segmentHits(oldToCurrent.transformPosition(new Vector3f(at)),at,a,b,c)){
                        lastFailure="swept_skin_vertex_crosses_hat_face";return true;}
                }
                return false;
            }))return false;
        }
        return true;
    }
    private static Vector3f bodyPoint(Vec3 point,Vec3 feet,Quaternionf inverse,double unit){
        var p=point.subtract(feet);return inverse.transform(new Vector3f((float)p.x,(float)p.y,(float)p.z)).mul((float)(1/unit));
    }
    private static AABB box(Vector3f a,Vector3f b){return new AABB(new Vec3(a.x,a.y,a.z),new Vec3(b.x,b.y,b.z));}
    private static Matrix4f bodyTransform(Function<Vec3,Vec3> frame,Vec3 feet,Quaternionf inverse,double unit){
        var o=bodyPoint(frame.apply(Vec3.ZERO),feet,inverse,unit);
        var x=bodyPoint(frame.apply(new Vec3(1,0,0)),feet,inverse,unit).sub(o);
        var y=bodyPoint(frame.apply(new Vec3(0,1,0)),feet,inverse,unit).sub(o);
        var z=bodyPoint(frame.apply(new Vec3(0,0,1)),feet,inverse,unit).sub(o);
        return new Matrix4f().m00(x.x).m01(x.y).m02(x.z).m10(y.x).m11(y.y).m12(y.z)
                .m20(z.x).m21(z.y).m22(z.z).m30(o.x).m31(o.y).m32(o.z);
    }
    private static boolean segmentHits(Vector3f from,Vector3f to,Vector3f a,Vector3f b,Vector3f c){
        var direction=new Vector3f(to).sub(from);float length=direction.length();if(length<=.02)return false;
        var e=new Vector3f(b).sub(a);var f=new Vector3f(c).sub(a);var h=new Vector3f(direction).cross(f);
        float det=e.dot(h);if(Math.abs(det)<1e-7)return false;
        var s=new Vector3f(from).sub(a);float u=s.dot(h)/det;if(u<0||u>1)return false;
        var q=s.cross(e);float v=direction.dot(q)/det;if(v<0||u+v>1)return false;
        float t=f.dot(q)/det;return t>.01/length&&t<1-.01/length;
    }
    private static boolean crosses(WoundSurface skin,Vector3f a,Vector3f b){
        var direction=new Vector3f(b).sub(a);float distance=direction.length();if(distance<=.02)return false;
        direction.div(distance);
        var hit=skin.ray(a,direction,null,List.of());
        if(hit!=null&&hit.distance()>.01&&hit.distance()<distance-.01)return true;
        hit=skin.ray(b,new Vector3f(direction).negate(),null,List.of());
        return hit!=null&&hit.distance()>.01&&hit.distance()<distance-.01;
    }
    private SelfRescueHatGeometry(){}
}
