package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.coordination.DeadlineMap;
import io.github.longshou.mcyukkuri.coordination.RegionKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
        public final EventWatchdog.State supervision = new EventWatchdog.State();
        public Claim(UUID id, List<UUID> members, GlobalPos site) { this.id=id; this.members=List.copyOf(members); this.site=site; }
        public boolean active() { return stage != Stage.ENDED; }
        public void stage(Stage next) { stage=next; stageTicks=0; }
    }
    private static final int KEEP = 64;
    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private MinecraftServer server;
    private final DeadlineMap<UUID> cooldowns = new DeadlineMap<>();
    private final DeadlineMap<RegionKey> regions = new DeadlineMap<>();
    private final DeadlineMap<Long> legacyRegions = new DeadlineMap<>();
    public static HomeClaimData get(ServerLevel level) {
        var data = level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(HomeClaimData::new, HomeClaimData::load), "mcyukkuri_home_claims");
        data.server = level.getServer(); return data;
    }
    public Claim claim(UUID id) { return claims.get(id); }
    public Claim forMember(UUID member) {
        return forMember(member,claim -> true);
    }
    public Claim forMember(UUID member, Predicate<Claim> purpose) {
        for (var token : EventReservations.owners(server, member)) if (token.source() == EventReservations.Source.HOME) {
            var claim = claims.get(token.id()); if (claim != null && claim.active() && purpose.test(claim)) return claim;
        }
        return null;
    }
    public List<Claim> all() { return List.copyOf(claims.values()); }
    public List<Claim> active() { return claims.values().stream().filter(Claim::active).toList(); }
    private static long legacyRegion(GlobalPos site) {
        return (((long)(site.pos().getX()>>6))<<32) ^ ((site.pos().getZ()>>6) & 0xFFFFFFFFL) ^ (site.dimension().location().hashCode() * 31L);
    }
    public boolean regionBlocked(GlobalPos site, long now) { return regions.active(RegionKey.cell(site,64),now) || legacyRegions.active(legacyRegion(site),now); }
    public void blockRegion(GlobalPos site, long until) { regions.extend(RegionKey.cell(site,64),until); setDirty(); }
    public boolean cooling(UUID member, long now) { return cooldowns.active(member,now); }
    public Claim start(List<UUID> members, GlobalPos site, UUID player) {
        if (members.isEmpty() || members.size()>6 || new HashSet<>(members).size()!=members.size()
                || !EventReservations.free(server,members)) return null;
        Claim claim=new Claim(UUID.randomUUID(),members,site); claim.player=player;
        if (!EventReservations.acquire(server,members,new EventReservations.Token(EventReservations.Source.HOME,claim.id))) return null;
        claims.put(claim.id,claim); setDirty(); log(claim,"started"); return claim;
    }
    /** Releases the members; ended records stay as the event log. */
    public void end(Claim claim, String result, long now, long memberCooldown, long regionCooldown) {
        if (!claim.active() || claims.get(claim.id) != claim) return;
        EventReservations.release(server,claim.members,new EventReservations.Token(EventReservations.Source.HOME,claim.id));
        claim.stage(Stage.ENDED); if (!result.isEmpty() || claim.result.isEmpty()) claim.result=result;
        for (UUID id:claim.members) { cooldowns.put(id,DeadlineMap.after(now,memberCooldown)); }
        regions.extend(RegionKey.cell(claim.site,64),DeadlineMap.after(now,regionCooldown));
        cooldowns.prune(now); regions.prune(now); legacyRegions.prune(now);
        List<UUID> ended=claims.values().stream().filter(c -> !c.active()).map(c -> c.id).toList();
        for (int i=0;i<ended.size()-KEEP;i++) claims.remove(ended.get(i));
        setDirty();
        log(claim,"ended");
    }
    private void log(Claim claim,String action){
        if(server==null)return;
        var level=server.getLevel(claim.site.dimension());
        GameplayLog.world(level==null?server.overworld():level,"home-claim",
                "{} claim={} site={} player={} members={} stage={} ticks={} holdTicks={} quarrels={} result={}",
                action,claim.id,claim.site,claim.player,claim.members,claim.stage,claim.ticks,claim.holdTicks,claim.quarrels,claim.result);
    }
    static boolean touches(Claim claim, ResourceKey<Level> dimension, AABB box, Set<UUID> members) {
        return claim.members.stream().anyMatch(members::contains)
                || claim.site.dimension().equals(dimension) && box.contains(Vec3.atCenterOf(claim.site.pos()));
    }
    /** Playground field clear only, after {@link HomeClaim#forget} ended the active claims: no record or cooldown of the box remains. */
    void forget(ResourceKey<Level> dimension, AABB box, Set<UUID> members) {
        claims.values().removeIf(c -> !c.active() && touches(c,dimension,box,members));
        for (UUID id:members) cooldowns.remove(id);
        int minX=Math.floorDiv((int)Math.floor(box.minX),64), maxX=Math.floorDiv((int)Math.ceil(box.maxX),64);
        int minZ=Math.floorDiv((int)Math.floor(box.minZ),64), maxZ=Math.floorDiv((int)Math.ceil(box.maxZ),64);
        for (int x=minX;x<=maxX;x++) for (int z=minZ;z<=maxZ;z++) {
            regions.remove(new RegionKey(dimension,x,z));
            legacyRegions.remove(legacyRegion(GlobalPos.of(dimension,new BlockPos(x*64,0,z*64))));
        }
        setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version",2); ListTag list=new ListTag();
        for (Claim c:claims.values()) {
            CompoundTag n=new CompoundTag(); n.putUUID("Id",c.id); FamilyData.putPosition(n,"Site",c.site); FamilyData.putPosition(n,"Retreat",c.retreat);
            c.supervision.save(n);
            n.putString("Stage",c.stage.name()); n.putString("Result",c.result); n.putBoolean("Declared",c.declared);
            n.putInt("Ticks",c.ticks); n.putInt("HoldTicks",c.holdTicks); n.putInt("StageTicks",c.stageTicks); n.putInt("Quarrels",c.quarrels);
            n.putBoolean("HasBox",c.hasBox); n.putBoolean("FoodInside",c.foodInside);
            n.putIntArray("Box",new int[]{c.minX,c.minY,c.minZ,c.maxX,c.maxY,c.maxZ});
            if (c.player!=null) n.putUUID("Player",c.player);
            ListTag members=new ListTag(); for (UUID id:c.members) { CompoundTag m=new CompoundTag(); m.putUUID("Id",id); members.add(m); }
            n.put("Members",members); list.add(n);
        }
        tag.put("Claims",list); list=new ListTag();
        for (var e:cooldowns.snapshot().entrySet()) { CompoundTag n=new CompoundTag(); n.putUUID("Id",e.getKey()); n.putLong("Until",e.getValue()); list.add(n); }
        tag.put("Cooldowns",list); list=new ListTag();
        for (var e:legacyRegions.snapshot().entrySet()) { CompoundTag n=new CompoundTag(); n.putLong("Key",e.getKey()); n.putLong("Until",e.getValue()); list.add(n); }
        tag.put("Regions",list); list=new ListTag();
        for (var e:regions.snapshot().entrySet()) {
            CompoundTag n=new CompoundTag(); n.putString("Dimension",e.getKey().dimension().location().toString());
            n.putInt("CellX",e.getKey().x()); n.putInt("CellZ",e.getKey().z()); n.putLong("Until",e.getValue()); list.add(n);
        }
        tag.put("RegionCooldowns",list); return tag;
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
                c.supervision.load(n);
                c.retreat=FamilyData.position(n,"Retreat"); c.result=n.getString("Result"); c.declared=n.getBoolean("Declared");
                c.ticks=Math.max(0,n.getInt("Ticks")); c.holdTicks=Math.max(0,n.getInt("HoldTicks")); c.stageTicks=Math.max(0,n.getInt("StageTicks"));
                c.quarrels=Math.clamp(n.getInt("Quarrels"),0,64); c.hasBox=n.getBoolean("HasBox"); c.foodInside=!n.contains("FoodInside") || n.getBoolean("FoodInside");
                int[] box=n.getIntArray("Box");
                if (box.length==6) { c.minX=box[0]; c.minY=box[1]; c.minZ=box[2]; c.maxX=box[3]; c.maxY=box[4]; c.maxZ=box[5]; } else c.hasBox=false;
                c.player=n.hasUUID("Player")?n.getUUID("Player"):null;
                data.claims.putIfAbsent(c.id,c); // Conflicting members remain separate authoritative records.
            } catch (IllegalArgumentException ignored) { /* Unknown stages must not fabricate claims. */ }
        }
        for (Tag entry:tag.getList("Cooldowns",Tag.TAG_COMPOUND)) { CompoundTag n=(CompoundTag)entry; if (n.hasUUID("Id")) data.cooldowns.put(n.getUUID("Id"),n.getLong("Until")); }
        for (Tag entry:tag.getList("Regions",Tag.TAG_COMPOUND)) { CompoundTag n=(CompoundTag)entry; data.legacyRegions.extend(n.getLong("Key"),n.getLong("Until")); }
        for (Tag entry:tag.getList("RegionCooldowns",Tag.TAG_COMPOUND)) {
            CompoundTag n=(CompoundTag)entry; var dimension=ResourceLocation.tryParse(n.getString("Dimension"));
            if (dimension!=null) data.regions.extend(new RegionKey(ResourceKey.create(Registries.DIMENSION,dimension),n.getInt("CellX"),n.getInt("CellZ")),n.getLong("Until"));
        }
        return data;
    }
    /** Convenience for callers that only need block coordinates. */
    public static BlockPos pos(Claim c) { return c.site.pos(); }
}
