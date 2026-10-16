package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The one selected braid, including its supported root pose; shared by custody and rendering. */
public final class BraidRescueGeometry {
    private static final JsonObject DATA=read();
    private static final Vec3 POSED_ROOT=posed("braid_root"), POSED_TIP=posed("braid_tip");
    private static final Vec3 MODEL_JOINT=vector("tip_joint");
    private static JsonObject read(){
        try(var stream=BraidRescueGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json")){
            if(stream==null)throw new IllegalStateException("Missing selected braid rescue geometry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    private static Vec3 vector(String key){var p=DATA.getAsJsonArray(key);return new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());}
    public static Vec3 modelRoot(){return vector("braid_root");}
    public static Vec3 modelTip(){return vector("braid_tip");}
    public static Vec3 modelJoint(){return MODEL_JOINT;}
    public static double firstLength(YukkuriEntity parent){return modelJoint().distanceTo(modelRoot())*parent.growthScale()*parent.getScale()/16;}
    public static double secondLength(YukkuriEntity parent){return modelTip().distanceTo(modelJoint())*parent.growthScale()*parent.getScale()/16;}
    public static double minimumReach(YukkuriEntity parent){return Math.abs(firstLength(parent)-secondLength(parent))+.005*parent.growthScale()*parent.getScale();}
    public static double maximumReach(YukkuriEntity parent){return firstLength(parent)+secondLength(parent)-.005*parent.growthScale()*parent.getScale();}
    public static int ticks(String phase){return Math.max(1,DATA.get(phase.toLowerCase(java.util.Locale.ROOT)+"_ticks").getAsInt());}
    public static double gripHeight(){return DATA.get("grip_height").getAsDouble();}
    /** Body-mesh support at the actual intermediate pitch, not a linear lift guessed from the final lean. */
    public static double rootLift(double blend){
        blend=Math.clamp(blend,0,1);
        if(!DATA.has("root_support_samples"))return DATA.get("root_lift").getAsDouble()*blend;
        var samples=DATA.getAsJsonArray("root_support_samples");double at=blend*(samples.size()-1);
        int a=(int)Math.floor(at),b=Math.min(samples.size()-1,a+1);
        return samples.get(a).getAsDouble()+(samples.get(b).getAsDouble()-samples.get(a).getAsDouble())*(at-a);
    }
    public static Vec3 grip(YukkuriEntity child){return child.position().add(0,child.getBbHeight()*gripHeight(),0);}
    public static double length(YukkuriEntity parent){return DATA.get("braid_length").getAsDouble()*parent.growthScale()*parent.getScale()/16;}
    private static Vec3 posed(String key){return posed(key,1);}
    private static Vec3 posed(String key,double blend){
        var pivot=vector("root_pivot");var angles=vector("root_rotation");var source=vector(key);
        var matrix=new Matrix4f().translate((float)pivot.x,(float)(pivot.y+rootLift(blend)),(float)pivot.z)
                .rotateZYX((float)Math.toRadians(angles.z*blend),(float)Math.toRadians(angles.y*blend),(float)Math.toRadians(angles.x*blend))
                .translate((float)-pivot.x,(float)-pivot.y,(float)-pivot.z);
        var at=matrix.transformPosition(new Vector3f((float)source.x,(float)source.y,(float)source.z));return new Vec3(at.x,at.y,at.z);
    }
    private static Vec3 offset(YukkuriEntity parent,String key){return (key.equals("braid_root")?POSED_ROOT:POSED_TIP)
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-parent.yBodyRot));}
    public static Vec3 root(YukkuriEntity parent){return rootAt(parent,parent.position());}
    public static Vec3 rootAt(YukkuriEntity parent,Vec3 feet){return feet.add(offset(parent,"braid_root"));}
    public static Vec3 rootAt(YukkuriEntity parent,Vec3 feet,double blend){return feet.add(posed("braid_root",blend)
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-parent.yBodyRot)));}
    public static Vec3 root(YukkuriEntity parent,double blend){return rootAt(parent,parent.position(),blend);}
    public static Vec3 restTip(YukkuriEntity parent,double blend){return parent.position().add(posed("braid_tip",blend)
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-parent.yBodyRot)));}
    public static Vec3 restJoint(YukkuriEntity parent,double blend){return parent.position().add(posed("tip_joint",blend)
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-parent.yBodyRot)));}
    private static Vec3 boardingPoint(YukkuriEntity actor,Vec3 feet,Vec3 model,double pitch,double roll){
        return boardingPoint(actor,feet,model,pitch,roll,actor.yBodyRot);
    }
    private static Vec3 boardingPoint(YukkuriEntity actor,Vec3 feet,Vec3 model,double pitch,double roll,float yaw){
        var pivot=vector("root_pivot");
        var delta=model.subtract(pivot);var vector=new Vector3f((float)delta.x,(float)delta.y,(float)delta.z);
        new Quaternionf().rotationZYX((float)Math.toRadians(roll),0,(float)Math.toRadians(pitch)).transform(vector);
        var at=new Vec3(vector.x,vector.y,vector.z).add(pivot);
        return feet.add(at.scale(actor.growthScale()*actor.getScale()/16).yRot((float)Math.toRadians(180-yaw)));
    }
    public static Vec3 boardingRoot(YukkuriEntity actor,Vec3 feet,double pitch,double roll){return boardingPoint(actor,feet,modelRoot(),pitch,roll);}
    public static Vec3 boardingRoot(YukkuriEntity actor,Vec3 feet,double pitch,double roll,float yaw){return boardingPoint(actor,feet,modelRoot(),pitch,roll,yaw);}
    public static Vec3 boardingRestJoint(YukkuriEntity actor,Vec3 feet,double pitch,double roll){return boardingPoint(actor,feet,modelJoint(),pitch,roll);}
    private static AABB boardingBox(YukkuriEntity actor,Vec3 feet,Vec3 min,Vec3 max,double pitch,double roll){
        return boardingBox(actor,feet,min,max,pitch,roll,actor.yBodyRot);
    }
    private static AABB boardingBox(YukkuriEntity actor,Vec3 feet,Vec3 min,Vec3 max,double pitch,double roll,float yaw){
        AABB box=null;
        for(int i=0;i<8;i++){
            var corner=new Vec3((i&1)==0?min.x:max.x,(i&2)==0?min.y:max.y,(i&4)==0?min.z:max.z);
            var at=boardingPoint(actor,feet,corner,pitch,roll,yaw);var point=new AABB(at,at);box=box==null?point:box.minmax(point);
        }
        return box;
    }
    public static java.util.List<AABB> boardingEnvelope(YukkuriEntity actor,Vec3 feet,double pitch,double roll){
        return boardingEnvelope(actor,feet,pitch,roll,actor.yBodyRot);
    }
    public static java.util.List<AABB> boardingEnvelope(YukkuriEntity actor,Vec3 feet,double pitch,double roll,float yaw){
        if(actor.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA||!DATA.has("body_min"))return envelope(actor,feet);
        var boxes=new java.util.ArrayList<AABB>();
        if(DATA.has("boarding_body_bounds")){
            var rows=DATA.getAsJsonArray("boarding_body_bounds");AABB body=null;
            for(int p:new int[]{(int)Math.floor(Math.clamp(pitch,-18,0)),(int)Math.ceil(Math.clamp(pitch,-18,0))})
                for(int r:new int[]{(int)Math.floor(Math.clamp(roll,-3,3)),(int)Math.ceil(Math.clamp(roll,-3,3))}){
                    var bounds=rows.get(p+18).getAsJsonArray().get(r+3).getAsJsonArray();
                    var low=bounds.get(0).getAsJsonArray();var high=bounds.get(1).getAsJsonArray();
                    var min=new Vec3(low.get(0).getAsDouble(),low.get(1).getAsDouble(),low.get(2).getAsDouble());
                    var max=new Vec3(high.get(0).getAsDouble(),high.get(1).getAsDouble(),high.get(2).getAsDouble());
                    var box=boardingBox(actor,feet,min,max,0,0,yaw);body=body==null?box:body.minmax(box);
                }
            boxes.add(body);
        }else boxes.add(boardingBox(actor,feet,vector("body_min"),vector("body_max"),pitch,roll,yaw));
        if(actor.life().hasAccessory()){
            double multiplier=HatVessel.hatScale(actor)/Math.max(.001,actor.growthScale());
            double base=HatVessel.anchor().y,height=DATA.get("hat_height_model_units").getAsDouble()*multiplier;
            boxes.add(boardingBox(actor,feet,new Vec3(-HatVessel.BRIM[0]*multiplier,base,-HatVessel.BRIM[1]*multiplier),
                    new Vec3(HatVessel.BRIM[0]*multiplier,base+height,HatVessel.BRIM[1]*multiplier),pitch,roll,yaw));
        }
        return boxes;
    }
    public static boolean reachable(YukkuriEntity parent,Vec3 root,Vec3 tip){
        double reach=root.distanceTo(tip);return reach>=minimumReach(parent)&&reach<=maximumReach(parent);
    }
    /** Two real existing rigid bone lengths meet at this joint; flex never extends either segment. */
    public static Vec3 joint(YukkuriEntity parent,Vec3 root,Vec3 tip,Vec3 bendHint){
        double distance=root.distanceTo(tip),a=firstLength(parent),b=secondLength(parent);
        if(distance<1e-8)return null;
        var axis=tip.subtract(root).scale(1/distance);
        double along=(a*a-b*b+distance*distance)/(2*distance);
        var hint=bendHint.subtract(root);var side=hint.subtract(axis.scale(hint.dot(axis)));
        if(side.lengthSqr()<1e-8)side=axis.cross(new Vec3(0,1,0));
        if(side.lengthSqr()<1e-8)side=new Vec3(1,0,0);
        return root.add(axis.scale(along)).add(side.normalize().scale(Math.sqrt(Math.max(0,a*a-along*along))));
    }
    /** The full worn-hat envelope participates in transfer collision, not only the feet or body centre. */
    public static java.util.List<AABB> envelope(YukkuriEntity actor,Vec3 feet){
        var boxes=new java.util.ArrayList<AABB>();boxes.add(actor.getBoundingBox().move(feet.subtract(actor.position())));
        if(actor.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&actor.life().hasAccessory()){
            double unit=HatVessel.hatScale(actor)*actor.getScale()/16;
            double yaw=Math.toRadians(actor.yBodyRot),hx=Math.abs(Math.cos(yaw))*HatVessel.BRIM[0]*unit+Math.abs(Math.sin(yaw))*HatVessel.BRIM[1]*unit;
            double hz=Math.abs(Math.sin(yaw))*HatVessel.BRIM[0]*unit+Math.abs(Math.cos(yaw))*HatVessel.BRIM[1]*unit;
            double base=HatVessel.anchor().y*actor.growthScale()*actor.getScale()/16;
            double height=(DATA.has("hat_height_model_units")?DATA.get("hat_height_model_units").getAsDouble():8)*unit;
            boxes.add(new AABB(feet.x-hx,feet.y+base,feet.z-hz,feet.x+hx,feet.y+base+height,feet.z+hz));
        }
        return boxes;
    }
    /** Actual selected body mesh bounds at the supported lean, with its real yaw. */
    public static AABB parentBody(YukkuriEntity parent,Vec3 feet){
        if(!DATA.has("posed_body_min"))return parent.getBoundingBox().move(feet.subtract(parent.position()));
        var min=vector("posed_body_min");var max=vector("posed_body_max");
        AABB bounds=null;double unit=parent.growthScale()*parent.getScale()/16;
        for(int i=0;i<8;i++){
            var p=new Vec3((i&1)==0?min.x:max.x,(i&2)==0?min.y:max.y,(i&4)==0?min.z:max.z)
                    .scale(unit).yRot((float)Math.toRadians(180-parent.yBodyRot)).add(feet);
            var point=new AABB(p,p);bounds=bounds==null?point:bounds.minmax(point);
        }
        return bounds;
    }
    public static Vec3 restTip(YukkuriEntity parent){return parent.position().add(offset(parent,"braid_tip"));}
    public static Vec3 tipOnReach(YukkuriEntity parent,Vec3 target){
        var origin=root(parent);var direction=target.subtract(origin);
        if(direction.lengthSqr()<1e-10)direction=restTip(parent).subtract(origin);
        return origin.add(direction.normalize().scale(length(parent)));
    }
    /** Shortest angular motion on the natural-length sphere; never a body flight or a stretched braid. */
    public static Vec3 tip(YukkuriEntity parent,Vec3 fromDirection,Vec3 toDirection,double progress){
        var a=fromDirection.normalize();var b=toDirection.normalize();
        var from=new Vector3f((float)a.x,(float)a.y,(float)a.z);var to=new Vector3f((float)b.x,(float)b.y,(float)b.z);
        float amount=(float)Math.clamp(progress,0,1);amount=amount*amount*(3-2*amount);
        var rotation=new Quaternionf().rotationTo(from,to);new Quaternionf().slerp(rotation,amount).transform(from);
        return root(parent).add(new Vec3(from.x,from.y,from.z).scale(length(parent)));
    }
    public static int travelTicks(YukkuriEntity parent,Vec3 fromDirection,Vec3 toDirection,String phase){
        double angle=Math.acos(Math.clamp(fromDirection.normalize().dot(toDirection.normalize()),-1,1));
        return Math.max(ticks(phase),(int)Math.ceil(1.5*angle*length(parent)/.075));
    }
    /** Keep the grip at the needed rim clearance, on the same natural-length sphere.
     * A vertical lift above the parent is never an intermediate waypoint. */
    public static Vec3 lowTip(YukkuriEntity parent,Vec3 direction,double height){
        var root=root(parent);double dy=height-root.y,l=length(parent);
        if(Math.abs(dy)>=l)return null;
        var horizontal=direction.multiply(1,0,1);
        if(horizontal.lengthSqr()<1e-8)return null;
        return root.add(horizontal.normalize().scale(Math.sqrt(l*l-dy*dy))).add(0,dy,0);
    }
    public static Vec3 lowArc(YukkuriEntity parent,Vec3 fromDirection,Vec3 toDirection,double fromHeight,double toHeight,double progress){
        double q=Math.clamp(progress,0,1);q=q*q*(3-2*q);
        double from=Math.atan2(fromDirection.z,fromDirection.x),to=Math.atan2(toDirection.z,toDirection.x);
        double angle=from+Math.atan2(Math.sin(to-from),Math.cos(to-from))*q;
        return lowTip(parent,new Vec3(Math.cos(angle),0,Math.sin(angle)),fromHeight+(toHeight-fromHeight)*q);
    }
    /** Measured body footprint clearance along the short transfer across this side of the brim. */
    public static double rimClearance(YukkuriEntity child,HatBoatEntity hat,Vec3 destination){
        double height=Math.max(child.getY(),destination.y),r=HatVessel.radius(child.getBbWidth())/hat.unit();
        int samples=Math.max(1,(int)Math.ceil(child.position().distanceTo(destination)/.025));
        for(int i=0;i<=samples;i++){
            var local=hat.toLocal(child.position().lerp(destination,(double)i/samples));
            double floor=HatVessel.footprint(hat.mode(),local.x,local.z,r);
            if(Double.isFinite(floor))height=Math.max(height,hat.toWorld(new Vec3(local.x,floor,local.z)).y);
        }
        return height+.008*child.growthScale()*child.getScale();
    }
    public static double envelopeClearance(YukkuriEntity child,HatBoatEntity hat,Vec3 feet){
        return envelopeClearance(envelope(child,feet),hat,feet);
    }
    public static double envelopeClearance(java.util.List<AABB> boxes,HatBoatEntity hat,Vec3 feet){
        double required=feet.y;
        for(var box:boxes){
            double offset=box.minY-feet.y;
            for(int x=0;x<=2;x++)for(int z=0;z<=2;z++){
                var p=hat.toLocal(new Vec3(box.minX+(box.maxX-box.minX)*x/2,feet.y,box.minZ+(box.maxZ-box.minZ)*z/2));
                double floor=HatVessel.surface(hat.mode(),p.x,p.z);
                if(Double.isFinite(floor))required=Math.max(required,hat.toWorld(new Vec3(p.x,floor,p.z)).y-offset);
            }
        }
        return required;
    }
    /** Low planar detour; its finite whole-body sweep, not this curve alone, decides whether it can be used. */
    public static Vec3 transferPoint(Vec3 from,Vec3 to,double side,double height,double q){
        q=Math.clamp(q,0,1);q=q*q*(3-2*q);
        var direction=to.subtract(from).multiply(1,0,1).normalize();var perpendicular=new Vec3(-direction.z,0,direction.x);
        var at=from.lerp(to,q).add(perpendicular.scale(4*side*q*(1-q)));
        return new Vec3(at.x,height,at.z);
    }
    public static double effortProgress(double q){return HatBoarding.pullProgress(q);}
    public static boolean touches(YukkuriEntity child,Vec3 tip){return child.getBoundingBox().inflate(.008).contains(tip);}
    /** Refine a supported grid candidate onto the exact reach circle at its measured floor height. */
    public static Vec3 stand(YukkuriEntity parent,HatBoatEntity hat,Vec3 target,Vec3 candidate){
        double r=HatVessel.radius(parent.getBbWidth())/hat.unit();var at=candidate;
        for(int i=0;i<3;i++){
            var feet=hat.toWorld(at);var root=rootAt(parent,feet);
            if(reachable(parent,root,target))return at;
            double dy=target.y-root.y,l=Math.clamp(root.distanceTo(target),minimumReach(parent),maximumReach(parent));
            if(Math.abs(dy)>=l)return null;
            var direction=target.subtract(root).multiply(1,0,1);
            if(direction.lengthSqr()<1e-8)direction=new Vec3(1,0,0);
            var originOffset=root.subtract(feet);
            var wanted=target.subtract(originOffset).subtract(direction.normalize().scale(Math.sqrt(l*l-dy*dy)));
            var local=hat.toLocal(wanted);double floor=HatVessel.footprint(hat.mode(),local.x,local.z,r);
            if(!Double.isFinite(floor))return null;at=new Vec3(local.x,floor,local.z);
        }
        return reachable(parent,rootAt(parent,hat.toWorld(at)),target)?at:null;
    }
    public static boolean canLift(YukkuriEntity parent,YukkuriEntity child){
        double parentVolume=parent.getBbWidth()*parent.getBbWidth()*parent.getBbHeight();
        double childVolume=child.getBbWidth()*child.getBbWidth()*child.getBbHeight();
        double strength=Math.clamp((parent.life().energy()-8)/60.0,.2,1);
        return parent.life().energy()>=10&&childVolume<=parentVolume*.24*strength;
    }
    private BraidRescueGeometry(){}
}
