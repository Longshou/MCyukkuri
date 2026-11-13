package io.github.longshou.mcyukkuri.family;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
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
    private static final JsonObject GROWTH=DATA.getAsJsonObject("stemGrowth");
    private static final JsonArray EXTEND=GROWTH.getAsJsonArray("extend"), EMERGE=GROWTH.getAsJsonArray("fruit_emerge"),
            RIPEN=GROWTH.getAsJsonArray("fruit_ripen");
    private static final float BUD=GROWTH.get("bud_scale").getAsFloat(), PEDICEL_GROW=GROWTH.get("pedicel_grow").getAsFloat(),
            STAGGER=GROWTH.get("fruit_stagger").getAsFloat(), TIP_TAPER=GROWTH.get("tip_taper").getAsFloat()/16;

    private static float sample(JsonArray knots,float progress) {
        var start=knots.get(0).getAsJsonArray();
        for(int i=1;i<knots.size();i++){
            var end=knots.get(i).getAsJsonArray();
            float from=start.get(0).getAsFloat(),to=end.get(0).getAsFloat();
            if(progress<=to)return Mth.lerp(Math.clamp((progress-from)/(to-from),0,1),start.get(1).getAsFloat(),end.get(1).getAsFloat());
            start=end;
        }
        return start.get(1).getAsFloat();
    }
    /** The stem grows by extension (reference: art/props/family-life/stem_growth.py): the grown arc-length fraction of
     *  the full stalk, revealed from the forehead root toward its tip. */
    public static float extension(float progress) { return progress>0?sample(EXTEND,progress):0; }
    /** Blocks (at maternal scale 1) behind the growing end over which the stalk narrows to its own tip radius. */
    public static double tipTaper() { return TIP_TAPER; }
    /** A pedicel starts when the growing tip reaches its socket's arc fraction and reaches full length over a fixed span of progress. */
    public static float pedicelGrowth(float progress,double fraction) {
        if(progress<=0)return 0;
        var start=EXTEND.get(0).getAsJsonArray();
        float reached=EXTEND.get(EXTEND.size()-1).getAsJsonArray().get(0).getAsFloat();
        for(int i=1;i<EXTEND.size();i++){
            var end=EXTEND.get(i).getAsJsonArray();
            float from=start.get(1).getAsFloat(),to=end.get(1).getAsFloat();
            if(fraction<=to){reached=Mth.lerp((float)Math.clamp((fraction-from)/(to-from),0,1),start.get(0).getAsFloat(),end.get(0).getAsFloat());break;}
            start=end;
        }
        return Math.clamp((progress-reached)/PEDICEL_GROW,0,1);
    }
    /** Fruit set after the whole stalk and their own pedicels, from the root (the highest index) toward the tip. */
    public static float fruitScale(float progress,int count,int index,float birthScale) {
        return sample(EMERGE,progress-STAGGER*(count-1-index))*Mth.lerp(sample(RIPEN,progress),Math.min(BUD,birthScale),birthScale);
    }
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
        double extent=Math.max(1,scale),length=length(count,variance)*extent,rise=(y-origin)*units*length;
        double depth=variant.has("depth_clearance")?variant.get("depth_clearance").getAsDouble():STEM.get("depth_clearance").getAsDouble();
        double routeHeight=variant.has("clearance_rise")?variant.get("clearance_rise").getAsDouble()*scale/16:.3*units;
        double clearance=depth*extent/16*Math.clamp(rise/routeHeight,0,1);
        return root(mother).scale(scale).add(0,rise,z*units*length-clearance);
    }
    /** The authored centerline of the full-length stalk; a growing stem shows only its first part. */
    public static List<Vec3> arc(int count,float scale,float variance,YukkuriVariant mother) {
        var points=STEM.getAsJsonArray("centerline");List<Vec3> result=new ArrayList<>();
        for(var entry:points) { var a=entry.getAsJsonArray();result.add(point(a.get(0).getAsDouble(),a.get(1).getAsDouble(),count,scale,variance,mother)); }
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
    /** The full pedicel's drop below its hang point before a fruit's own hang clearance, in blocks. */
    public static double pendant(float motherScale) {
        return STEM.get("pendant_length_reference").getAsDouble()*STEM.get("reference_units_to_model").getAsDouble()/16*motherScale;
    }
    /** Where the pedicel meets a fruit of this size, relative to its body origin in blocks: the registered hanging anchor, else the crown top. */
    public static Vec3 hangingAnchor(YukkuriVariant child,float fruitScale) {
        var shape=DATA.getAsJsonObject("variants").getAsJsonObject(child.assetId());
        var anchor=shape.getAsJsonArray("hanging_anchor");double size=fruitScale/16;
        return anchor==null?new Vec3(0,shape.get("crown_height").getAsDouble()*size,0)
                :new Vec3(anchor.get(0).getAsDouble()*size,anchor.get(1).getAsDouble()*size,anchor.get(2).getAsDouble()*size);
    }
    public static double hangClearance(YukkuriVariant child,float fruitScale) {
        var config=DATA.getAsJsonObject("variants").getAsJsonObject(child.assetId());
        return config.get(config.has("hang_clearance_height")?"hang_clearance_height":"crown_height").getAsDouble()/16*fruitScale;
    }
    /** At rest the curved pedicel ends hanging plumb: the fruit's anchor lies directly below socket + hangOffset. Birth drops from here. */
    public static Vec3 childBase(int count,int index,float progress,float motherScale,float variance,YukkuriVariant mother,YukkuriVariant child) {
        return childBase(count,index,progress,motherScale,variance,mother,child,GrowthSize.STEM);
    }
    public static Vec3 childBase(int count,int index,float progress,float motherScale,float variance,YukkuriVariant mother,YukkuriVariant child,float birthScale) {
        float size=fruitScale(progress,count,index,birthScale);
        var anchor=hangingAnchor(child,size);
        return socket(count,index,motherScale,variance,mother).add(hangOffset(count,index,motherScale))
                .add(-anchor.x,-pendant(motherScale)-hangClearance(child,size),-anchor.z);
    }
    /** Rest drop of the full pedicel's tip below its hang point, down to the anchor of a fruit of this size. */
    public static double pedicel(float motherScale,YukkuriVariant child,float fruitScale) {
        return pendant(motherScale)+hangClearance(child,fruitScale)-hangingAnchor(child,fruitScale).y;
    }
    public static Vec3 toWorld(YukkuriEntity mother,Vec3 local) {
        return toWorld(mother.position(),mother.yBodyRot,mother.getScale(),local);
    }
    /** A point of the mother's local frame for her standing at {@code at} turned to {@code yaw} (a stand she is still to take). */
    public static Vec3 toWorld(Vec3 at,float yaw,float scale,Vec3 local) {
        return at.add(local.yRot((float)Math.toRadians(180-yaw)).scale(scale));
    }
    private FamilyGeometry() {}
}
