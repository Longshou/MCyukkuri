package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.WoundShape;
import io.github.longshou.mcyukkuri.body.WoundKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;

/** Selected source parameters and textures. This contains no injury decisions or mutable body state. */
final class HairWoundAssets {
    record Profile(float radiusU,float radiusV,float cutU,float cutV,float depth,boolean open,
                   float[][] outline,float[][] hairOutline) {}
    static final class Palette {
        final Vector3f core;
        final float[][] cavityColours,coreColours;
        Palette(JsonObject data){
            core=vector(data.getAsJsonArray("core_color"));
            cavityColours=rows(data.getAsJsonArray("cavity_controls"));
            coreColours=rows(data.getAsJsonArray("core_levels"));
        }
    }
    static final class Model {
        final String body;
        final Palette palette;
        final boolean house;
        final Vector3f scale,offset,hairEdge,hairHighlight;
        final Set<String> hair,thermalHair;
        final Map<String,String> rigid,accessories;
        final Map<Integer,Set<String>> dryFaces=new HashMap<>();
        Model(JsonObject data,JsonObject faces,Palette palette){
            this.palette=palette;
            body=data.get("body_part").getAsString();house=data.get("house").getAsBoolean();
            var fit=data.getAsJsonObject("fit");scale=vector(fit.getAsJsonArray("scale"));offset=vector(fit.getAsJsonArray("offset"));
            hairEdge=vector(data.getAsJsonArray("hair_edge"));hairHighlight=vector(data.getAsJsonArray("hair_highlight"));
            hair=strings(data.getAsJsonArray("hair_parts"));thermalHair=strings(data.getAsJsonArray("thermal_extra_hair_parts"));
            rigid=map(data.getAsJsonObject("rigid_groups"));accessories=map(data.getAsJsonObject("thermal_accessories"));
            if(faces!=null)for(var entry:faces.entrySet())dryFaces.put(Integer.parseInt(entry.getKey()),strings(entry.getValue().getAsJsonArray()));
        }
        boolean surface(String part){return part.equals(body)||part.startsWith("face_")||hair.contains(part);}
        boolean heated(String part){return surface(part)||thermalHair.contains(part);}
        float span(Vector3f axis){return new Vector3f(axis).mul(scale).length();}
        Vector3f canonical(Vector3f point){return new Vector3f(point).sub(offset).div(scale);}
    }
    private static final Map<String,Model> MODELS=new HashMap<>();
    private static final Map<String,Palette> PALETTES=new HashMap<>();
    private static final Map<String,Profile[]> PROFILES=new HashMap<>();
    static float[] widthLoss;
    static float minimumInset,cheekHeight,cheekFalloff,soleLow,soleHigh,textureScale;
    static float[][] cavityControls;
    static float coreDensity;

    static float wallTone(int ring,int segment,float y,float scale){
        float top=Math.clamp((y+1)*.5f,0,1);
        float occlusion=1-.24f*top*Math.min(1,scale*1.15f);
        float grain=ring<2?.98f+.02f*((segment+ring)%3):.92f+.08f*Math.floorMod(segment*7+ring*3,3);
        return occlusion*grain;
    }

