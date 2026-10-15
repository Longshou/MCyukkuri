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
    public enum Kind { FAMILY, FOREST }
    public enum Role { FREE, MEMBER, LEADER, SHUNNED, OUTCAST, UNUN_SLAVE }
    /** Social standing never replaces membership, ancestry, or the previous office. */
    public static final class Member {
        public Role role, previousRole;
        public int rejections;
        public long nextObservation, rememberedUntil, assignedUntil;
        public UUID observer, assignedBy;
        private Member(Role role) { this.role = this.previousRole = role; }
    }
    public static final class Group {
        public final UUID id;
        public final Kind kind;
        public final BlockPos center;
        public UUID leader;
        public boolean hasLeaderOffice, vacancyConfirmed;
        public UUID successor, successionWitness;
        public int successionTicks;
        // Runtime sampling anchor is deliberately not persisted: no offline catch-up.
        long lastSuccessionSample = Long.MIN_VALUE;
        private final Map<UUID, Member> members = new HashMap<>();
        public Group(UUID id, Kind kind, BlockPos center, UUID leader) {
            this.id = id; this.kind = kind; this.center = center.immutable(); this.leader = leader;
            hasLeaderOffice = kind == Kind.FOREST && leader != null;
        }
    }
    private final Map<UUID, Group> groups = new HashMap<>();
    private final Set<Long> discovered = new HashSet<>();
    public static SettlementData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(SettlementData::new, SettlementData::load), "mcyukkuri_settlements");
    }
    public Group group(UUID id) { return id == null ? null : groups.get(id); }
    public void add(Group group) { groups.put(group.id, group); setDirty(); }
    public void remove(UUID id) { if(groups.remove(id)!=null)setDirty(); }
    /** A successful household move changes membership without deleting the family ledger. */
    public void leave(Group group, UUID id) {
        if (group == null) return;
        if (id.equals(group.leader)) vacate(group);
        group.members.remove(id);
        if (id.equals(group.successor) || id.equals(group.successionWitness)) resetSuccession(group);
        setDirty();
    }
    /** Unlike member(), observation of a candidate cannot invent membership. */
    public Member existingMember(Group group, UUID id) { return group.members.get(id); }
    private void vacate(Group group) {
        var old = group.members.get(group.leader);
        if (old != null) {
            if (old.role == Role.LEADER) old.role = Role.MEMBER;
            if (old.previousRole == Role.LEADER) old.previousRole = Role.MEMBER;
        }
        group.leader = null;
        group.vacancyConfirmed = group.kind == Kind.FOREST && group.hasLeaderOffice;
        resetSuccession(group); setDirty();
    }
    public void resetSuccession(Group group) {
        if (group.successor != null || group.successionWitness != null || group.successionTicks != 0) {
            group.successor = group.successionWitness = null; group.successionTicks = 0; setDirty();
        }
    }
    /** Called after real local witnesses have completed the succession interval. */
    public boolean appoint(Group group, UUID id) {
        var next = group.members.get(id);
        if (group.kind != Kind.FOREST || !group.hasLeaderOffice || !group.vacancyConfirmed || group.leader != null
                || next == null || next.role != Role.MEMBER || next.rejections != 0) return false;
        for (var member : group.members.values()) {
            if (member.role == Role.LEADER) member.role = Role.MEMBER;
            if (member.previousRole == Role.LEADER) member.previousRole = Role.MEMBER;
        }
        group.leader = id; group.vacancyConfirmed = false;
        next.role = next.previousRole = Role.LEADER;
        resetSuccession(group); setDirty(); return true;
    }
    public Member member(Group group, UUID id) {
        var member = group.members.get(id);
        if (member == null) {
            member = new Member(id.equals(group.leader) ? Role.LEADER : Role.MEMBER);
            group.members.put(id, member); setDirty();
        }
        return member;
    }
    public Role role(Group group, UUID id) {
        if (group == null) return Role.FREE;
        var member = group.members.get(id);
        return member == null ? id.equals(group.leader) ? Role.LEADER : Role.MEMBER : member.role;
    }
    public UUID activeLeader(Group group) {
        return group.leader != null && role(group, group.leader) == Role.LEADER ? group.leader : null;
    }
    public void restore(Group group, UUID id, long now) {
        var member = member(group, id);
        if (member.rejections == 0 && member.role != Role.SHUNNED && member.role != Role.OUTCAST && member.role != Role.UNUN_SLAVE) return;
        member.role = member.previousRole == Role.LEADER && id.equals(group.leader) ? Role.LEADER : Role.MEMBER;
        member.previousRole = member.role;
        member.rejections = 0; member.nextObservation = now + 200;
        member.rememberedUntil = now + 2400; member.assignedBy = null; member.assignedUntil = 0;
        setDirty();
    }
    /** One natural community attempt per 64 x 64 area, persisted even after population loss. */
    public boolean discover(BlockPos pos) {
        boolean fresh = discovered.add(ChunkPos.asLong(Math.floorDiv(pos.getX(), 64), Math.floorDiv(pos.getZ(), 64)));
        if (fresh) setDirty();
        return fresh;
    }
    public void refreshLeader(ServerLevel level, Group group) {
        if (group.leader == null) return;
        var person = FamilyData.get(level).person(group.leader);
        if (person != null && (person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED)) {
            vacate(group);
        }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Group group : groups.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", group.id); value.putString("Kind", group.kind.name()); value.putLong("Center", group.center.asLong());
            if (group.leader != null) value.putUUID("Leader", group.leader);
            value.putBoolean("HasLeaderOffice", group.hasLeaderOffice);
            value.putBoolean("VacancyConfirmed", group.vacancyConfirmed);
            value.putInt("SuccessionTicks", group.successionTicks);
            if (group.successor != null) value.putUUID("Successor", group.successor);
            if (group.successionWitness != null) value.putUUID("SuccessionWitness", group.successionWitness);
            ListTag members = new ListTag();
            group.members.forEach((id, member) -> {
                CompoundTag record = new CompoundTag(); record.putUUID("Id", id);
                record.putString("Role", member.role.name()); record.putString("PreviousRole", member.previousRole.name());
                record.putInt("Rejections", member.rejections); record.putLong("NextObservation", member.nextObservation);
                record.putLong("RememberedUntil", member.rememberedUntil); record.putLong("AssignedUntil", member.assignedUntil);
                if (member.observer != null) record.putUUID("Observer", member.observer);
                if (member.assignedBy != null) record.putUUID("AssignedBy", member.assignedBy);
                members.add(record);
            });
            value.put("Members", members);
            list.add(value);
        }
        tag.putInt("Version", 3); tag.put("Groups", list);
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
                if (group.kind == Kind.FOREST) {
                    if (value.contains("HasLeaderOffice", Tag.TAG_BYTE)) {
                        group.hasLeaderOffice = group.leader != null || value.getBoolean("HasLeaderOffice");
                        group.vacancyConfirmed = group.hasLeaderOffice && group.leader == null && value.getBoolean("VacancyConfirmed");
                    } else if (group.leader == null) {
                        // Version 2 kept the former LEADER role after death. Recover evidence, not a presumed death.
                        Set<UUID> recorded = new HashSet<>();
                        for (Tag raw : value.getList("Members", Tag.TAG_COMPOUND)) {
                            var record = (CompoundTag)raw;
                            if (record.hasUUID("Id") && ("LEADER".equals(record.getString("Role"))
                                    || "LEADER".equals(record.getString("PreviousRole")))) recorded.add(record.getUUID("Id"));
                        }
                        group.hasLeaderOffice = !recorded.isEmpty();
                        if (recorded.size() == 1) group.leader = recorded.iterator().next();
                        // Conflicting old records retain an unresolved office; they do not authorize an election.
                    }
                }
                for (Tag raw : value.getList("Members", Tag.TAG_COMPOUND)) {
                    CompoundTag record = (CompoundTag)raw;
                    if (!record.hasUUID("Id")) continue;
                    UUID id = record.getUUID("Id");
                    Role base = id.equals(group.leader) ? Role.LEADER : Role.MEMBER;
                    var member = new Member(base);
                    try { member.role = Role.valueOf(record.getString("Role")); }
                    catch (IllegalArgumentException ignored) { /* Unknown roles remain ordinary members. */ }
                    // A record cannot grant an office absent from the group's original leader registration.
                    if (member.role == Role.FREE || member.role == Role.LEADER && base != Role.LEADER) member.role = base;
                    if (record.contains("PreviousRole")) member.previousRole = "LEADER".equals(record.getString("PreviousRole"))
                            && base == Role.LEADER ? Role.LEADER : Role.MEMBER;
                    member.rejections = Math.clamp(record.getInt("Rejections"), 0, 5);
                    member.nextObservation = Math.max(0, record.getLong("NextObservation"));
                    member.rememberedUntil = Math.max(0, record.getLong("RememberedUntil"));
                    member.assignedUntil = Math.max(0, record.getLong("AssignedUntil"));
                    member.observer = record.hasUUID("Observer") ? record.getUUID("Observer") : null;
                    member.assignedBy = record.hasUUID("AssignedBy") ? record.getUUID("AssignedBy") : null;
                    group.members.put(id, member);
                }
                if (group.vacancyConfirmed && value.hasUUID("Successor") && value.hasUUID("SuccessionWitness")) {
                    UUID candidate = value.getUUID("Successor"), witness = value.getUUID("SuccessionWitness");
                    if (!candidate.equals(witness) && group.members.containsKey(candidate) && group.members.containsKey(witness)) {
                        group.successor = candidate; group.successionWitness = witness;
                        group.successionTicks = Math.clamp(value.getInt("SuccessionTicks"), 0, LeaderSuccession.DELAY_TICKS - 1);
                    }
                }
                data.groups.put(group.id, group);
            } catch (IllegalArgumentException ignored) { /* Unknown future kinds do not invent communities. */ }
        }
        return data;
    }
}
