package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.settlement.site.SitePlan;
import io.github.longshou.mcyukkuri.settlement.site.SitePlanner;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import io.github.longshou.mcyukkuri.settlement.site.SiteTier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    public enum Kind { FAMILY, FOREST, BANDITS }
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
        /** Changes when a migration completes. */
        public BlockPos center;
        /** The planned site this community lives on (plaza, granaries, pool...); null for groups on natural caves. */
        public UUID siteId;
        public UUID leader;
        /** A forest group only elects after a real incumbent's death/removal. */
        public boolean hasLeaderOffice, vacancyConfirmed;
        /** Mirrors {@link #hasLeaderOffice} for the command report. */
        public boolean led;
        public long vacantSince;
        public UUID successor, successionWitness;
        public int successionTicks;
        // Runtime sampling anchor is deliberately not persisted: no offline catch-up.
        long lastSuccessionSample = Long.MIN_VALUE;
        private final Map<UUID, Member> members = new HashMap<>();

        /** Transient seasonal state, rebuilt after restart. */
        public boolean snowing;
        public long gatherUntil, gatherDay = -1;
        /** Rumours, predator sightings and the current move. */
        public int threat;
        public BlockPos threatPos;
        public long threatTime;
        public BlockPos migrateTo;
        public long migrateSince;
        public final Map<UUID, Long> sighted = new HashMap<>();
        public long decayAt, migrateRetry, attackedAt;
        /** What the group believes about humans and pets (see Rumours); saved. */
        public final List<Rumours.Rumour> rumours = new ArrayList<>();

        public Group(UUID id, Kind kind, BlockPos center, UUID leader) {
            this.id = id;
            this.kind = kind;
            this.center = center.immutable();
            this.leader = leader;
            hasLeaderOffice = kind == Kind.FOREST && leader != null;
            led = hasLeaderOffice;
        }

        public Role role(UUID id) {
            if (id == null) return Role.FREE;
            var member = members.get(id);
            return member == null ? id.equals(leader) ? Role.LEADER : Role.MEMBER : member.role;
        }

        /** A confirmed office vacancy; never an invitation to elect from a missing record. */
        public boolean vacant() { return vacancyConfirmed && leader == null; }
    }

    public enum SiteState { SEED, FOUNDED, THRIVING, STRAINED, DECLINING, ABANDONED }

    /**
     * A planned settlement ground (see settlement/site). Only the inputs of the plan are saved: the layout never depends on
     * the terrain, so {@link #plan()} re-derives slots, paths and reserved boxes exactly. Time counters use loaded ticks only.
     */
    public static final class Site {
        public final UUID id;
        public final SiteTier tier;
        public final long seed;
        public final int rotation, palette;
        /** The plaza centre column at the site level. */
        public final BlockPos origin;
        /** Auto sites are populated when a player comes near; manual sites (commands, exhibits) are populated by their owner. */
        public final boolean auto;
        public SiteState state = SiteState.SEED;
        public int prosperity, failures;
        public UUID group;
        /** Transient pacing (not saved): next claim attempt, when the site was last seen empty, last upkeep pass. */
        public long retryAt, emptySince, lastCheck;
        private SitePlan plan;
        public Site(UUID id, SiteTier tier, long seed, int rotation, int palette, BlockPos origin, boolean auto) {
            this.id = id; this.tier = tier; this.seed = seed; this.rotation = rotation; this.palette = palette; this.origin = origin.immutable(); this.auto = auto;
        }
        public synchronized SitePlan plan() {
            if (plan == null) {
                int level = origin.getY();
                plan = SitePlanner.plan(tier, seed, rotation, origin.getX(), origin.getZ(), palette, (x, z) -> level, false);
            }
            return plan;
        }
        public boolean vacant() { return state == SiteState.SEED || state == SiteState.ABANDONED; }
        public List<SiteSlot> slots(SiteSlot.Kind kind) {
            var out = new ArrayList<SiteSlot>();
            var p = plan();
            if (p != null) for (var slot : p.slots()) if (slot.kind() == kind) out.add(slot);
            return out;
        }
        public SiteSlot first(SiteSlot.Kind kind) { var all = slots(kind); return all.isEmpty() ? null : all.getFirst(); }
    }

    private final Map<UUID, Group> groups = new HashMap<>();
    private final Map<UUID, Site> sites = new HashMap<>();
    private final Set<Long> discovered = new HashSet<>();

    public static SettlementData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(SettlementData::new, SettlementData::load), "mcyukkuri_settlements");
    }

    public Site site(UUID id) { return id == null ? null : sites.get(id); }
    public java.util.Collection<Site> sites() { return java.util.List.copyOf(sites.values()); }
    public Site siteOf(Group group) { return group == null ? null : site(group.siteId); }
    /** The site registered for this plaza column, if any. */
    public Site siteAt(int x, int z) {
        for (Site site : sites.values()) if (site.origin.getX() == x && site.origin.getZ() == z) return site;
        return null;
    }
    public Site addSite(SitePlan plan, boolean auto) {
        Site known = siteAt(plan.ox, plan.oz);
        if (known != null) return known;
        Site site = new Site(UUID.randomUUID(), plan.tier, plan.seed, plan.rotation, plan.palette, new BlockPos(plan.ox, plan.level, plan.oz), auto);
        sites.put(site.id, site); setDirty();
        return site;
    }
    public void removeSite(UUID id) { if (sites.remove(id) != null) setDirty(); }
    /** True for columns of granary hollows, plazas and nurseries: ordinary shelter hunting must never claim these. */
    public boolean reserved(BlockPos pos) {
        for (Site site : sites.values()) {
            int bound = site.tier.bound;
            if (Math.abs(pos.getX() - site.origin.getX()) > bound || Math.abs(pos.getZ() - site.origin.getZ()) > bound
                    || Math.abs(pos.getY() - site.origin.getY()) > 8) continue;
            var plan = site.plan();
            if (plan != null) for (int[] box : plan.reserved())
                if (pos.getX() >= box[0] && pos.getX() <= box[2] && pos.getZ() >= box[1] && pos.getZ() <= box[3]) return true;
        }
        return false;
    }

    public Group group(UUID id) { return id == null ? null : groups.get(id); }
    public java.util.Collection<Group> groups() { return java.util.List.copyOf(groups.values()); }
    public void add(Group group) { groups.put(group.id, group); setDirty(); }
    public void remove(UUID id) { if (groups.remove(id) != null) setDirty(); }

    /** A successful household move changes membership without deleting the family ledger. */
    public void leave(Group group, UUID id) {
        leave(group, id, 1);
    }

    public void leave(Group group, UUID id, long now) {
        if (group == null || id == null) return;
        if (id.equals(group.leader)) vacate(group, now);
        group.members.remove(id);
        if (id.equals(group.successor) || id.equals(group.successionWitness)) resetSuccession(group);
        setDirty();
    }

    /** Unlike member(), observation of a candidate cannot invent membership. */
    public Member existingMember(Group group, UUID id) { return group == null || id == null ? null : group.members.get(id); }

    private void vacate(Group group, long now) {
        var old = group.members.get(group.leader);
        if (old != null) {
            if (old.role == Role.LEADER) old.role = Role.MEMBER;
            if (old.previousRole == Role.LEADER) old.previousRole = Role.MEMBER;
        }
        group.leader = null;
        group.vacancyConfirmed = group.kind == Kind.FOREST && group.hasLeaderOffice;
        group.vacantSince = group.vacancyConfirmed ? Math.max(1, now) : 0;
        resetSuccession(group);
        setDirty();
    }

    public void resetSuccession(Group group) {
        if (group != null && (group.successor != null || group.successionWitness != null || group.successionTicks != 0)) {
            group.successor = group.successionWitness = null;
            group.successionTicks = 0;
            setDirty();
        }
    }

    /** Called after real local witnesses have completed the succession interval. */
    public boolean appoint(Group group, UUID id) {
        var next = existingMember(group, id);
        if (group == null || group.kind != Kind.FOREST || !group.hasLeaderOffice || !group.vacancyConfirmed || group.leader != null
                || next == null || next.role != Role.MEMBER || next.rejections != 0) return false;
        for (var member : group.members.values()) {
            if (member.role == Role.LEADER) member.role = Role.MEMBER;
            if (member.previousRole == Role.LEADER) member.previousRole = Role.MEMBER;
        }
        group.leader = id;
        group.vacancyConfirmed = false;
        group.vacantSince = 0;
        next.role = next.previousRole = Role.LEADER;
        resetSuccession(group);
        setDirty();
        return true;
    }

    public Member member(Group group, UUID id) {
        if (group == null || id == null) return null;
        var member = group.members.get(id);
        if (member == null) {
            member = new Member(id.equals(group.leader) ? Role.LEADER : Role.MEMBER);
            group.members.put(id, member);
            setDirty();
        }
        return member;
    }

    public Role role(Group group, UUID id) { return group == null ? Role.FREE : group.role(id); }
    public UUID activeLeader(Group group) {
        return group != null && group.leader != null && role(group, group.leader) == Role.LEADER ? group.leader : null;
    }

    public void setRole(Group group, UUID id, Role role) {
        if (group == null || id == null || role == null) return;
        var member = member(group, id);
        if (role == Role.FREE) {
            group.members.remove(id);
        } else if (role != Role.LEADER || id.equals(group.leader)) {
            member.role = role;
            if (role == Role.LEADER) member.previousRole = Role.LEADER;
        }
        setDirty();
    }

    public void restore(Group group, UUID id, long now) {
        var member = member(group, id);
        if (member == null || member.rejections == 0 && member.role != Role.SHUNNED && member.role != Role.OUTCAST && member.role != Role.UNUN_SLAVE) return;
        member.role = member.previousRole == Role.LEADER && id.equals(group.leader) ? Role.LEADER : Role.MEMBER;
        member.previousRole = member.role;
        member.rejections = 0;
        member.nextObservation = now + 200;
        member.rememberedUntil = now + 2400;
        member.assignedBy = null;
        member.assignedUntil = 0;
        setDirty();
    }

    /** One natural community attempt per 64 x 64 area, persisted even after population loss. */
    public boolean discover(BlockPos pos) {
        boolean fresh = discovered.add(ChunkPos.asLong(Math.floorDiv(pos.getX(), 64), Math.floorDiv(pos.getZ(), 64)));
        if (fresh) setDirty();
        return fresh;
    }

    /** Only a ledger-confirmed death/removal opens a witnessed succession. */
    public void refreshLeader(ServerLevel level, Group group) {
        if (group == null || group.leader == null) return;
        var person = FamilyData.get(level).person(group.leader);
        if (person != null && (person.status == FamilyData.Status.DEAD || person.status == FamilyData.Status.REMOVED)) {
            vacate(group, level.getGameTime());
        }
    }

    /** A scout saw danger near the community; migration consumes the accumulated evidence. */
    public void reportThreat(Group group, BlockPos pos, long gameTime) { reportThreat(group, pos, gameTime, 1); }
    public void reportThreat(Group group, BlockPos pos, long gameTime, int weight) {
        if (group == null || pos == null || weight <= 0) return;
        group.threat = Math.min(20, group.threat + weight);
        group.decayAt = gameTime;
        group.threatPos = pos.immutable();
        group.threatTime = gameTime;
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Group group : groups.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", group.id);
            value.putString("Kind", group.kind.name());
            value.putLong("Center", group.center.asLong());
            if (group.siteId != null) value.putUUID("Site", group.siteId);
            if (group.leader != null) value.putUUID("Leader", group.leader);
            value.putBoolean("HasLeaderOffice", group.hasLeaderOffice);
            value.putBoolean("VacancyConfirmed", group.vacancyConfirmed);
            value.putLong("VacantSince", group.vacantSince);
            value.putInt("SuccessionTicks", group.successionTicks);
            if (group.successor != null) value.putUUID("Successor", group.successor);
            if (group.successionWitness != null) value.putUUID("SuccessionWitness", group.successionWitness);
            value.putInt("Threat", group.threat);
            value.put("Rumours", Rumours.save(group));
            value.putLong("ThreatTime", group.threatTime);
            if (group.threatPos != null) value.putLong("ThreatPos", group.threatPos.asLong());
            if (group.migrateTo != null) {
                value.putLong("MigrateTo", group.migrateTo.asLong());
                value.putLong("MigrateSince", group.migrateSince);
            }
            ListTag members = new ListTag();
            group.members.forEach((id, member) -> {
                CompoundTag record = new CompoundTag();
                record.putUUID("Id", id);
                record.putString("Role", member.role.name());
                record.putString("PreviousRole", member.previousRole.name());
                record.putInt("Rejections", member.rejections);
                record.putLong("NextObservation", member.nextObservation);
                record.putLong("RememberedUntil", member.rememberedUntil);
                record.putLong("AssignedUntil", member.assignedUntil);
                if (member.observer != null) record.putUUID("Observer", member.observer);
                if (member.assignedBy != null) record.putUUID("AssignedBy", member.assignedBy);
                members.add(record);
            });
            value.put("Members", members);
            list.add(value);
        }
        ListTag siteList = new ListTag();
        for (Site site : sites.values()) {
            CompoundTag value = new CompoundTag();
            value.putUUID("Id", site.id);
            value.putString("Tier", site.tier.name());
            value.putLong("Seed", site.seed);
            value.putInt("Rotation", site.rotation);
            value.putInt("Palette", site.palette);
            value.putLong("Origin", site.origin.asLong());
            value.putBoolean("Auto", site.auto);
            value.putString("State", site.state.name());
            value.putInt("Prosperity", site.prosperity);
            value.putInt("Failures", site.failures);
            if (site.group != null) value.putUUID("Group", site.group);
            siteList.add(value);
        }
        tag.putInt("Version", 5);
        tag.put("Sites", siteList);
        tag.put("Groups", list);
        tag.putLongArray("Discovered", discovered.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public static SettlementData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementData data = new SettlementData();
        for (long region : tag.getLongArray("Discovered")) data.discovered.add(region);
        for (Tag entry : tag.getList("Sites", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)entry;
            if (!value.hasUUID("Id") || !value.contains("Origin", Tag.TAG_LONG)) continue;
            try {
                Site site = new Site(value.getUUID("Id"), SiteTier.valueOf(value.getString("Tier")), value.getLong("Seed"), value.getInt("Rotation"),
                        value.getInt("Palette"), BlockPos.of(value.getLong("Origin")), value.getBoolean("Auto"));
                site.state = SiteState.valueOf(value.getString("State"));
                site.prosperity = Math.clamp(value.getInt("Prosperity"), 0, 3);
                site.failures = Math.max(0, value.getInt("Failures"));
                site.group = value.hasUUID("Group") ? value.getUUID("Group") : null;
                data.sites.put(site.id, site);
            } catch (IllegalArgumentException ignored) { /* Unknown tiers or states from a future version are skipped. */ }
        }
        for (Tag entry : tag.getList("Groups", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)entry;
            if (!value.hasUUID("Id") || !value.contains("Center", Tag.TAG_LONG)) continue;
            try {
                Group group = new Group(value.getUUID("Id"), Kind.valueOf(value.getString("Kind")), BlockPos.of(value.getLong("Center")),
                        value.hasUUID("Leader") ? value.getUUID("Leader") : null);
                if (value.hasUUID("Site")) group.siteId = value.getUUID("Site");
                loadOffice(value, group);
                loadMembers(value, group);
                if (group.vacancyConfirmed && value.hasUUID("Successor") && value.hasUUID("SuccessionWitness")) {
                    UUID candidate = value.getUUID("Successor"), witness = value.getUUID("SuccessionWitness");
                    if (!candidate.equals(witness) && group.members.containsKey(candidate) && group.members.containsKey(witness)) {
                        group.successor = candidate;
                        group.successionWitness = witness;
                        group.successionTicks = Math.clamp(value.getInt("SuccessionTicks"), 0, LeaderSuccession.DELAY_TICKS - 1);
                    }
                }
                group.threat = Math.max(0, value.getInt("Threat"));
                Rumours.load(value.getList("Rumours", Tag.TAG_COMPOUND), group);
                group.threatTime = value.getLong("ThreatTime");
                if (value.contains("ThreatPos", Tag.TAG_LONG)) group.threatPos = BlockPos.of(value.getLong("ThreatPos"));
                if (value.contains("MigrateTo", Tag.TAG_LONG)) {
                    group.migrateTo = BlockPos.of(value.getLong("MigrateTo"));
                    group.migrateSince = value.getLong("MigrateSince");
                }
                data.groups.put(group.id, group);
            } catch (IllegalArgumentException ignored) { /* Unknown future kinds do not invent communities. */ }
        }
        return data;
    }

    private static void loadOffice(CompoundTag value, Group group) {
        if (group.kind != Kind.FOREST) return;
        if (value.contains("HasLeaderOffice", Tag.TAG_BYTE)) {
            group.hasLeaderOffice = group.leader != null || value.getBoolean("HasLeaderOffice");
            group.vacancyConfirmed = group.hasLeaderOffice && group.leader == null && value.getBoolean("VacancyConfirmed");
        } else if (group.leader != null) {
            group.hasLeaderOffice = true;
        }
        group.led = group.hasLeaderOffice;
        group.vacantSince = group.vacancyConfirmed ? Math.max(1, value.getLong("VacantSince")) : 0;
    }

    private static void loadMembers(CompoundTag value, Group group) {
        for (Tag raw : value.getList("Members", Tag.TAG_COMPOUND)) {
            CompoundTag record = (CompoundTag)raw;
            if (!record.hasUUID("Id")) continue;
            UUID id = record.getUUID("Id");
            Role base = id.equals(group.leader) ? Role.LEADER : Role.MEMBER;
            var member = new Member(base);
            try { member.role = Role.valueOf(record.getString("Role")); }
            catch (IllegalArgumentException ignored) { /* Unknown roles remain ordinary members. */ }
            if (member.role == Role.FREE || member.role == Role.LEADER && base != Role.LEADER) member.role = base;
            if (record.contains("PreviousRole")) member.previousRole = "LEADER".equals(record.getString("PreviousRole")) && base == Role.LEADER
                    ? Role.LEADER : Role.MEMBER;
            member.rejections = Math.clamp(record.getInt("Rejections"), 0, 5);
            member.nextObservation = Math.max(0, record.getLong("NextObservation"));
            member.rememberedUntil = Math.max(0, record.getLong("RememberedUntil"));
            member.assignedUntil = Math.max(0, record.getLong("AssignedUntil"));
            member.observer = record.hasUUID("Observer") ? record.getUUID("Observer") : null;
            member.assignedBy = record.hasUUID("AssignedBy") ? record.getUUID("AssignedBy") : null;
            group.members.put(id, member);
        }
    }
}
