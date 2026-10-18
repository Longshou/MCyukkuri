package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.joml.Vector3f;

/** Authored placement corrections shared by rendering and server wound visibility. */
public final class ModelPlacementFit {
    private ModelPlacementFit() {}
    private static float[] floats(JsonArray values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i).getAsFloat();
            if (!Float.isFinite(result[i])) throw new IllegalArgumentException("Non-finite model value");
        }
        return result;
    }
    public static boolean apply(String id,JsonObject data,JsonObject fit,ModelFingerprint hash) {
        if (!hash.matches(fit.get("base_sha256").getAsString())) return false;
        JsonArray vertices=data.getAsJsonArray("vertices"),weights=data.getAsJsonArray("weights"),parts=data.getAsJsonArray("parts"),bones=data.getAsJsonArray("bones");
        for (var raw:fit.getAsJsonArray("parts")) {
            JsonObject entry=raw.getAsJsonObject();int index=entry.get("part_index").getAsInt();
            if (index<0||index>=parts.size()) throw new IllegalArgumentException("Invalid placement fit part");
            JsonObject part=parts.get(index).getAsJsonObject();
            if (!part.get("name").getAsString().equals(entry.get("name").getAsString())) throw new IllegalArgumentException("Placement fit part mismatch");
            float[] source=floats(entry.getAsJsonArray("vertices"));JsonArray binding=entry.getAsJsonArray("weights"),indices=entry.getAsJsonArray("indices");
            int count=indices.size();boolean inplace=entry.get("inplace").getAsBoolean();
            if (count%3!=0||source.length%5!=0||binding.size()*5!=source.length*3||(inplace&&count!=part.get("count").getAsInt()))
                throw new IllegalArgumentException("Invalid placement fit mesh");
            int start=inplace?part.get("start").getAsInt():vertices.size()/8;
            int[] corner=new int[3];var a=new Vector3f();var b=new Vector3f();
            for (int t=0;t<count;t+=3) {
                for (int j=0;j<3;j++) { corner[j]=indices.get(t+j).getAsInt(); if (corner[j]<0||corner[j]*5+5>source.length) throw new IllegalArgumentException("Invalid placement fit index"); }
                a.set(source[corner[1]*5]-source[corner[0]*5],source[corner[1]*5+1]-source[corner[0]*5+1],source[corner[1]*5+2]-source[corner[0]*5+2]);
                b.set(source[corner[2]*5]-source[corner[0]*5],source[corner[2]*5+1]-source[corner[0]*5+1],source[corner[2]*5+2]-source[corner[0]*5+2]);
                a.cross(b);if (a.lengthSquared()>1e-24f) a.normalize(); else a.set(0,0,-1);
                for (int j=0;j<3;j++) {
                    int k=corner[j],target=start+t+j;float[] row={source[k*5],source[k*5+1],source[k*5+2],source[k*5+3],source[k*5+4],a.x,a.y,a.z};
                    JsonArray bind=new JsonArray();bind.add(binding.get(k*3));bind.add(binding.get(k*3+1));bind.add(binding.get(k*3+2));
                    if (inplace) { for (int c=0;c<8;c++) vertices.set(target*8+c,new JsonPrimitive(row[c])); weights.set(target,bind); }
                    else { for (float value:row) vertices.add(value); weights.add(bind); }
                }
            }
            if (!inplace) { part.addProperty("start",start); part.addProperty("count",count); }
        }
        for (var raw:fit.getAsJsonArray("bone_pivots")) {
            JsonObject moved=raw.getAsJsonObject();int index=moved.get("index").getAsInt();
            if (index<0||index>=bones.size()||!bones.get(index).getAsJsonObject().get("name").getAsString().equals(moved.get("name").getAsString()))
                throw new IllegalArgumentException("Placement fit bone mismatch");
            bones.get(index).getAsJsonObject().add("pivot",moved.getAsJsonArray("pivot").deepCopy());
        }
        return true;
    }

}
