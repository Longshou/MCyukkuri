package io.github.longshou.mcyukkuri.subspecies;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Real selected mouth anchors, under the same stable carry root tracks used by the renderer. */
public final class HatCareAnchor {
    private static final JsonObject MOUTHS=mouths(), MOTIONS=read("motion_corrections.json").getAsJsonObject("models");
    private record PoseMouth(Vec3 mouth,Matrix4f root){}
    private static final Map<String,PoseMouth> POSED=new ConcurrentHashMap<>();
    private static JsonObject read(String name){
        try(var stream=HatCareAnchor.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/"+name)){
            if(stream==null)throw new IllegalStateException("Missing selected carry presentation "+name);
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    private static JsonObject mouths(){
        var out=read("living.json").getAsJsonObject("variants");
        for(var entry:read("subspecies.json").entrySet())out.add(entry.getKey(),entry.getValue());return out;
    }
    private static Vec3 posed(YukkuriEntity actor,String action){
        String id=actor.subspecies().assetId();
        PoseMouth pose=POSED.computeIfAbsent(id+":"+action,key->{
            if(!MOUTHS.has(id)||!MOTIONS.has(id))return null;
            var mouth=MOUTHS.getAsJsonObject(id).getAsJsonArray("mouth");
            var model=MOTIONS.getAsJsonObject(id);var names=model.getAsJsonArray("bone_names");int root=-1;
            if(mouth==null||!model.has("clips")||!model.getAsJsonObject("clips").has(action))return null;
            for(int i=0;i<names.size();i++)if(names.get(i).getAsString().equals("root")){root=i;break;}
            if(root<0)return null;
            float[] values={0,0,0,0,0,0,1,1,1};
            for(var raw:model.getAsJsonObject("clips").getAsJsonObject(action).getAsJsonArray("tracks")){
                var track=raw.getAsJsonObject();if(track.get("bone").getAsInt()!=root)continue;
                int offset=switch(track.get("channel").getAsString()){case "position"->0;case "rotation"->3;default->6;};
                var keys=track.getAsJsonArray("keys");var first=keys.get(0).getAsJsonArray();
                // Custody uses a stable mouth pose; changing this source clip must change the shared anchor contract too.
                for(var sample:keys)for(int axis=1;axis<=3;axis++)if(Math.abs(sample.getAsJsonArray().get(axis).getAsFloat()-first.get(axis).getAsFloat())>.0001)
                    return null;
                for(int axis=0;axis<3;axis++)values[offset+axis]=first.get(axis+1).getAsFloat();
            }
            return new PoseMouth(new Vec3(mouth.get(0).getAsDouble(),mouth.get(1).getAsDouble(),mouth.get(2).getAsDouble()),WoundSurface.get(id).rootPose(values));
        });
        if(pose==null)return null;
        var at=new Vector3f((float)pose.mouth().x,(float)pose.mouth().y,(float)pose.mouth().z);
        io.github.longshou.mcyukkuri.family.LiveBirthGeometry.deform(at,actor.livePregnancyProgress(),0);
        pose.root().transformPosition(at);
        return new Vec3(at.x,at.y,at.z).scale(actor.getScale()*actor.growthScale()/16);
    }
    /** Hat-local entity offset that makes the two rendered mouths coincide, with opposite faces. */
    public static Vec3 offset(YukkuriEntity parent,YukkuriEntity child,Vec3 direction,double unit){
        Vec3 parentMouth=posed(parent,"boat_carry_young"), childMouth=posed(child,"boat_be_carried");
        if(parentMouth==null||childMouth==null)return null;
        childMouth=childMouth.yRot((float)Math.PI);
        double rotation=Math.atan2(-direction.x,-direction.z);
        return parentMouth.subtract(childMouth).yRot((float)rotation).scale(1/unit);
    }
    public static Vec3 mouth(YukkuriEntity actor,String action){return posed(actor,action);}
    private HatCareAnchor(){}
}
