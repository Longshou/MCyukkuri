package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.RemainsCause;
import io.github.longshou.mcyukkuri.body.RemainsWater;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/**
 * Selected remains looks (yukkuri_presentation/remains_forms.json, exported from standards/yukkuri-library/drafts/remains).
 * Reads only the synced remains snapshot: the stored cause category and water stage pick the face atlas, squash, tilt,
 * skin overlay and ground decal. Bodies without authored atlases keep their sleep face and still take the pose.
 */
final class RemainsAssets {
    private record Entry(String face,float sx,float sy,float sz,float roll,String overlay,String decal,float decalRadius){}
    /** A resolved look; {@code overlay}/{@code decal} are null when the form has none. */
    record Look(String face,ResourceLocation texture,float sx,float sy,float sz,float roll,ResourceLocation overlay,
                ResourceLocation decal,float decalRadius){}
    private static final Map<String,Entry> FORMS=new HashMap<>(),WATER=new HashMap<>();
    private static final Map<String,Set<String>> ATLASES=new HashMap<>();
    /** The withered stem kept on remains (art/props/family-life/stem_growth.py): pitch lowered by droop (radians) x the
     *  stalk length scale x arc fraction down to steepest (radians) below level, radius x thickness, pedicels slack times longer and splayed (radians) from plumb,
     *  fruit shrunk per axis by shrivel. */
    record StemLook(float droop,float steepest,float thickness,float slack,float splay,Vec3 shrivel,int[] colour) {}
    private static StemLook stem;

    static void reload(ResourceManager manager){
        FORMS.clear();WATER.clear();ATLASES.clear();stem=null;
        try(var reader=manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/remains_forms.json")).openAsReader()){
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            if(data.get("format").getAsInt()!=1)throw new IllegalArgumentException("Unsupported remains forms");
            var geometry=data.getAsJsonObject("stem");
            var rgb=geometry.getAsJsonArray("colour");var shrivel=geometry.getAsJsonArray("fruit_shrivel");
            stem=new StemLook((float)Math.toRadians(geometry.get("droop_degrees").getAsFloat()),
                    (float)Math.toRadians(geometry.get("steepest_degrees").getAsFloat()),geometry.get("thickness").getAsFloat(),
                    geometry.get("pedicel_slack").getAsFloat(),(float)Math.toRadians(geometry.get("pedicel_splay_degrees").getAsFloat()),
                    new Vec3(shrivel.get(0).getAsDouble(),shrivel.get(1).getAsDouble(),shrivel.get(2).getAsDouble()),
                    new int[]{rgb.get(0).getAsInt(),rgb.get(1).getAsInt(),rgb.get(2).getAsInt()});
            for(var entry:data.getAsJsonObject("forms").entrySet())FORMS.put(entry.getKey(),entry(entry.getValue().getAsJsonObject()));
            for(var entry:data.getAsJsonObject("water").entrySet())WATER.put(entry.getKey(),entry(entry.getValue().getAsJsonObject()));
            for(var entry:data.getAsJsonObject("atlases").entrySet()){
                var names=new HashSet<String>();for(var name:entry.getValue().getAsJsonArray())names.add(name.getAsString());
                ATLASES.put(entry.getKey(),Set.copyOf(names));
            }
        }catch(IOException|RuntimeException error){
            // Without the selected looks the remains keep the former transitional pose; nothing else depends on them.
            FORMS.clear();WATER.clear();ATLASES.clear();stem=null;
            LoggerFactory.getLogger(RemainsAssets.class).warn("Remains forms unavailable",error);
        }
    }
    private static Entry entry(JsonObject data){
        var scale=data.getAsJsonArray("scale");var decal=data.getAsJsonObject("decal");
        return new Entry(data.has("face")?data.get("face").getAsString():"sleep",scale.get(0).getAsFloat(),scale.get(1).getAsFloat(),scale.get(2).getAsFloat(),
                data.has("roll")?data.get("roll").getAsFloat():0,data.has("overlay")?data.get("overlay").getAsString():null,
                decal==null?null:decal.get("name").getAsString(),decal==null?0:decal.get("radius").getAsFloat());
    }
    private static boolean atlas(String model,String name){return ATLASES.getOrDefault(model,Set.of()).contains(name);}
    static StemLook stem(){return stem;}
    static ResourceLocation stemFruit(String model){
        return MCYukkuri.id("textures/entity/"+model+(atlas(model,"stem_fruit")?"/remains_stem_fruit.png":"/sleep.png"));
    }
    private static ResourceLocation overlay(String name){return name==null?null:MCYukkuri.id("textures/entity/remains/overlay_"+name+".png");}
    private static ResourceLocation decal(String name){return name==null?null:MCYukkuri.id("textures/entity/remains/decal_"+name+".png");}

    static Look resolve(String model,CompoundTag data){
        int burn=HairWoundAssets.wholeBurn(BodyWound.read(data.getCompound("Body")),model.startsWith("house_"));
        String form=RemainsCause.form(RemainsCause.of(data));
        var look=FORMS.get(form);
        if(look==null)return new Look("sleep",HairWoundAssets.texture(model,"sleep",burn),1,.65f,1,0,null,null,0);
        // A whole-body burn from another cause keeps its burnt sleep face; the burned form has its own charred atlas.
        boolean own=atlas(model,form)&&(burn==0||form.equals(RemainsCause.BURNED));
        String face=own?look.face():"sleep";
        var texture=own?MCYukkuri.id("textures/entity/"+model+"/remains_"+form+".png"):HairWoundAssets.texture(model,"sleep",burn);
        float sx=look.sx(),sy=look.sy(),sz=look.sz(),roll=look.roll();
        String overlay=look.overlay(),decal=look.decal();float radius=look.decalRadius();
        int stage=RemainsWater.stage(data);
        var water=stage==RemainsWater.SOFTENED?WATER.get("softened"):stage==RemainsWater.DISSOLVING?WATER.get("dissolving"):null;
        if(water!=null){
            sx*=water.sx();sy*=water.sy();sz*=water.sz();
            if(water.overlay()!=null)overlay=water.overlay();
            if(water.decal()!=null){decal=water.decal();radius=water.decalRadius();}
            if(stage==RemainsWater.DISSOLVING){
                // The residue has no posture left; its face is washed out where an atlas exists.
                roll=0;
                if(atlas(model,"dissolving")){face=water.face();texture=MCYukkuri.id("textures/entity/"+model+"/remains_dissolving.png");}
            }
        }
        return new Look(face,texture,sx,sy,sz,roll,overlay(overlay),decal(decal),radius);
    }
    private RemainsAssets(){}
}
