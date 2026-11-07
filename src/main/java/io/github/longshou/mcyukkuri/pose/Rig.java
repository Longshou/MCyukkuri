package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonArray;
import org.joml.Matrix4f;

/** Ordered model-space skeleton and reusable pose buffers; no game or render state. */
public final class Rig {
    private Rig() {}
    public static final class Bone {
        public final String name;
        public final int parent;
        public final float x,y,z;
        public Bone(String name,int parent,float x,float y,float z){this.name=name;this.parent=parent;this.x=x;this.y=y;this.z=z;}
        public String name(){return name;}
        public int parent(){return parent;}
        public float x(){return x;} public float y(){return y;} public float z(){return z;}
    }
    public static float[] floats(JsonArray values) {
        float[] result=new float[values.size()];
        for(int i=0;i<result.length;i++){
            result[i]=values.get(i).getAsFloat();
            if(!Float.isFinite(result[i]))throw new IllegalArgumentException("Non-finite model value");
        }
        return result;
    }
    public static Bone[] bones(JsonArray source){
        var result=new Bone[source.size()];
        for(int i=0;i<result.length;i++){
            var b=source.get(i).getAsJsonObject();var p=floats(b.getAsJsonArray("pivot"));int parent=b.get("parent").getAsInt();
            if(parent < -1 || parent >= i)throw new IllegalArgumentException("Unordered bone hierarchy");
            result[i]=new Bone(b.get("name").getAsString(),parent,p[0],p[1],p[2]);
        }
        return result;
    }
    public static float[][] values(int count){var result=new float[count][9];for(var v:result)v[6]=v[7]=v[8]=1;return result;}
    public static Matrix4f[] matrices(int count){var result=new Matrix4f[count];for(int i=0;i<count;i++)result[i]=new Matrix4f();return result;}
    public static boolean descends(Bone[] bones,int bone,int ancestor){for(int i=bone;i>=0;i=bones[i].parent)if(i==ancestor)return true;return false;}
}
