package io.github.longshou.mcyukkuri.body;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Rest-surface triangle and barycentric coordinates survive movement and save/reload. */
public record BodyWound(UUID id, String kind, int triangle, float u, float v, int severity, float size,
                        int emitted, long lastRelease) {
    public BodyWound {
        if(!java.util.Set.of("scratch","tear","dent","scorch").contains(kind))kind="scratch";
        triangle=Math.max(0,triangle/3*3);
        u=Float.isFinite(u)?Math.clamp(u,0,1):0;v=Float.isFinite(v)?Math.clamp(v,0,1-u):0;
        severity=Math.clamp(severity,1,3);size=Float.isFinite(size)?Math.clamp(size,.3f,2f):1;
        emitted=Math.clamp(emitted,0,12);lastRelease=Math.max(0,lastRelease);
    }
    public BodyWound heal(){return new BodyWound(id,kind,triangle,u,v,severity-1,size,emitted,lastRelease);}
    public BodyWound released(long tick){return new BodyWound(id,kind,triangle,u,v,severity,size,emitted+1,tick);}
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);t.putString("Kind",kind);t.putInt("Triangle",triangle);
        t.putFloat("U",u);t.putFloat("V",v);t.putInt("Severity",severity);t.putFloat("Size",size);t.putInt("Emitted",emitted);t.putLong("LastRelease",lastRelease);return t;}
    public static java.util.List<BodyWound> read(CompoundTag t){var result=new java.util.ArrayList<BodyWound>();var ids=new java.util.HashSet<UUID>();
        for(var raw:t.getList("Wounds",Tag.TAG_COMPOUND)){var w=(CompoundTag)raw;if(w.getInt("Severity")<=0||!w.hasUUID("Id")||!ids.add(w.getUUID("Id")))continue;
            result.add(new BodyWound(w.getUUID("Id"),w.getString("Kind"),w.getInt("Triangle"),w.getFloat("U"),w.getFloat("V"),w.getInt("Severity"),w.getFloat("Size"),w.getInt("Emitted"),w.getLong("LastRelease")));if(result.size()==8)break;}
        return java.util.List.copyOf(result);}
    public static CompoundTag write(java.util.List<BodyWound> wounds){var t=new CompoundTag();var list=new ListTag();wounds.forEach(w->list.add(w.save()));t.put("Wounds",list);return t;}
}
