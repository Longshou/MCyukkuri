package io.github.longshou.mcyukkuri.body;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** NBT representation of the shared wound data. */
public final class WoundTags {
    private WoundTags() {}

    public static CompoundTag save(BodyWound w){var t=new CompoundTag();t.putUUID("Id",w.id());t.putString("Kind",w.kind().id());t.putInt("Triangle",w.triangle());
        t.putFloat("U",w.u());t.putFloat("V",w.v());t.putInt("Severity",w.severity());t.putInt("PeakSeverity",w.peakSeverity());t.putFloat("Size",w.size());t.putInt("Emitted",w.emitted());t.putLong("LastRelease",w.lastRelease());
        t.putFloat("Points",w.points());t.putString("Shape",w.shape().id());return t;}
    // Legacy Display* tags are ignored: live bodies and remains both draw at the original injury site.
    public static List<BodyWound> read(CompoundTag t){var result=new ArrayList<BodyWound>();var ids=new HashSet<UUID>();
        for(var raw:t.getList("Wounds",Tag.TAG_COMPOUND)){var w=(CompoundTag)raw;if(w.getInt("Severity")<=0||!w.hasUUID("Id")||!ids.add(w.getUUID("Id")))continue;
            result.add(new BodyWound(w.getUUID("Id"),WoundKind.parse(w.getString("Kind")),w.getInt("Triangle"),w.getFloat("U"),w.getFloat("V"),
                    w.getInt("Severity"),w.getFloat("Size"),w.getInt("Emitted"),w.getLong("LastRelease"),
                    w.contains("PeakSeverity",Tag.TAG_INT)?w.getInt("PeakSeverity"):w.getInt("Severity"),
                    WoundShape.parse(w.getString("Shape"),WoundKind.parse(w.getString("Kind"))),
                    w.contains("Points",Tag.TAG_FLOAT)?w.getFloat("Points"):0));if(result.size()==8)break;}
        return List.copyOf(result);}
    public static CompoundTag write(List<BodyWound> wounds){var t=new CompoundTag();var list=new ListTag();wounds.forEach(w->list.add(save(w)));t.put("Wounds",list);return t;}
}
