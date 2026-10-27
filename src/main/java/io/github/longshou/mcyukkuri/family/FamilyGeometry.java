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
import java.io.IOException;
import java.io.UncheckedIOException;

/** Shared deterministic geometry. Source data comes from the packaged selected authoring recipe. */
public final class FamilyGeometry {
    public static final JsonObject DATA=read();
    private static final JsonObject STEM=DATA.getAsJsonObject("stem");
    private static JsonObject read() {
        try(var stream=FamilyGeometry.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/family.json")) {
            if(stream==null)throw new IllegalStateException("Missing family authoring export");
            return JsonParser.parseReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).getAsJsonObject();
        }catch(IOException error){throw new UncheckedIOException(error);}
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
    /** Horizontal heading of a pedicel leaving its socket: on along the stalk for a single fruit; with several,
     *  alternately to each side (nearest fruit to the right) so neighbours do not bunch. */
    public static Vec3 branchHeading(int count,int index) {
        if(count<2)return new Vec3(0,0,-1);
        double side=DATA.get("pedicelSide").getAsDouble();
        return new Vec3(((count-1-index)%2==0?1:-1)*side,0,-Math.sqrt(Math.max(0,1-side*side)));
    }
    /** Horizontal reach of the curved pedicel: at rest its tip hangs plumb below socket + this offset. */
    public static Vec3 hangOffset(int count,int index,float scale) {
        return branchHeading(count,index).scale(DATA.get("pedicelReach").getAsDouble()/16*Math.max(1,scale));
    }
    /** Angle below horizontal at which a pedicel leaves the stalk. */
    public static double branchDrop() { return Math.toRadians(DATA.get("pedicelDropDegrees").getAsDouble()); }
    public static float fruitScale(float progress) { return GrowthSize.fruit(progress); }
    public static double pendant(float motherScale) { return STEM.get("pendant_length_reference").getAsDouble()*STEM.get("reference_units_to_model").getAsDouble()/16*motherScale; }
    /** Where the pedicel meets the fruit, relative to its body origin in blocks: the registered hanging anchor, else the crown top. */
    public static Vec3 hangingAnchor(float progress,YukkuriVariant child) {return hangingAnchor(progress,child,GrowthSize.STEM);}
    public static Vec3 hangingAnchor(float progress,YukkuriVariant child,float birthScale) {
        var shape=DATA.getAsJsonObject("variants").getAsJsonObject(child.assetId());
        var anchor=shape.getAsJsonArray("hanging_anchor");double size=GrowthSize.fruit(progress,birthScale)/16;
        return anchor==null?new Vec3(0,shape.get("crown_height").getAsDouble()*size,0)
                :new Vec3(anchor.get(0).getAsDouble()*size,anchor.get(1).getAsDouble()*size,anchor.get(2).getAsDouble()*size);
    }
    private static double hangClearance(float progress,YukkuriVariant child,float birthScale) {
        var config=DATA.getAsJsonObject("variants").getAsJsonObject(child.assetId());
        return config.get(config.has("hang_clearance_height")?"hang_clearance_height":"crown_height").getAsDouble()/16*GrowthSize.fruit(progress,birthScale);
    }
    /** At rest the curved pedicel ends hanging plumb: the fruit's anchor lies directly below socket + hangOffset. Birth drops from here. */
    public static Vec3 childBase(int count,int index,float progress,float motherScale,float variance,YukkuriVariant mother,YukkuriVariant child) {
        return childBase(count,index,progress,motherScale,variance,mother,child,GrowthSize.STEM);
    }
    public static Vec3 childBase(int count,int index,float progress,float motherScale,float variance,YukkuriVariant mother,YukkuriVariant child,float birthScale) {
        var anchor=hangingAnchor(progress,child,birthScale);
        return socket(count,index,motherScale,variance,mother).add(hangOffset(count,index,motherScale))
                .add(-anchor.x,-pendant(motherScale)-hangClearance(progress,child,birthScale),-anchor.z);
    }
    /** Rest drop of the pedicel tip below its hang point, down to the fruit's anchor. */
    public static double pedicel(float progress,float motherScale,YukkuriVariant child) {
        return pedicel(progress,motherScale,child,GrowthSize.STEM);
    }
    public static double pedicel(float progress,float motherScale,YukkuriVariant child,float birthScale) {
        return pendant(motherScale)+hangClearance(progress,child,birthScale)-hangingAnchor(progress,child,birthScale).y;
    }
    public static Vec3 toWorld(YukkuriEntity mother,Vec3 local) {
        return mother.position().add(local.yRot((float)Math.toRadians(180-mother.yBodyRot)).scale(mother.getScale()));
    }
    private FamilyGeometry() {}
}
