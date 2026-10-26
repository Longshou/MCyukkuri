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
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The one selected braid, including its supported root pose; shared by custody and rendering. */
public final class BraidRescueGeometry {
    private static final JsonObject DATA=read();
    private static final Vec3 POSED_ROOT=posed("braid_root"), POSED_TIP=posed("braid_tip");
    private static final Vec3 MODEL_JOINT=vector("tip_joint");
    /** Project-original bite grip (not canon): the mouth anchor in model units and its contact reach in blocks of a standard adult. */
    private static final Vec3 MOUTH_ROOT=DATA.has("mouth_root")?vector("mouth_root"):new Vec3(0,2.0034,-5.215);
    private static final double MOUTH_REACH=DATA.has("mouth_reach")?DATA.get("mouth_reach").getAsDouble():.03;
    private static JsonObject read(){
        try(var stream=BraidRescueGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json")){
            if(stream==null)throw new IllegalStateException("Missing selected braid rescue geometry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException error){throw new UncheckedIOException(error);}
    }
    private static Vec3 vector(String key){var p=DATA.getAsJsonArray(key);return new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());}
    public static Vec3 modelRoot(){return vector("braid_root");}
    public static Vec3 modelTip(){return vector("braid_tip");}
    public static Vec3 modelJoint(){return MODEL_JOINT;}
    public static double firstLength(YukkuriEntity parent){return modelJoint().distanceTo(modelRoot())*parent.growthScale()*parent.getScale()/16;}
    public static double secondLength(YukkuriEntity parent){return modelTip().distanceTo(modelJoint())*parent.growthScale()*parent.getScale()/16;}
    /** The ribbon-like braid may fold a little tighter than two rigid links could (it curls); the tip then rests a few centimetres off the line. */
    public static double minimumReach(YukkuriEntity parent){return .7*Math.abs(firstLength(parent)-secondLength(parent))+.005*parent.growthScale()*parent.getScale();}
    public static double maximumReach(YukkuriEntity parent){return firstLength(parent)+secondLength(parent)-.005*parent.growthScale()*parent.getScale();}
    public static int ticks(String phase){return Math.max(1,DATA.get(phase.toLowerCase(Locale.ROOT)+"_ticks").getAsInt());}
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
    public static Vec3 rootAt(YukkuriEntity parent,Vec3 feet){return rootAtYaw(parent,feet,parent.yBodyRot);}
    /** Same unextended braid root, at the heading used for the supported rescue stand. */
    public static Vec3 rootAtYaw(YukkuriEntity parent,Vec3 feet,float yaw){return feet.add(POSED_ROOT
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-yaw)));}
    /** braid_rescue.json reach_lean: the parent sinks her braid side toward a child floating low (clip braid_reach_low). */
    private static final JsonObject LEAN=DATA.has("reach_lean")?DATA.getAsJsonObject("reach_lean"):null;
    public static int leanTicks(){return LEAN==null?1:Math.max(1,LEAN.get("ease_ticks").getAsInt());}
    /**
     * The braid root of a parent standing at these feet and heading, with her reach lean at this blend (0 upright, 1 full):
     * exactly the root-bone pose braid_reach_low plays (shift + lift, then Rz*Ry*Rx and the squash about the root pivot).
     */
    public static Vec3 leanRootAtYaw(YukkuriEntity parent,Vec3 feet,float yaw,double blend){
        if(LEAN==null||blend<=0)return rootAtYaw(parent,feet,yaw);
        float q=(float)Math.clamp(blend,0,1);
        var pivot=vector("root_pivot");var source=modelRoot();
        var scale=LEAN.getAsJsonArray("scale");var shift=LEAN.getAsJsonArray("shift");
        float lift=DATA.has("reach_lean_lift")?DATA.get("reach_lean_lift").getAsFloat():0;
        var matrix=new Matrix4f().translate((float)pivot.x+shift.get(0).getAsFloat()*q,(float)pivot.y+(shift.get(1).getAsFloat()+lift)*q,(float)pivot.z+shift.get(2).getAsFloat()*q)
                .rotateZYX((float)Math.toRadians(LEAN.get("roll").getAsDouble()*q),(float)Math.toRadians(LEAN.get("yaw").getAsDouble()*q),(float)Math.toRadians(LEAN.get("pitch").getAsDouble()*q))
                .scale(1+(scale.get(0).getAsFloat()-1)*q,1+(scale.get(1).getAsFloat()-1)*q,1+(scale.get(2).getAsFloat()-1)*q)
                .translate((float)-pivot.x,(float)-pivot.y,(float)-pivot.z);
        var at=matrix.transformPosition(new Vector3f((float)source.x,(float)source.y,(float)source.z));
        return feet.add(new Vec3(at.x,at.y,at.z).scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-yaw)));
    }
    /** Nearest point on the child's actual body surface; the braid must touch before it can pull. */
    public static Vec3 rescueContact(YukkuriEntity child,Vec3 feet,Vec3 root){
        var box=child.getBoundingBox().move(feet.subtract(child.position()));
        return new Vec3(Math.clamp(root.x,box.minX,box.maxX),Math.clamp(root.y,box.minY,box.maxY),Math.clamp(root.z,box.minZ,box.maxZ));
    }
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
    /** The mouth of an upright body standing at these feet, turned to this body yaw. */
    public static Vec3 mouthPoint(YukkuriEntity actor,Vec3 feet,float yawDeg){return boardingPoint(actor,feet,MOUTH_ROOT,0,0,yawDeg);}
    /** The mouth at the boarding pose's pitch and roll (they rotate about the same root pivot as the body envelope). */
    public static Vec3 mouthPoint(YukkuriEntity actor,Vec3 feet,double pitch,double roll,float yawDeg){return boardingPoint(actor,feet,MOUTH_ROOT,pitch,roll,yawDeg);}
    /** How far below the hull's waterline the bowl's outer wall can still be bitten: a swimmer floats with its mouth under the surface. */
    public static double biteAllowance(YukkuriEntity actor){
        double size=Math.min(1,actor.growthScale()*actor.getScale());
        return Math.clamp(.35*size+.05,.1,.4);
    }
    /** Allowed mouth-to-rim contact distance in blocks, scaled with this body. */
    public static double biteReach(YukkuriEntity actor){
        // The mouth sits a little behind the front of the collision box; a body that rests against a wall touches it with its face.
        return MOUTH_REACH*actor.growthScale()*actor.getScale()+.15*actor.getBbWidth();
    }
    /** Looser distance within which the teeth stay hooked while the body lunges, still measured from the mouth. */
    public static double holdReach(YukkuriEntity actor){return biteReach(actor)+.40*actor.growthScale()*actor.getScale();}
    /** Mouth distance to the visible rim wall: a vertical segment from the lip down to the waterline. */
    public static double rimDistance(Vec3 mouth,Vec3 lip,double wall){
        double y=Math.clamp(mouth.y,lip.y-Math.max(0,wall),lip.y);
        return mouth.distanceTo(new Vec3(lip.x,y,lip.z));
    }
    public static boolean bites(YukkuriEntity actor,Vec3 mouth,Vec3 lip,double wall){return rimDistance(mouth,lip,wall)<=biteReach(actor)+1e-6;}
    public static boolean holds(YukkuriEntity actor,Vec3 mouth,Vec3 lip,double wall){return rimDistance(mouth,lip,wall)<=holdReach(actor);}
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
    public static List<AABB> boardingEnvelope(YukkuriEntity actor,Vec3 feet,double pitch,double roll){
        return boardingEnvelope(actor,feet,pitch,roll,actor.yBodyRot);
    }
    public static List<AABB> boardingEnvelope(YukkuriEntity actor,Vec3 feet,double pitch,double roll,float yaw){
        if(actor.getVariant()!=YukkuriVariant.MARISA||!DATA.has("body_min"))return envelope(actor,feet);
        var boxes=new ArrayList<AABB>();
        if(DATA.has("boarding_body_bounds")){
            var rows=DATA.getAsJsonArray("boarding_body_bounds");AABB body=null;
            // The exported table spans its own pitch/roll grid (default -18..0 / -3..3); a pose beyond it is that edge row turned rigidly.
            int pitchLow=DATA.has("boarding_body_pitch_min")?DATA.get("boarding_body_pitch_min").getAsInt():-18;
            int rollLow=-(rows.get(0).getAsJsonArray().size()-1)/2;
            double wanted=Math.clamp(pitch,-8,6),row=Math.clamp(wanted,pitchLow,pitchLow+rows.size()-1);
            double column=Math.clamp(roll,rollLow,rollLow+rows.get(0).getAsJsonArray().size()-1);
            for(int p:new int[]{(int)Math.floor(row),(int)Math.ceil(row)})
                for(int r:new int[]{(int)Math.floor(column),(int)Math.ceil(column)}){
                    var bounds=rows.get(p-pitchLow).getAsJsonArray().get(r-rollLow).getAsJsonArray();
                    var low=bounds.get(0).getAsJsonArray();var high=bounds.get(1).getAsJsonArray();
                    var min=new Vec3(low.get(0).getAsDouble(),low.get(1).getAsDouble(),low.get(2).getAsDouble());
                    var max=new Vec3(high.get(0).getAsDouble(),high.get(1).getAsDouble(),high.get(2).getAsDouble());
                    var box=boardingBox(actor,feet,min,max,wanted-row,0,yaw);body=body==null?box:body.minmax(box);
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
    /** Model-space bounds {min,max} of the posed body, from the same exported pitch/roll table the envelope uses. */
    private static Vec3[] bodyBounds(double pitch,double roll){
        if(!DATA.has("boarding_body_bounds"))return rotatedBounds(vector("body_min"),vector("body_max"),pitch,roll);
        var rows=DATA.getAsJsonArray("boarding_body_bounds");
        int pitchLow=DATA.has("boarding_body_pitch_min")?DATA.get("boarding_body_pitch_min").getAsInt():-18;
        int rollLow=-(rows.get(0).getAsJsonArray().size()-1)/2;
        double wanted=Math.clamp(pitch,-8,6),row=Math.clamp(wanted,pitchLow,pitchLow+rows.size()-1);
        double column=Math.clamp(roll,rollLow,rollLow+rows.get(0).getAsJsonArray().size()-1);
        Vec3 min=null,max=null;
        for(int p:new int[]{(int)Math.floor(row),(int)Math.ceil(row)})
            for(int r:new int[]{(int)Math.floor(column),(int)Math.ceil(column)}){
                var bounds=rows.get(p-pitchLow).getAsJsonArray().get(r-rollLow).getAsJsonArray();
                var low=bounds.get(0).getAsJsonArray();var high=bounds.get(1).getAsJsonArray();
                var a=new Vec3(low.get(0).getAsDouble(),low.get(1).getAsDouble(),low.get(2).getAsDouble());
                var b=new Vec3(high.get(0).getAsDouble(),high.get(1).getAsDouble(),high.get(2).getAsDouble());
                min=min==null?a:new Vec3(Math.min(min.x,a.x),Math.min(min.y,a.y),Math.min(min.z,a.z));
                max=max==null?b:new Vec3(Math.max(max.x,b.x),Math.max(max.y,b.y),Math.max(max.z,b.z));
            }
        return wanted==row?new Vec3[]{min,max}:rotatedBounds(min,max,wanted-row,0);
    }
    private static Vec3[] rotatedBounds(Vec3 min,Vec3 max,double pitch,double roll){
        var pivot=vector("root_pivot");var rotation=new Quaternionf().rotationZYX((float)Math.toRadians(roll),0,(float)Math.toRadians(pitch));
        Vec3 low=null,high=null;
        for(int i=0;i<8;i++){
            var corner=new Vec3((i&1)==0?min.x:max.x,(i&2)==0?min.y:max.y,(i&4)==0?min.z:max.z).subtract(pivot);
            var v=new Vector3f((float)corner.x,(float)corner.y,(float)corner.z);rotation.transform(v);
            var at=new Vec3(v.x,v.y,v.z).add(pivot);
            low=low==null?at:new Vec3(Math.min(low.x,at.x),Math.min(low.y,at.y),Math.min(low.z,at.z));
            high=high==null?at:new Vec3(Math.max(high.x,at.x),Math.max(high.y,at.y),Math.max(high.z,at.z));
        }
        return new Vec3[]{low,high};
    }
    /** Lowest feet height at which the posed body clears this hull: the underside is sampled on its real turned
     *  footprint rather than the world-aligned box (whose corners would overlap the hat at any oblique heading). */
    public static double poseClearance(YukkuriEntity actor,Vec3 feet,double pitch,double roll,float yaw,HatBoatEntity hat,HatBoatEntity.Dip dip){
        return Math.max(feet.y,poseSupport(actor,feet,pitch,roll,yaw,hat,dip));
    }
    /** Hull support under this pose, independent of its current height; -infinity outside the hull.
     *  A falling body uses this same surface to find first contact before moving through it. */
    public static double poseSupport(YukkuriEntity actor,Vec3 feet,double pitch,double roll,float yaw,HatBoatEntity hat,HatBoatEntity.Dip dip){
        var boxes=boardingEnvelope(actor,feet,pitch,roll,yaw);
        if(actor.getVariant()!=YukkuriVariant.MARISA||!DATA.has("body_min"))return envelopeSupport(boxes,hat,feet,dip);
        double required=envelopeSupport(boxes.subList(1,boxes.size()),hat,feet,dip);
        var tilt=dip==null?hat.dip():dip;var bounds=bodyBounds(pitch,roll);
        for(int x=0;x<=2;x++)for(int z=0;z<=2;z++){
            var model=new Vec3(bounds[0].x+(bounds[1].x-bounds[0].x)*x/2,bounds[0].y,bounds[0].z+(bounds[1].z-bounds[0].z)*z/2);
            var at=boardingPoint(actor,feet,model,0,0,yaw);var p=hat.toLocal(at);
            double floor=HatVessel.surface(hat.mode(),p.x,p.z);
            if(Double.isFinite(floor))required=Math.max(required,hat.toWorld(new Vec3(p.x,floor,p.z)).y-HatBoatEntity.rimLift(p,tilt,hat.unit())-(at.y-feet.y));
        }
        return required;
    }
    public static boolean reachable(YukkuriEntity parent,Vec3 root,Vec3 tip){
        double reach=root.distanceTo(tip);return reach>=minimumReach(parent)&&reach<=maximumReach(parent);
    }
    /** Two real existing rigid bone lengths meet at this joint; flex never extends either segment. */
    public static Vec3 joint(YukkuriEntity parent,Vec3 root,Vec3 tip,Vec3 bendHint){
        double distance=root.distanceTo(tip),a=firstLength(parent),b=secondLength(parent);
        if(distance<1e-8)return null;
        var axis=tip.subtract(root).scale(1/distance);
        double along=Math.min(a,(a*a-b*b+distance*distance)/(2*distance));
        var hint=bendHint.subtract(root);var side=hint.subtract(axis.scale(hint.dot(axis)));
        if(side.lengthSqr()<1e-8)side=axis.cross(new Vec3(0,1,0));
        if(side.lengthSqr()<1e-8)side=new Vec3(1,0,0);
        return root.add(axis.scale(along)).add(side.normalize().scale(Math.sqrt(Math.max(0,a*a-along*along))));
    }
    /** The full worn-hat envelope participates in transfer collision, not only the feet or body centre. */
    public static List<AABB> envelope(YukkuriEntity actor,Vec3 feet){
        var boxes=new ArrayList<AABB>();boxes.add(actor.getBoundingBox().move(feet.subtract(actor.position())));
        if(actor.getVariant()==YukkuriVariant.MARISA&&actor.life().hasAccessory()){
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
            if(Double.isFinite(floor))height=Math.max(height,hat.toWorld(new Vec3(local.x,floor,local.z)).y-hat.rimLift(local));
        }
        return height+.008*child.growthScale()*child.getScale();
    }
    public static double envelopeClearance(YukkuriEntity child,HatBoatEntity hat,Vec3 feet){
        return envelopeClearance(envelope(child,feet),hat,feet);
    }
    public static double envelopeClearance(List<AABB> boxes,HatBoatEntity hat,Vec3 feet){
        return envelopeClearance(boxes,hat,feet,null);
    }
    /** Which box/sample/surface point demands the greatest clearance (diagnostics only). */
    public static String clearanceWhy(List<AABB> boxes,HatBoatEntity hat,Vec3 feet){
        var tilt=hat.dip();double required=feet.y;String why="none";int index=0;
        for(var box:boxes){
            double offset=box.minY-feet.y;
            for(int x=0;x<=2;x++)for(int z=0;z<=2;z++){
                var world=new Vec3(box.minX+(box.maxX-box.minX)*x/2,feet.y,box.minZ+(box.maxZ-box.minZ)*z/2);
                var p=hat.toLocal(world);
                double floor=HatVessel.surface(hat.mode(),p.x,p.z);
                if(Double.isFinite(floor)){
                    double need=hat.toWorld(new Vec3(p.x,floor,p.z)).y-HatBoatEntity.rimLift(p,tilt,hat.unit())-offset;
                    if(need>required){required=need;why="box"+index+" sample("+x+","+z+") world="+world+" local="+p+" floor="+floor+" offset="+offset+" lift="
                            +HatBoatEntity.rimLift(p,tilt,hat.unit());}
                }
            }
            index++;
        }
        return why;
    }
    /** As above, with the hull's near rim lowered by this dip (null means the hat's current one). */
    public static double envelopeClearance(List<AABB> boxes,HatBoatEntity hat,Vec3 feet,HatBoatEntity.Dip dip){
        return Math.max(feet.y,envelopeSupport(boxes,hat,feet,dip));
    }
    private static double envelopeSupport(List<AABB> boxes,HatBoatEntity hat,Vec3 feet,HatBoatEntity.Dip dip){
        var tilt=dip==null?hat.dip():dip;double required=Double.NEGATIVE_INFINITY;
        for(var box:boxes){
            double offset=box.minY-feet.y;
            for(int x=0;x<=2;x++)for(int z=0;z<=2;z++){
                var p=hat.toLocal(new Vec3(box.minX+(box.maxX-box.minX)*x/2,feet.y,box.minZ+(box.maxZ-box.minZ)*z/2));
                double floor=HatVessel.surface(hat.mode(),p.x,p.z);
                if(Double.isFinite(floor))required=Math.max(required,hat.toWorld(new Vec3(p.x,floor,p.z)).y-HatBoatEntity.rimLift(p,tilt,hat.unit())-offset);
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
    /** The one lift rule: the carried body's volume against 24% of the carrier's, scaled by her energy. */
    public static boolean canLift(YukkuriEntity parent,YukkuriEntity child){return canLift(parent,child,false);}
    /** {@code atFullStrength}: the size ceiling alone, which waiting (resting, recovering energy) can never change. */
    public static boolean canLift(YukkuriEntity parent,YukkuriEntity child,boolean atFullStrength){
        double parentVolume=parent.getBbWidth()*parent.getBbWidth()*parent.getBbHeight();
        double childVolume=child.getBbWidth()*child.getBbWidth()*child.getBbHeight();
        double strength=atFullStrength?1:Math.clamp((parent.life().energy()-8)/60.0,.2,1);
        return (atFullStrength||parent.life().energy()>=10)&&childVolume<=parentVolume*.24*strength;
    }
    private BraidRescueGeometry(){}
}
