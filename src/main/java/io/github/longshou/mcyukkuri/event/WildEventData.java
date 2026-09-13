package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Event progress only. Ancestry, food and ownership remain in their existing authorities. */
public final class WildEventData extends SavedData {
    public enum Kind { CONCERT, SISTERS, SINGLE_PARENT, ADOPTION, ABANDONMENT, RIVER, EXPEDITION, CARD }
    public enum Phase { APPROACH, WAITING, RESPONDING, SEPARATED, COMPLETE, INTERRUPTED, REFUSED }
    public static final class Scene {
        public final UUID id;
        public final Kind kind;
        public final String card;
        public final GlobalPos origin;
        public final List<UUID> members;
        public final Set<UUID> fed = new HashSet<>();
        public UUID player, target;
        public GlobalPos destination;
        public transient Object concertToken;
        public Phase phase = Phase.APPROACH;
        public int ticks, phaseTicks, responses, sourceCount;
        public String result = "";
        public Scene(UUID id, Kind kind, String card, GlobalPos origin, List<UUID> members) {
            this.id=id; this.kind=kind; this.card=card; this.origin=origin; this.members=List.copyOf(members);
        }
        public boolean active() { return phase != Phase.COMPLETE && phase != Phase.INTERRUPTED && phase != Phase.REFUSED; }
    }
    private record Delay(GlobalPos origin, long until) {}
    private final Map<UUID, Scene> scenes = new LinkedHashMap<>();
    private final Map<UUID, UUID> claims = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final List<Delay> regions = new ArrayList<>();
    public static WildEventData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                new Factory<>(WildEventData::new, WildEventData::load), "mcyukkuri_wild_events");
    }
    public Scene scene(UUID id) { return scenes.get(id); }
    public Scene forMember(UUID id) { return scenes.get(claims.get(id)); }
    public List<Scene> scenes() { return List.copyOf(scenes.values()); }
    public boolean canStart(GlobalPos origin, List<UUID> members, long now) {
        if (members.isEmpty() || members.size()>6 || new HashSet<>(members).size()!=members.size()) return false;
        if (members.stream().anyMatch(id -> claims.containsKey(id) || cooldowns.getOrDefault(id,0L)>now)) return false;
        if (regions.stream().anyMatch(d -> d.until>now && near(d.origin, origin))) return false;
        return scenes.values().stream().noneMatch(s -> s.active() && near(s.origin,origin));
    }
    private static boolean near(GlobalPos a, GlobalPos b) {
        return a.dimension().equals(b.dimension()) && a.pos().distSqr(b.pos())<32*32;
    }
    public Scene start(Kind kind, String card, GlobalPos origin, List<UUID> members, UUID player, long now) {
        if (!canStart(origin,members,now)) return null;
        Scene scene=new Scene(UUID.randomUUID(),kind,card,origin,members); scene.player=player;
        scenes.put(scene.id,scene); members.forEach(id -> claims.put(id,scene.id)); setDirty(); return scene;
    }
    public void phase(Scene scene, Phase phase) { scene.phase=phase; scene.phaseTicks=0; setDirty(); }
    public void finish(Scene scene, Phase phase, String result, long now) {
        if (!scene.active()) return;
        phase(scene,phase); scene.result=result;
        for(UUID member:scene.members) { claims.remove(member,scene.id); cooldowns.put(member,now+1200); }
        regions.add(new Delay(scene.origin,now+400));
        regions.removeIf(d -> d.until<=now); cooldowns.entrySet().removeIf(e -> e.getValue()<=now);
        while(scenes.size()>256) {
            UUID old=scenes.values().stream().filter(s -> !s.active()).map(s -> s.id).findFirst().orElse(null);
            if(old==null)break; scenes.remove(old);
        }
        setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version",1); ListTag list=new ListTag();
        for(Scene s:scenes.values()) {
            CompoundTag n=new CompoundTag(); n.putUUID("Id",s.id);n.putString("Kind",s.kind.name());n.putString("Card",s.card);
            FamilyData.putPosition(n,"Origin",s.origin);n.putString("Phase",s.phase.name());n.putInt("Ticks",s.ticks);
            n.putInt("PhaseTicks",s.phaseTicks);n.putInt("Responses",s.responses);n.putInt("SourceCount",s.sourceCount);n.putString("Result",s.result);
            if(s.player!=null)n.putUUID("Player",s.player);if(s.target!=null)n.putUUID("Target",s.target);
            FamilyData.putPosition(n,"Destination",s.destination);
            ListTag members=new ListTag();for(UUID id:s.members){CompoundTag m=new CompoundTag();m.putUUID("Id",id);m.putBoolean("Fed",s.fed.contains(id));members.add(m);}
            n.put("Members",members);list.add(n);
        }
        tag.put("Scenes",list);list=new ListTag();
        for(var e:cooldowns.entrySet()){CompoundTag n=new CompoundTag();n.putUUID("Id",e.getKey());n.putLong("Until",e.getValue());list.add(n);}tag.put("Cooldowns",list);
        list=new ListTag();for(var d:regions){CompoundTag n=new CompoundTag();FamilyData.putPosition(n,"Origin",d.origin);n.putLong("Until",d.until);list.add(n);}tag.put("Regions",list);
        return tag;
    }
    public static WildEventData load(CompoundTag tag, HolderLookup.Provider registries) {
        WildEventData data=new WildEventData();
        for(Tag entry:tag.getList("Scenes",Tag.TAG_COMPOUND)) {
            CompoundTag n=(CompoundTag)entry; GlobalPos origin=FamilyData.position(n,"Origin");
            if(!n.hasUUID("Id")||origin==null)continue;
            try {
                List<UUID> ids=new ArrayList<>();Set<UUID> fed=new HashSet<>();
                for(Tag member:n.getList("Members",Tag.TAG_COMPOUND)){CompoundTag m=(CompoundTag)member;if(m.hasUUID("Id")){UUID id=m.getUUID("Id");ids.add(id);if(m.getBoolean("Fed"))fed.add(id);}}
                if(ids.isEmpty()||ids.size()>6||new HashSet<>(ids).size()!=ids.size())continue;
                Scene s=new Scene(n.getUUID("Id"),Kind.valueOf(n.getString("Kind")),n.getString("Card"),origin,ids);
                s.phase=Phase.valueOf(n.getString("Phase"));s.ticks=Math.clamp(n.getInt("Ticks"),0,2400);s.phaseTicks=Math.clamp(n.getInt("PhaseTicks"),0,2400);
                s.responses=Math.max(0,n.getInt("Responses"));s.sourceCount=Math.max(0,n.getInt("SourceCount"));s.result=n.getString("Result");s.fed.addAll(fed);
                s.player=n.hasUUID("Player")?n.getUUID("Player"):null;s.target=n.hasUUID("Target")?n.getUUID("Target"):null;
                s.destination=FamilyData.position(n,"Destination");
                if(s.active() && ids.stream().anyMatch(data.claims::containsKey))continue;
                data.scenes.put(s.id,s);if(s.active())ids.forEach(id -> data.claims.put(id,s.id));
            } catch(IllegalArgumentException ignored) { /* Unknown kinds must not fabricate encounters. */ }
        }
        for(Tag entry:tag.getList("Cooldowns",Tag.TAG_COMPOUND)){CompoundTag n=(CompoundTag)entry;if(n.hasUUID("Id"))data.cooldowns.put(n.getUUID("Id"),n.getLong("Until"));}
        for(Tag entry:tag.getList("Regions",Tag.TAG_COMPOUND)){CompoundTag n=(CompoundTag)entry;GlobalPos p=FamilyData.position(n,"Origin");if(p!=null)data.regions.add(new Delay(p,n.getLong("Until")));}
        return data;
    }
}
