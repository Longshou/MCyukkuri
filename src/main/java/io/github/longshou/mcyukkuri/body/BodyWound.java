package io.github.longshou.mcyukkuri.body;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Rest-surface triangle and barycentric coordinates survive movement and save/reload. */
public record BodyWound(UUID id, WoundKind kind, int triangle, float u, float v, int severity, float size,
                        int emitted, long lastRelease, int peakSeverity, WoundShape shape) {
    /** Shared surface site for injury rules, rendering and model migration. */
    public record Site(int triangle, float u, float v) {
        public boolean validNumbers() {
            return triangle >= 0 && triangle % 3 == 0 && Float.isFinite(u) && Float.isFinite(v)
                    && u >= 0 && v >= 0 && u + v <= 1;
        }
        public WoundSurface.Hit hit() { return new WoundSurface.Hit(triangle, u, v, 0); }
    }
    public BodyWound {
        Objects.requireNonNull(kind);
        shape=shape==null?WoundShape.defaultFor(kind):shape;
        triangle=Math.max(0,triangle/3*3);
        u=Float.isFinite(u)?Math.clamp(u,0,1):0;v=Float.isFinite(v)?Math.clamp(v,0,1-u):0;
        severity=Math.clamp(severity,1,3);size=Float.isFinite(size)?Math.clamp(size,.3f,2f):1;
        peakSeverity=Math.clamp(Math.max(severity,peakSeverity),1,3);
        emitted=Math.clamp(emitted,0,12);lastRelease=Math.max(0,lastRelease);
    }
    public BodyWound(UUID id,WoundKind kind,int triangle,float u,float v,int severity,float size,int emitted,long lastRelease) {
        this(id,kind,triangle,u,v,severity,size,emitted,lastRelease,severity,WoundShape.defaultFor(kind));
    }
    public BodyWound(UUID id,WoundKind kind,int triangle,float u,float v,int severity,float size,int emitted,long lastRelease,int peakSeverity) {
        this(id,kind,triangle,u,v,severity,size,emitted,lastRelease,peakSeverity,WoundShape.defaultFor(kind));
    }
    /** String boundary retained for NBT, commands and status tools. */
    public BodyWound(UUID id, String kind, int triangle, float u, float v, int severity, float size,
                     int emitted, long lastRelease) {
        this(id, WoundKind.parse(kind), triangle, u, v, severity, size, emitted, lastRelease);
    }
    public Site origin(){return new Site(triangle,u,v);}
    public BodyWound withSeverity(int value){return new BodyWound(id,kind,triangle,u,v,value,size,emitted,lastRelease,peakSeverity,shape);}
    public BodyWound heal(){return withSeverity(severity-1);}
    public BodyWound released(long tick){return new BodyWound(id,kind,triangle,u,v,severity,size,emitted+1,tick,peakSeverity,shape);}
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);t.putString("Kind",kind.id());t.putInt("Triangle",triangle);
        t.putFloat("U",u);t.putFloat("V",v);t.putInt("Severity",severity);t.putInt("PeakSeverity",peakSeverity);t.putFloat("Size",size);t.putInt("Emitted",emitted);t.putLong("LastRelease",lastRelease);
        t.putString("Shape",shape.id());return t;}
    // Legacy Display* tags are ignored: live bodies and remains both draw at the original injury site.
    public static List<BodyWound> read(CompoundTag t){var result=new ArrayList<BodyWound>();var ids=new HashSet<UUID>();
        for(var raw:t.getList("Wounds",Tag.TAG_COMPOUND)){var w=(CompoundTag)raw;if(w.getInt("Severity")<=0||!w.hasUUID("Id")||!ids.add(w.getUUID("Id")))continue;
            result.add(new BodyWound(w.getUUID("Id"),WoundKind.parse(w.getString("Kind")),w.getInt("Triangle"),w.getFloat("U"),w.getFloat("V"),
                    w.getInt("Severity"),w.getFloat("Size"),w.getInt("Emitted"),w.getLong("LastRelease"),
                    w.contains("PeakSeverity",Tag.TAG_INT)?w.getInt("PeakSeverity"):w.getInt("Severity"),
                    WoundShape.parse(w.getString("Shape"),WoundKind.parse(w.getString("Kind")))));if(result.size()==8)break;}
        return List.copyOf(result);}
    public static CompoundTag write(List<BodyWound> wounds){var t=new CompoundTag();var list=new ListTag();wounds.forEach(w->list.add(w.save()));t.put("Wounds",list);return t;}
}
