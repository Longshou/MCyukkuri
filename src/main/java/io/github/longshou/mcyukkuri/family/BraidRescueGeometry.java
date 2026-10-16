package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** The one selected braid, including its supported root pose; shared by custody and rendering. */
public final class BraidRescueGeometry {
    private static final JsonObject DATA=read();
    private static final Vec3 POSED_ROOT=posed("braid_root"), POSED_TIP=posed("braid_tip");
    private static JsonObject read(){
        try(var stream=BraidRescueGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/braid_rescue.json")){
            if(stream==null)throw new IllegalStateException("Missing selected braid rescue geometry");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    private static Vec3 vector(String key){var p=DATA.getAsJsonArray(key);return new Vec3(p.get(0).getAsDouble(),p.get(1).getAsDouble(),p.get(2).getAsDouble());}
    public static Vec3 modelRoot(){return vector("braid_root");}
    public static Vec3 modelTip(){return vector("braid_tip");}
    public static int ticks(String phase){return Math.max(1,DATA.get(phase.toLowerCase(java.util.Locale.ROOT)+"_ticks").getAsInt());}
    public static double gripHeight(){return DATA.get("grip_height").getAsDouble();}
    public static Vec3 grip(YukkuriEntity child){return child.position().add(0,child.getBbHeight()*gripHeight(),0);}
    public static double length(YukkuriEntity parent){return DATA.get("braid_length").getAsDouble()*parent.growthScale()*parent.getScale()/16;}
    private static Vec3 posed(String key){
        var pivot=vector("root_pivot");var angles=vector("root_rotation");var source=vector(key);
        var matrix=new Matrix4f().translate((float)pivot.x,(float)(pivot.y+DATA.get("root_lift").getAsDouble()),(float)pivot.z)
                .rotateZYX((float)Math.toRadians(angles.z),(float)Math.toRadians(angles.y),(float)Math.toRadians(angles.x))
                .translate((float)-pivot.x,(float)-pivot.y,(float)-pivot.z);
        var at=matrix.transformPosition(new Vector3f((float)source.x,(float)source.y,(float)source.z));return new Vec3(at.x,at.y,at.z);
    }
    private static Vec3 offset(YukkuriEntity parent,String key){return (key.equals("braid_root")?POSED_ROOT:POSED_TIP)
            .scale(parent.growthScale()*parent.getScale()/16).yRot((float)Math.toRadians(180-parent.yBodyRot));}
    public static Vec3 root(YukkuriEntity parent){return rootAt(parent,parent.position());}
    public static Vec3 rootAt(YukkuriEntity parent,Vec3 feet){return feet.add(offset(parent,"braid_root"));}
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
        return height+.015;
    }
    public static boolean touches(YukkuriEntity child,Vec3 tip){return child.getBoundingBox().inflate(.008).contains(tip);}
    /** Refine a supported grid candidate onto the exact reach circle at its measured floor height. */
    public static Vec3 stand(YukkuriEntity parent,HatBoatEntity hat,Vec3 target,Vec3 candidate){
        double r=HatVessel.radius(parent.getBbWidth())/hat.unit();var at=candidate;
        for(int i=0;i<3;i++){
            var feet=hat.toWorld(at);var root=rootAt(parent,feet);double dy=target.y-root.y,l=length(parent);
            if(Math.abs(dy)>=l)return null;
            var direction=target.subtract(root).multiply(1,0,1);
            if(direction.lengthSqr()<1e-8)direction=new Vec3(1,0,0);
            var originOffset=root.subtract(feet);
            var wanted=target.subtract(originOffset).subtract(direction.normalize().scale(Math.sqrt(l*l-dy*dy)));
            var local=hat.toLocal(wanted);double floor=HatVessel.footprint(hat.mode(),local.x,local.z,r);
            if(!Double.isFinite(floor))return null;at=new Vec3(local.x,floor,local.z);
        }
        return Math.abs(rootAt(parent,hat.toWorld(at)).distanceTo(target)-length(parent))<=.004?at:null;
    }
    public static boolean canLift(YukkuriEntity parent,YukkuriEntity child){
        double parentVolume=parent.getBbWidth()*parent.getBbWidth()*parent.getBbHeight();
        double childVolume=child.getBbWidth()*child.getBbWidth()*child.getBbHeight();
        double strength=Math.clamp((parent.life().energy()-8)/60.0,.2,1);
        return parent.life().energy()>=10&&childVolume<=parentVolume*.24*strength;
    }
    private BraidRescueGeometry(){}
}