    static void reload(ResourceManager manager){
        try(var reader=manager.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/hair_wounds.json")).openAsReader()){
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            if(data.get("format").getAsInt()!=1)throw new IllegalArgumentException("Unsupported selected hair wounds");
            MODELS.clear();PROFILES.clear();PALETTES.clear();var recipe=data.getAsJsonObject("recipe");
            for(var entry:data.getAsJsonObject("filling_palettes").entrySet())
                PALETTES.put(entry.getKey(),new Palette(entry.getValue().getAsJsonObject()));
            for(var entry:data.getAsJsonObject("models").entrySet())MODELS.put(entry.getKey(),new Model(entry.getValue().getAsJsonObject(),
                    data.getAsJsonObject("dry_faces").getAsJsonObject(entry.getKey()),
                    PALETTES.get(entry.getValue().getAsJsonObject().get("filling_palette").getAsString())));
            for(var entry:data.getAsJsonObject("profiles").entrySet()){
                var profiles=new Profile[4];var grades=recipe.getAsJsonObject("grades").getAsJsonArray(entry.getKey());
                for(int grade=1;grade<=3;grade++){
                    var profile=entry.getValue().getAsJsonObject().getAsJsonObject(Integer.toString(grade));
                    var radius=profile.getAsJsonArray("radius");var cut=profile.getAsJsonArray("cutRadius");
                    profiles[grade]=new Profile(radius.get(0).getAsFloat(),radius.get(1).getAsFloat(),cut.get(0).getAsFloat(),cut.get(1).getAsFloat(),
                            grades.get(grade-1).getAsJsonObject().get("depth").getAsFloat(),profile.get("open").getAsBoolean(),
                            rows(profile.getAsJsonArray("outline")),rows(profile.getAsJsonArray("hairOutline")));
                }PROFILES.put(entry.getKey(),profiles);
            }
            var thermal=recipe.getAsJsonObject("thermal");var dry=thermal.getAsJsonObject("dry_shape");
            widthLoss=floats(dry.getAsJsonArray("width_loss"));minimumInset=dry.get("minimum_inset").getAsFloat();
            cheekHeight=dry.get("cheek_height").getAsFloat();cheekFalloff=dry.get("cheek_falloff").getAsFloat();
            var sole=floats(thermal.getAsJsonArray("sole_y"));soleLow=sole[0];soleHigh=sole[1];textureScale=thermal.get("texture_world_scale").getAsFloat();
            cavityControls=rows(data.getAsJsonArray("cavity_controls"));
            var material=recipe.getAsJsonObject("filling_material");
            coreDensity=material.get("core_density_tint").getAsFloat();
        }catch(IOException error){throw new UncheckedIOException("Cannot load selected hair wound art",error);}
        HairWoundRenderer.clear();
    }
    static Model model(String id){return MODELS.get(id);}
    private static Palette palette(String id){var model=MODELS.get(id);return model==null?PALETTES.get("generic"):model.palette;}
    static float[] cavityColoursFor(String id,int ring){float[] row=palette(id).cavityColours[ring];return new float[]{row[3],row[4],row[5]};}
    static float[] coreColoursFor(String id,int ring){float[] row=palette(id).coreColours[ring];return new float[]{row[3],row[4],row[5]};}
    static Vector3f coreColourFor(String id){return palette(id).core;}
    static Profile profile(BodyWound wound){var profiles=PROFILES.get(wound.kind()==WoundKind.SCAR?"tear":wound.shape().id());
        return profiles==null?null:profiles[wound.severity()];}
    static int wholeBurn(Iterable<BodyWound> wounds,boolean house){int result=0;for(var wound:wounds)
        if(wound.kind()==WoundKind.SCORCH&&wound.shape()==WoundShape.WHOLE_BURN)result=Math.max(result,house?3:wound.severity());return result;}
    static ResourceLocation texture(String id,String expression,int grade){
        var model=MODELS.get(id);
        if(grade>0&&model!=null&&model.dryFaces.getOrDefault(grade,Set.of()).contains(expression))
            return MCYukkuri.id("textures/wounds/hair/"+id+"/dry-"+expression+"-"+grade+".png");
        return MCYukkuri.id("textures/entity/"+id+"/"+expression+".png");
    }
    static ResourceLocation material(String name){return MCYukkuri.id("textures/wounds/hair/"+name+".png");}
    private static Map<String,String> map(JsonObject data){var result=new HashMap<String,String>();if(data!=null)
        for(var entry:data.entrySet())result.put(entry.getKey(),entry.getValue().getAsString());return Map.copyOf(result);}
    private static Set<String> strings(JsonArray data){var result=new HashSet<String>();if(data!=null)for(var value:data)result.add(value.getAsString());return Set.copyOf(result);}
    private static Vector3f vector(JsonArray data){return new Vector3f(data.get(0).getAsFloat(),data.get(1).getAsFloat(),data.get(2).getAsFloat());}
    private static float[] floats(JsonArray data){float[] result=new float[data.size()];for(int i=0;i<result.length;i++)result[i]=data.get(i).getAsFloat();return result;}
    private static float[][] rows(JsonArray data){float[][] result=new float[data.size()][];for(int i=0;i<result.length;i++)result[i]=floats(data.get(i).getAsJsonArray());return result;}
    private HairWoundAssets(){}
}
