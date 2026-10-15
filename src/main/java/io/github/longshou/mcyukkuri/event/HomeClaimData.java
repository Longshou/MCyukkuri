package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Home-claim ("おうち宣言") progress. Identities, food and ownership stay in their existing authorities. */
public final class HomeClaimData extends SavedData {
    public enum Stage { GATHER, HOLD, ENCLOSED, COLLAPSING, RETREAT, ENDED }
    public static final class Claim {
        public final UUID id;
        public final List<UUID> members;
        public final GlobalPos site;
        public Stage stage = Stage.GATHER;
        public UUID player;
        public GlobalPos retreat;
        public boolean declared;
        public int ticks, holdTicks, stageTicks, quarrels;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public boolean hasBox, foodInside = true;
        public String result = "";
        public Claim(UUID id, List<UUID> members, GlobalPos site) { this.id=id; this.members=List.copyOf(members); this.site=site; }
        public boolean active() { return stage != Stage.ENDED; }
        public void stage(Stage next) { stage=next; stageTicks=0; }
    }
    private static final int KEEP = 64;
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private final Map<UUID, UUID> byMember = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<Long, Long> regions = new HashMap<>();
    public static HomeClaimData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(HomeClaimData::new, HomeClaimData::load), "mcyukkuri_home_claims");
    }
    public Claim claim(UUID id) { return claims.get(id); }
    public Claim forMember(UUID member) { UUID id=byMember.get(member); return id==null?null:claims.get(id); }
    public List<Claim> all() { return List.copyOf(claims.values()); }
    public List<Claim> active() { return claims.values().stream().filter(Claim::active).toList(); }
    private static long region(GlobalPos site) {
        return (((long)(site.pos().getX()>>6))<<32) ^ ((site.pos().getZ()>>6) & 0xFFFFFFFFL) ^ (site.dimension().location().hashCode() * 31L);
    }
    public boolean regionBlocked(GlobalPos site, long now) { return regions.getOrDefault(region(site),0L) > now; }
    public void blockRegion(GlobalPos site, long until) { regions.merge(region(site),until,Math::max); setDirty(); }
    public boolean cooling(UUID member, long now) { return cooldowns.getOrDefault(member,0L) > now; }
    public Claim start(List<UUID> members, GlobalPos site, UUID player) {
        if (members.isEmpty() || members.size()>6 || new HashSet<>(members).size()!=members.size()
                || members.stream().anyMatch(byMember::containsKey)) return null;
        Claim claim=new Claim(UUID.randomUUID(),members,site); claim.player=player;
        claims.put(claim.id,claim); members.forEach(id -> byMember.put(id,claim.id)); setDirty(); return claim;
    }
    /** Releases the members; ended records stay as the event log. */
    public void end(Claim claim, String result, long now, long memberCooldown, long regionCooldown) {
        if (!claim.active()) return;
        claim.stage(Stage.ENDED); if (!result.isEmpty() || claim.result.isEmpty()) claim.result=result;
        for (UUID id:claim.members) { byMember.remove(id,claim.id); cooldowns.put(id,now+memberCooldown); }
        regions.merge(region(claim.site),now+regionCooldown,Math::max);
        cooldowns.entrySet().removeIf(e -> e.getValue()<=now); regions.entrySet().removeIf(e -> e.getValue()<=now);
        List<UUID> ended=claims.values().stream().filter(c -> !c.active()).map(c -> c.id).toList();
        for (int i=0;i<ended.size()-KEEP;i++) claims.remove(ended.get(i));
        setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version",1); ListTag list=new ListTag();
        for (Claim c:claims.values()) {
            CompoundTag n=new CompoundTag(); n.putUUID("Id",c.id); FamilyData.putPosition(n,"Site",c.site); FamilyData.putPosition(n,"Retreat",c.retreat);
            n.putString("Stage",c.stage.name()); n.putString("Result",c.result); n.putBoolean("Declared",c.declared);
            n.putInt("Ticks",c.ticks); n.putInt("HoldTicks",c.holdTicks); n.putInt("StageTicks",c.stageTicks); n.putInt("Quarrels",c.quarrels);
            n.putBoolean("HasBox",c.hasBox); n.putBoolean("FoodInside",c.foodInside);
            n.putIntArray("Box",new int[]{c.minX,c.minY,c.minZ,c.maxX,c.maxY,c.maxZ});
            if (c.player!=null) n.putUUID("Player",c.player);
            ListTag members=new ListTag(); for (UUID id:c.members) { CompoundTag m=new CompoundTag(); m.putUUID("Id",id); members.add(m); }
            n.put("Members",members); list.add(n);
        }
        tag.put("Claims",list); list=new ListTag();
        for (var e:cooldowns.entrySet()) { CompoundTag n=new CompoundTag(); n.putUUID("Id",e.getKey()); n.putLong("Until",e.getValue()); list.add(n); }
        tag.put("Cooldowns",list); list=new ListTag();
        for (var e:regions.entrySet()) { CompoundTag n=new CompoundTag(); n.putLong("Key",e.getKey()); n.putLong("Until",e.getValue()); list.add(n); }
        tag.put("Regions",list); return tag;
    }
    public static HomeClaimData load(CompoundTag tag, HolderLookup.Provider registries) {
        HomeClaimData data=new HomeClaimData();
        for (Tag entry:tag.getList("Claims",Tag.TAG_COMPOUND)) {
            CompoundTag n=(CompoundTag)entry; GlobalPos site=FamilyData.position(n,"Site");
            if (!n.hasUUID("Id") || site==null) continue;
            try {
                List<UUID> ids=new ArrayList<>();
                for (Tag member:n.getList("Members",Tag.TAG_COMPOUND)) { CompoundTag m=(CompoundTag)member; if (m.hasUUID("Id")) ids.add(m.getUUID("Id")); }
                if (ids.isEmpty() || ids.size()>6 || new HashSet<>(ids).size()!=ids.size()) continue;
                Claim c=new Claim(n.getUUID("Id"),ids,site); c.stage=Stage.valueOf(n.getString("Stage"));
                c.retreat=FamilyData.position(n,"Retreat"); c.result=n.getString("Result"); c.declared=n.getBoolean("Declared");
                c.ticks=Math.max(0,n.getInt("Ticks")); c.holdTicks=Math.max(0,n.getInt("HoldTicks")); c.stageTicks=Math.max(0,n.getInt("StageTicks"));
                c.quarrels=Math.clamp(n.getInt("Quarrels"),0,64); c.hasBox=n.getBoolean("HasBox"); c.foodInside=!n.contains("FoodInside") || n.getBoolean("FoodInside");
                int[] box=n.getIntArray("Box");
                if (box.length==6) { c.minX=box[0]; c.minY=box[1]; c.minZ=box[2]; c.maxX=box[3]; c.maxY=box[4]; c.maxZ=box[5]; } else c.hasBox=false;
                c.player=n.hasUUID("Player")?n.getUUID("Player"):null;
                if (c.active() && ids.stream().anyMatch(data.byMember::containsKey)) continue;
                data.claims.put(c.id,c); if (c.active()) ids.forEach(id -> data.byMember.put(id,c.id));
            } catch (IllegalArgumentException ignored) { /* Unknown stages must not fabricate claims. */ }
        }
        for (Tag entry:tag.getList("Cooldowns",Tag.TAG_COMPOUND)) { CompoundTag n=(CompoundTag)entry; if (n.hasUUID("Id")) data.cooldowns.put(n.getUUID("Id"),n.getLong("Until")); }
        for (Tag entry:tag.getList("Regions",Tag.TAG_COMPOUND)) { CompoundTag n=(CompoundTag)entry; data.regions.put(n.getLong("Key"),n.getLong("Until")); }
        return data;
    }
    /** Convenience for callers that only need block coordinates. */
    public static BlockPos pos(Claim c) { return c.site.pos(); }
}
