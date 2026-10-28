package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.joml.Vector3f;

/** Selected authoring parameters used by both posed impact geometry and the rendered injury. */
public final class WoundProfiles {
    public record Fit(Vector3f scale, Vector3f offset, Vector3f center) {}
    public record Grade(float[] radius, float depth, float[][] outline, float[][] hairOutline) {}
    private static JsonObject data;
    private static final Map<String,Fit> FITS=new HashMap<>();
    private static final Map<String,Grade> GRADES=new HashMap<>();
    private record DryShape(float[] widthLoss,float minimumInset,float cheekHeight,float cheekFalloff) {}
    private static DryShape dryShape;
    private WoundProfiles() {}
    public static synchronized void clear() { data=null;FITS.clear();GRADES.clear();dryShape=null; }
    private static JsonObject data() {
        if(data!=null)return data;
        var input=WoundProfiles.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/hair_wounds.json");
        if(input==null)throw new IllegalStateException("Missing authored wound shapes");
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){
            return data=JsonParser.parseReader(reader).getAsJsonObject();
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }
    public static synchronized Fit fit(String id) {
        return FITS.computeIfAbsent(id,key->{
            var model=data().getAsJsonObject("models").getAsJsonObject(key);
            if(model==null)throw new IllegalStateException("Missing wound fit for "+key);
            var fit=model.getAsJsonObject("fit");
            var scale=new Vector3f(floats(fit.getAsJsonArray("scale")));
            var offset=new Vector3f(floats(fit.getAsJsonArray("offset")));
            var center=new Vector3f(floats(data().getAsJsonObject("recipe").getAsJsonArray("core_center"))).mul(scale).add(offset);
            return new Fit(scale,offset,center);
        });
    }
    public static synchronized Grade grade(WoundShape shape,int severity) {
        return GRADES.computeIfAbsent(shape.id()+severity,key->{
            var grade=data().getAsJsonObject("recipe").getAsJsonObject("grades").getAsJsonArray(shape.id()).get(severity-1).getAsJsonObject();
            var profile=data().getAsJsonObject("profiles").getAsJsonObject(shape.id()).getAsJsonObject(Integer.toString(severity));
            return new Grade(floats(grade.getAsJsonArray("radius")),grade.get("depth").getAsFloat(),
                    polygon(profile.getAsJsonArray("outline")),polygon(profile.getAsJsonArray("hairOutline")));
        });
    }
    public static synchronized float[] coreRadius(WoundShape shape) {
        return floats(data().getAsJsonObject("recipe").getAsJsonObject("core_openings").getAsJsonArray(shape.id()));
    }
    public static synchronized float[][] coreLevels() { return polygon(data().getAsJsonArray("core_levels")); }
    public static synchronized float coreReferenceDepth() { return data().get("core_reference_depth").getAsFloat(); }
    public static synchronized float verticalScale(String id,Vector3f normal) {
        var scale=data().getAsJsonObject("models").getAsJsonObject(id).getAsJsonObject("mechanical_v_scale");
        return scale==null?1:scale.get(normal.z<-.6f?"skin":"hair").getAsFloat();
    }
    private static synchronized DryShape dryShape() {
        if(dryShape!=null)return dryShape;
        var raw=data().getAsJsonObject("recipe").getAsJsonObject("thermal").getAsJsonObject("dry_shape");
        return dryShape=new DryShape(floats(raw.getAsJsonArray("width_loss")),raw.get("minimum_inset").getAsFloat(),
                raw.get("cheek_height").getAsFloat(),raw.get("cheek_falloff").getAsFloat());
    }
    /** Continuous whole-body drying; height/depth and sole-only burns do not shrink. */
    public static Vector3f thermalPoint(String id,Vector3f point,int severity) {
        if(severity<=0)return point;
        var fit=fit(id);var dry=dryShape();
        float y=(point.y-fit.offset().y)/fit.scale().y;
        float delta=(y-dry.cheekHeight())/dry.cheekFalloff();
        float factor=1-dry.widthLoss()[Math.clamp(severity,1,3)-1]*(dry.minimumInset()+(1-dry.minimumInset())*(float)Math.exp(-delta*delta));
        point.x=fit.offset().x+(point.x-fit.offset().x)*factor;
        return point;
    }
    private static float[] floats(JsonArray values) {
        float[] result=new float[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i).getAsFloat();return result;
    }
    private static float[][] polygon(JsonArray values) {
        float[][] result=new float[values.size()][];for(int i=0;i<result.length;i++)result[i]=floats(values.get(i).getAsJsonArray());return result;
    }
}
