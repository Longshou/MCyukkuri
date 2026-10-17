package io.github.longshou.mcyukkuri.house;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.phys.Vec3;

/** The selected nest recipe is shared by egg items, shells and the real newborn spawn. */
public final class HouseEggGeometry {
    private static final JsonObject DATA=read();
    public static final float MODEL_SCALE=DATA.get("model_scale").getAsFloat();
    public static final double HATCH_FOOT_LIFT=DATA.get("hatch_foot_lift").getAsDouble()/16;
    public static Vec3 slot(int slot){
        var point=DATA.getAsJsonArray("slots").get(Math.clamp(slot,0,3)).getAsJsonArray();
        return new Vec3(point.get(0).getAsDouble(),point.get(1).getAsDouble(),point.get(2).getAsDouble());
    }
    public static Vec3 beddingCenter(){
        var point=DATA.getAsJsonArray("bedding_center");
        return new Vec3(point.get(0).getAsDouble(),point.get(1).getAsDouble(),point.get(2).getAsDouble());
    }
    public static float beddingScale(int axis){return DATA.getAsJsonArray("bedding_scale").get(axis).getAsFloat();}
    private static JsonObject read(){
        try(var stream=HouseEggGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/house_egg_geometry.json")){
            if(stream==null)throw new IllegalStateException("Missing house egg geometry export");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    private HouseEggGeometry(){}
}
