package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector3f;

/** Versioned surface anchors keep injuries through asset updates, storage and adult morphs. */
public final class WoundMigration {
    private record Legacy(float[] points,float[] bounds) {}
    private static final Map<String,Legacy> LEGACY=new HashMap<>();
    static void record(CompoundTag body,WoundSurface mesh){
        body.putString("SurfaceHash",mesh.runtimeSourceHash);body.put("SurfaceBounds",list(mesh.bounds()));
        for(var raw:body.getList("Wounds",Tag.TAG_COMPOUND)){
            var tag=(CompoundTag)raw;int triangle=tag.getInt("Triangle");if(triangle<0||triangle+2>=mesh.skinCount)continue;
            float u=tag.getFloat("U"),v=tag.getFloat("V");var p=mesh.rest(triangle).mul(1-u-v).fma(u,mesh.rest(triangle+1)).fma(v,mesh.rest(triangle+2));
            tag.put("Anchor",list(new float[]{p.x,p.y,p.z}));var n=mesh.normal(triangle);tag.put("Normal",list(new float[]{n.x,n.y,n.z}));
        }
    }
    private static ListTag list(float[] values){var result=new ListTag();for(float value:values)result.add(FloatTag.valueOf(value));return result;}
    private static float[] floats(CompoundTag tag,String key,int count){var list=tag.getList(key,Tag.TAG_FLOAT);if(list.size()!=count)return null;float[] values=new float[count];for(int i=0;i<count;i++){values[i]=list.getFloat(i);if(!Float.isFinite(values[i]))return null;}return values;}
    public static BodyWound restore(String id,WoundSurface mesh,CompoundTag body,BodyWound wound){
        if(mesh.runtimeSourceHash.equals(body.getString("SurfaceHash"))&&mesh.valid(wound))return wound;
        for(var raw:body.getList("Wounds",Tag.TAG_COMPOUND)){
            var tag=(CompoundTag)raw;if(!tag.hasUUID("Id")||!tag.getUUID("Id").equals(wound.id()))continue;
            float[] anchor=floats(tag,"Anchor",3),normal=floats(tag,"Normal",3),bounds=floats(body,"SurfaceBounds",6);
            if(anchor!=null&&normal!=null)return mesh.remap(wound,new Vector3f(anchor),new Vector3f(normal),bounds);
        }
        if(body.getInt("Version")>=3)return mesh.valid(wound)?wound:null;
        var old=legacy(id);if(old==null)return mesh.valid(wound)?wound:null;
        int first=wound.triangle()*3;if(first<0||first+8>=old.points.length)return null;
        var a=new Vector3f(old.points[first],old.points[first+1],old.points[first+2]);var b=new Vector3f(old.points[first+3],old.points[first+4],old.points[first+5]);var c=new Vector3f(old.points[first+6],old.points[first+7],old.points[first+8]);
        var normal=new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));var point=new Vector3f(a).mul(1-wound.u()-wound.v()).fma(wound.u(),b).fma(wound.v(),c);
        return mesh.remap(wound,point,normal,old.bounds);
    }
    private static Legacy legacy(String id){
        if(LEGACY.containsKey(id))return LEGACY.get(id);
        var stream=WoundMigration.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_wounds/legacy/"+id+".json.gz");
        if(stream==null){LEGACY.put(id,null);return null;}
        try(var reader=new InputStreamReader(new java.util.zip.GZIPInputStream(stream),StandardCharsets.UTF_8)){
            var data=JsonParser.parseReader(reader).getAsJsonObject();var p=data.getAsJsonArray("points");var b=data.getAsJsonArray("bounds");float[] points=new float[p.size()],bounds=new float[6];
            for(int i=0;i<points.length;i++)points[i]=p.get(i).getAsFloat();for(int i=0;i<6;i++)bounds[i]=b.get(i).getAsFloat();var legacy=new Legacy(points,bounds);LEGACY.put(id,legacy);return legacy;
        }catch(java.io.IOException ex){throw new java.io.UncheckedIOException(ex);}
    }
    private WoundMigration(){}
}
