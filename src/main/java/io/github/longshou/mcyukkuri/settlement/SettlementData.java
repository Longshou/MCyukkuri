package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.family.FamilyData;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Dimension-local communities. Unloaded members are never treated as dead. */
public final class SettlementData extends SavedData {
    public enum Role { FREE, MEMBER, LEADER, OUTCAST, SLAVE }
    public enum Kind { FAMILY, FOREST, BANDITS }
    public static final class Group {
        public final UUID id;
        public final Kind kind;
        /** Moves when a migration finishes; everything else reads it as before. */
        public BlockPos center;
        public UUID leader;
        /** Transient seasonal state, rebuilt after a restart: snow seen, gathering deadline and the day of the last spring gathering. */
        public boolean snowing;
        public long gatherUntil, gatherDay = -1;
        /** Only non-default roles are stored: OUTCAST and SLAVE. LEADER follows {@link #leader}; everyone else is a MEMBER. */
        public final Map<UUID, Role> roles = new HashMap<>();
        /** The group is supposed to have a leader (born with one). Leaderless forest groups stay as they are. */
        public boolean led;
        /** Game time the leader seat became empty; 0 when it is not empty. */
        public long vacantSince;
        /** Threat reports from scouts: count, latest position and time (input for later migration logic). */
        public int threat;
        public BlockPos threatPos;
        public long threatTime;
        /** Transient: no new election attempt before this game time. */
        public long electionRetry;
        /** Migration in progress: destination and start time; null / 0 when the group is settled. */
        public BlockPos migrateTo;
        public long migrateSince;
        /** Transient: predators already counted (id to game time), the next decay step and the next migration attempt. */
        public final Map<UUID, Long> sighted = new HashMap<>();
        public long decayAt, migrateRetry, attackedAt;
        public Group(UUID id, Kind kind, BlockPos center, UUID leader) {
            this.id = id; this.kind = kind; this.center = center.immutable(); this.leader = leader; this.led = leader != null;
        }
        public Role role(UUID member) {
            if (member == null) return Role.FREE;
            if (member.equals(leader)) return Role.LEADER;
            return roles.getOrDefault(member, Role.MEMBER);
        }
        /** Seat is empty and the group expects a leader. */
        public boolean vacant() { return led && leader == null; }
    }
    private final Map<UUID, Group> groups = new HashMap<>();
    private final Set<Long> discovered = new HashSet<>();
    public static SettlementData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(SettlementData::new, SettlementData::load), "mcyukkuri_settlements");
    }
    public Group group(UUID id) { return id == null ? null : groups.get(id); }
    public java.util.Collection<Group> groups() { return java.util.List.copyOf(groups.values()); }
    public void add(Group group) { groups.put(group.id, group); setDirty(); }
    public void remove(UUID id) { if(groups.remove(id)!=null)setDirty(); }
    /** One natural community attempt per 64 x 64 area, persisted even after population loss. */
    public boolean discover(BlockPos pos) {
        boolean fresh = discovered.add(ChunkPos.asLong(Math.floorDiv(pos.getX(), 64), Math.floorDiv(pos.getZ(), 64)));
        if (fresh) setDirty();
        return fresh;
    }
    public void refreshLeader(ServerLevel level, Group group) {
        if (group.leader == null) return;
        var person = FamilyData.get(level).person(group.leader);
        if (person != null && (person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED
                || person.status == FamilyData.Status.LOST)) {
            group.leader = null;
            if (group.led) group.vacantSince = Math.max(1, level.getGameTime());
            setDirty();
        }
    }
    /** Seats a (new) leader: clears the vacancy and any stored role of that member. */
    public void setLeader(Group group, UUID member) {
        group.leader = member; group.vacantSince = 0; group.roles.remove(member);
        if (member != null) group.led = true;
        setDirty();
    }
    public Role role(Group group, UUID member) { return group == null ? Role.FREE : group.role(member); }
    /** MEMBER removes the entry; the leader's role cannot be overridden here (use {@link #setLeader}). */
    public void setRole(Group group, UUID member, Role role) {
        if (group == null || member == null) return;
        if (role == Role.MEMBER || role == Role.FREE || role == Role.LEADER) group.roles.remove(member);
        else group.roles.put(member, role);
        setDirty();
    }
    /** A scout saw danger near the community; the later migration logic reads these fields. */
    public void reportThreat(Group group, BlockPos pos, long gameTime) { reportThreat(group, pos, gameTime, 1); }
    /** Weighted report: a sighting counts 1, an attack on a member 2, a death 3, an omen (see {@link Migration#omen}) 2. Capped so decay stays meaningful. */
    public void reportThreat(Group group, BlockPos pos, long gameTime, int weight) {
        if (group == null || weight <= 0) return;
        group.threat = Math.min(20, group.threat + weight); group.decayAt = gameTime; group.threatPos = pos.immutable(); group.threatTime = gameTime; setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Group group : groups.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", group.id); value.putString("Kind", group.kind.name()); value.putLong("Center", group.center.asLong());
            if (group.leader != null) value.putUUID("Leader", group.leader);
            value.putBoolean("Led", group.led); value.putLong("VacantSince", group.vacantSince);
            value.putInt("Threat", group.threat); value.putLong("ThreatTime", group.threatTime);
            if (group.threatPos != null) value.putLong("ThreatPos", group.threatPos.asLong());
            if (group.migrateTo != null) { value.putLong("MigrateTo", group.migrateTo.asLong()); value.putLong("MigrateSince", group.migrateSince); }
            ListTag roles = new ListTag();
            for (var role : group.roles.entrySet()) {
                CompoundTag item = new CompoundTag(); item.putUUID("Id", role.getKey()); item.putString("Role", role.getValue().name()); roles.add(item);
            }
            value.put("Roles", roles);
            list.add(value);
        }
        tag.putInt("Version", 1); tag.put("Groups", list);
        tag.putLongArray("Discovered", discovered.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }
    public static SettlementData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementData data = new SettlementData();
        for (long region : tag.getLongArray("Discovered")) data.discovered.add(region);
        for (Tag entry : tag.getList("Groups", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)entry;
            if (!value.hasUUID("Id") || !value.contains("Center", Tag.TAG_LONG)) continue;
            try {
                Group group = new Group(value.getUUID("Id"), Kind.valueOf(value.getString("Kind")), BlockPos.of(value.getLong("Center")),
                        value.hasUUID("Leader") ? value.getUUID("Leader") : null);
                // Older saves have no "Led": a group with a leader was a led group.
                group.led = value.contains("Led", Tag.TAG_BYTE) ? value.getBoolean("Led") : group.leader != null;
                group.vacantSince = Math.max(0, value.getLong("VacantSince"));
                group.threat = Math.max(0, value.getInt("Threat")); group.threatTime = value.getLong("ThreatTime");
                if (value.contains("ThreatPos", Tag.TAG_LONG)) group.threatPos = BlockPos.of(value.getLong("ThreatPos"));
                if (value.contains("MigrateTo", Tag.TAG_LONG)) { group.migrateTo = BlockPos.of(value.getLong("MigrateTo")); group.migrateSince = value.getLong("MigrateSince"); }
                for (Tag item : value.getList("Roles", Tag.TAG_COMPOUND)) {
                    CompoundTag role = (CompoundTag)item;
                    if (!role.hasUUID("Id")) continue;
                    try { group.roles.put(role.getUUID("Id"), Role.valueOf(role.getString("Role"))); }
                    catch (IllegalArgumentException ignored) { /* Unknown roles fall back to MEMBER. */ }
                }
                if (group.led && group.leader == null && group.vacantSince == 0) group.vacantSince = 1; // vacant since long ago
                data.groups.put(group.id, group);
            } catch (IllegalArgumentException ignored) { /* Unknown future kinds do not invent communities. */ }
        }
        return data;
    }
}
