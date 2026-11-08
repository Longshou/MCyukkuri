package io.github.longshou.mcyukkuri.pose.check;

import com.google.gson.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Geometry evidence assembled from the already evaluated core skin, in common world mu. */
public final class PoseGeometry {
    private PoseGeometry() {}
    public static String group(String name) {
        if(name.startsWith("braid_lock"))return "braid";
        if(name.startsWith("soft_head_slice_")||List.of("rounded_body_surface","house_body_surface","predator_body_surface","deficient_body_surface").contains(name))return "body";
        if(name.contains("hair")||name.startsWith("bang")||name.startsWith("back_lock"))return "hair";
        if(name.startsWith("hat_")||name.startsWith("brim_")||name.startsWith("bow_"))return "hat";
        if(name.contains("tail"))return "tail";
        if(name.startsWith("face_mouth"))return "mouth";
        return "other";
    }
    public static JsonArray point(Vector3f p) { var a=new JsonArray();a.add((double)p.x);a.add((double)p.y);a.add((double)p.z);return a; }
    public static Vector3f vector(JsonElement p){var a=p.getAsJsonArray();return new Vector3f(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat());}
    public static void append(JsonObject meshes,String name,JsonArray triangles,boolean closed) {
        if(!meshes.has(name)){var m=new JsonObject();m.addProperty("closed",closed);m.add("triangles",new JsonArray());meshes.add(name,m);}
        meshes.getAsJsonObject(name).getAsJsonArray("triangles").addAll(triangles.deepCopy());
    }
    public static JsonArray points(JsonObject mesh) {
        var result=new JsonArray();for(var triangle:mesh.getAsJsonArray("triangles"))for(var p:triangle.getAsJsonArray())result.add(p);return result;
    }
    public static void transform(JsonObject meshes,Matrix4f transform) {
        for(var e:meshes.entrySet())for(var triangle:e.getValue().getAsJsonObject().getAsJsonArray("triangles")) {
            var t=triangle.getAsJsonArray();for(int i=0;i<3;i++)t.set(i,point(transform.transformPosition(vector(t.get(i)))));
        }
    }
    public static JsonArray region(JsonObject mesh,String why) {
        var ps=points(mesh);var low=new Vector3f(Float.POSITIVE_INFINITY);var high=new Vector3f(Float.NEGATIVE_INFINITY);
        for(var p:ps){var v=vector(p);low.min(v);high.max(v);}
        var center=low.add(high).mul(.5f);float radius=0;
        for(var p:ps)radius=Math.max(radius,center.distance(vector(p)));
        var region=new JsonObject();region.add("center",point(center));region.addProperty("radius",radius);region.addProperty("why",why);
        var result=new JsonArray();result.add(region);return result;
    }
    private static void pair(JsonArray pairs,String a,String b,JsonArray allowed) {
        var p=new JsonObject();p.addProperty("a",a);p.addProperty("b",b);p.add("allowed",allowed);pairs.add(p);
    }
    /** Nearby means two blocks between body origins, as specified for partner/hat observation. */
    public static void context(JsonObject f,Map<String,JsonObject> actors) {
        var meshes=f.getAsJsonObject("meshes");var pairs=new JsonArray();f.add("pairs",pairs);
        if(meshes.has("braid")&&meshes.has("body"))pair(pairs,"braid","body",meshes.has("root_ring")?region(meshes.getAsJsonObject("root_ring"),"drawn braid root ring"):new JsonArray());
        var sup=f.getAsJsonObject("sup");String hostId=sup.get("host").isJsonNull()?null:sup.get("host").getAsString();
        if(meshes.has("body"))f.add("skin_points",points(meshes.getAsJsonObject("body")));
        for(var entry:actors.entrySet()) {
            if(entry.getKey().equals(f.get("u").getAsString()))continue;
            var other=entry.getValue();
            if(!other.has("meshes"))continue;
            var om=other.getAsJsonObject("meshes");
            boolean host=entry.getKey().equals(hostId);
            double distance=PoseChecks.vector(f.get("position")).distance(PoseChecks.vector(other.get("position")));
            if(!host&&distance>32)continue;
            String prefix=entry.getKey()+"/";
            for(String group:List.of("body","hair","hat","tail"))if(om.has(group))meshes.add(prefix+group,om.get(group));
            if(host) {
                sup.add("frame",other.get(sup.get("kind").getAsString().equals("carrier")?"head":"root"));
                var support=new JsonObject();support.addProperty("closed",false);var ts=new JsonArray();support.add("triangles",ts);
                for(String group:List.of("body","hat","tail","braid"))if(om.has(group))ts.addAll(om.getAsJsonObject(group).getAsJsonArray("triangles"));
                sup.add("mesh",support);
                if(meshes.has("body"))for(String group:List.of("body","hat"))if(om.has(group))
                    pair(pairs,"body",prefix+group,om.has("mouth")?region(om.getAsJsonObject("mouth"),"carrier mouth contact"):new JsonArray());
            }
            if(meshes.has("hat"))for(String group:List.of("body","hair"))if(om.has(group))pair(pairs,"hat",prefix+group,new JsonArray());
            if(om.has("hat")&&meshes.has("body")&&!host)pair(pairs,"body",prefix+"hat",new JsonArray());
        }
        if(meshes.has("item")&&meshes.has("body"))pair(pairs,"item","body",meshes.has("mouth")?region(meshes.getAsJsonObject("mouth"),"mouth grip contact"):new JsonArray());
        f.addProperty("pairs_complete",!f.has("geometry_error"));
    }
}
