package io.github.longshou.mcyukkuri.body;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector3f;

/** Surface anchors keep injuries through asset updates, storage and adult morphs. */
public final class WoundMigration {
    static void record(CompoundTag body,WoundSurface mesh){
        body.putString("SurfaceHash",mesh.runtimeSourceHash);body.put("SurfaceBounds",list(mesh.bounds()));
        for(var raw:body.getList("Wounds",Tag.TAG_COMPOUND)){
            var tag=(CompoundTag)raw;int triangle=tag.getInt("Triangle");if(triangle<0||triangle+2>=mesh.skinCount)continue;
            float u=tag.getFloat("U"),v=tag.getFloat("V");var p=mesh.anchor(triangle,u,v,WoundSurface.AnchorMath.FMA);
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
        return mesh.valid(wound)?wound:null;
    }
    private WoundMigration(){}
}
