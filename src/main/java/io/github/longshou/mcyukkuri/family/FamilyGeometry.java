package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Shared deterministic geometry. Source data comes from the packaged selected authoring recipe. */
public final class FamilyGeometry {
    public static final JsonObject DATA=read();
    private static final JsonObject STEM=DATA.getAsJsonObject("stem");
    private static JsonObject read() {
        try(var stream=FamilyGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/family.json")) {
            if(stream==null)throw new IllegalStateException("Missing family authoring export");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(java.io.IOException error){throw new java.io.UncheckedIOException(error);}
    }
    public static double length(int count,float variance) { return DATA.getAsJsonArray("lengthScale").get(Math.clamp(count,1,5)-1).getAsDouble()*variance; }
    public static double radius(int count,double along,float variance) {
        var range=STEM.getAsJsonArray("radius");return Mth.lerp(along,range.get(0).getAsDouble(),range.get(1).getAsDouble())
                *DATA.getAsJsonArray("radiusScale").get(Math.clamp(count,1,5)-1).getAsDouble()*variance/16;
    }
    public static Vec3 root(YukkuriVariant mother) {
        var a=DATA.getAsJsonObject("variants").getAsJsonObject(mother.assetId()).getAsJsonArray("root");
        return new Vec3(a.get(0).getAsDouble()/16,a.get(1).getAsDouble()/16,a.get(2).getAsDouble()/16);
    }
    private static Vec3 point(double z,double y,int count,float scale,float variance,YukkuriVariant mother) {
        double units=STEM.get("reference_units_to_model").getAsDouble()/16;
        double origin=STEM.getAsJsonArray("reference_origin").get(1).getAsDouble();
        var variant=DATA.getAsJsonObject("variants").getAsJsonObject(mother.assetId());
        // Sockets never shrink below the standard parent's, so small adults cannot compress them into their fruit.
        double extent=Math.max(1,scale),rise=(y-origin)*units*length(count,variance)*extent;
        double depth=variant.has("depth_clearance")?variant.get("depth_clearance").getAsDouble():STEM.get("depth_clearance").getAsDouble();
        double routeHeight=variant.has("clearance_rise")?variant.get("clearance_rise").getAsDouble()*scale/16:.3*units;
        double clearance=depth*extent/16*Math.clamp(rise/routeHeight,0,1);
        return root(mother).scale(scale).add(0,rise,z*units*length(count,variance)*extent-clearance);
    }
    public static List<Vec3> arc(int count,float growth,float scale,float variance,YukkuriVariant mother) {
        var points=STEM.getAsJsonArray("centerline");List<Vec3> result=new ArrayList<>();Vec3 base=root(mother).scale(scale);
        for(var entry:points) { var a=entry.getAsJsonArray();result.add(base.lerp(point(a.get(0).getAsDouble(),a.get(1).getAsDouble(),count,scale,variance,mother),growth)); }
        return result;
    }
    public static Vec3 socket(int count,int index,float scale,float variance,YukkuriVariant mother) {
        var a=STEM.getAsJsonObject("socket_layouts").getAsJsonArray(String.valueOf(count)).get(index).getAsJsonArray();
        return point(a.get(0).getAsDouble(),a.get(1).getAsDouble(),count,scale,variance,mother);
    }
    public static float fruitScale(float progress) { return GrowthSize.fruit(progress); }
    public static double pendant(float motherScale) { return STEM.get("pendant_length_reference").getAsDouble()*STEM.get("reference_units_to_model").getAsDouble()/16*motherScale; }
    public static Vec3 childBase(int count,int index,float progress,float motherScale,float variance,YukkuriVariant mother,YukkuriVariant child) {
        var config=DATA.getAsJsonObject("variants").getAsJsonObject(child.assetId());
        double crown=config.get(config.has("hang_clearance_height")?"hang_clearance_height":"crown_height").getAsDouble()/16;
        double forward=DATA.get("fruitForwardClearance").getAsDouble()/16;
        return socket(count,index,motherScale,variance,mother).add(0,-pendant(motherScale)-crown*fruitScale(progress),-forward);
    }
    public static Vec3 toWorld(YukkuriEntity mother,Vec3 local) {
        return mother.position().add(local.yRot((float)Math.toRadians(180-mother.yBodyRot)).scale(mother.getScale()));
    }
    private FamilyGeometry() {}
}
