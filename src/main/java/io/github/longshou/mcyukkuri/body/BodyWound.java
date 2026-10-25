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
                        int emitted, long lastRelease, int peakSeverity, WoundShape shape, float points) {
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
        points=Float.isFinite(points)?Math.clamp(Math.max(points,minPoints(severity)),0,60):minPoints(severity);
    }
    /** Accepted-loss points at which a wound reaches the grade: 1, 3, 5 (bands match a single hit, ceil(loss/2)). */
    public static float minPoints(int severity){return severity>=3?5:severity==2?3:1;}
    public static int gradeOf(float points){return Math.clamp((int)Math.ceil(points/2),1,3);}
    /** Derived points: the minimum for the grade (explicit-severity creation, scenes, bites, thermal, scars). */
    public BodyWound(UUID id,WoundKind kind,int triangle,float u,float v,int severity,float size,int emitted,long lastRelease,int peakSeverity,WoundShape shape) {
        this(id,kind,triangle,u,v,severity,size,emitted,lastRelease,peakSeverity,shape,0);
    }
    public BodyWound(UUID id,WoundKind kind,int triangle,float u,float v,int severity,float size,int emitted,long lastRelease) {
        this(id,kind,triangle,u,v,severity,size,emitted,lastRelease,severity,WoundShape.defaultFor(kind),0);
    }
    public BodyWound(UUID id,WoundKind kind,int triangle,float u,float v,int severity,float size,int emitted,long lastRelease,int peakSeverity) {
        this(id,kind,triangle,u,v,severity,size,emitted,lastRelease,peakSeverity,WoundShape.defaultFor(kind),0);
    }
    /** String boundary retained for NBT, commands and status tools. */
    public BodyWound(UUID id, String kind, int triangle, float u, float v, int severity, float size,
                     int emitted, long lastRelease) {
        this(id, WoundKind.parse(kind), triangle, u, v, severity, size, emitted, lastRelease);
    }
    public Site origin(){return new Site(triangle,u,v);}
    /** Setting a grade resets the accumulated points to that grade's minimum, so healing and bumps stay coherent. */
    public BodyWound withSeverity(int value){int s=Math.clamp(value,1,3);return new BodyWound(id,kind,triangle,u,v,s,size,emitted,lastRelease,peakSeverity,shape,minPoints(s));}
    /** A repeat hit merged into this wound: add its loss; grade is the larger of the current one and the points band. */
    public BodyWound struck(float loss,int hitSeverity,boolean bump){float p=points+Math.max(0,loss);int s=Math.min(3,Math.max(Math.max(severity,hitSeverity),gradeOf(p))+(bump?1:0));
        return new BodyWound(id,kind,triangle,u,v,s,size,emitted,lastRelease,peakSeverity,shape,Math.max(p,minPoints(s)));}
    public BodyWound heal(){return withSeverity(severity-1);}
    public BodyWound released(long tick){return new BodyWound(id,kind,triangle,u,v,severity,size,emitted+1,tick,peakSeverity,shape,points);}
    public CompoundTag save(){var t=new CompoundTag();t.putUUID("Id",id);t.putString("Kind",kind.id());t.putInt("Triangle",triangle);
        t.putFloat("U",u);t.putFloat("V",v);t.putInt("Severity",severity);t.putInt("PeakSeverity",peakSeverity);t.putFloat("Size",size);t.putInt("Emitted",emitted);t.putLong("LastRelease",lastRelease);
        t.putFloat("Points",points);t.putString("Shape",shape.id());return t;}
    // Legacy Display* tags are ignored: live bodies and remains both draw at the original injury site.
    public static List<BodyWound> read(CompoundTag t){var result=new ArrayList<BodyWound>();var ids=new HashSet<UUID>();
        for(var raw:t.getList("Wounds",Tag.TAG_COMPOUND)){var w=(CompoundTag)raw;if(w.getInt("Severity")<=0||!w.hasUUID("Id")||!ids.add(w.getUUID("Id")))continue;
            result.add(new BodyWound(w.getUUID("Id"),WoundKind.parse(w.getString("Kind")),w.getInt("Triangle"),w.getFloat("U"),w.getFloat("V"),
                    w.getInt("Severity"),w.getFloat("Size"),w.getInt("Emitted"),w.getLong("LastRelease"),
                    w.contains("PeakSeverity",Tag.TAG_INT)?w.getInt("PeakSeverity"):w.getInt("Severity"),
                    WoundShape.parse(w.getString("Shape"),WoundKind.parse(w.getString("Kind"))),
                    w.contains("Points",Tag.TAG_FLOAT)?w.getFloat("Points"):0));if(result.size()==8)break;}
        return List.copyOf(result);}
    public static CompoundTag write(List<BodyWound> wounds){var t=new CompoundTag();var list=new ListTag();wounds.forEach(w->list.add(w.save()));t.put("Wounds",list);return t;}
}
