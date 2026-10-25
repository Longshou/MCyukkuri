package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.RelationshipCleanup;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.settlement.site.SitePlan;
import io.github.longshou.mcyukkuri.settlement.site.SitePlanner;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import io.github.longshou.mcyukkuri.settlement.site.SiteTier;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.Collection;

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
        /** Bank-side real forage pile serving an afloat community; null for land communities and planned granaries. */
        public BlockPos waterCommons;
        /** Standing cell of the waste pit a natural-cave community chose (GroupPits); planned sites use their own pit. */
        public BlockPos pit;
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
        private final NavigableSet<UUID> cleanupMembers = new TreeSet<>();
        /** One deadline per current member assignment, counted when several members share an issuer/deadline. */
        private final Map<UUID,TreeMap<Long,Integer>> assignmentsByIssuer = new HashMap<>();
        private long rosterRevision;
        private boolean rosterTrusted = true;

        /** Transient seasonal state, rebuilt after restart. */
        public boolean snowing;
        public long gatherUntil, gatherDay = -1;
        /** Rumours, predator sightings and the current move. */
        public int threat;
        public BlockPos threatPos;
        public long threatTime;
        public BlockPos migrateTo;
        /** Actual calm-water berth selected for an afloat migration; the dry {@link #migrateTo} remains the bank anchor. */
        public BlockPos migrateWater;
        public long migrateSince;
        /** The scout that walks ahead and is the arrival pivot of the current move; null when none is chosen. */
        public UUID migrateVanguard;
        /** Game time when the vanguard (or the leader, if no vanguard) first stood at the destination; 0 until then. */
        public long migrateArrivedAt;
        public final Map<UUID, Long> sighted = new HashMap<>();
        public long decayAt, migrateRetry, attackedAt;
        /** What the group believes about humans and pets (see Rumours); saved. */
        public final List<Rumours.Rumour> rumours = new ArrayList<>();
        /** Rules, rations, the hunt and the record of offences (package 12); saved. */
        public final Commons commons = new Commons();

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
            return member == null ? id.equals(leader) ? Role.LEADER : Role.FREE : member.role;
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
        static final long ABANDON_TICKS = 14L * 24000;
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
        /** Accumulated empty ticks from observed, fully loaded upkeep passes; never an absolute world timestamp. */
        public long emptySince;
        /** Transient pacing (not saved): next claim attempt and last upkeep pass. */
        public long retryAt, lastCheck;
        private SitePlan plan;
        /** Standing level of each part, as built (the layout itself never depends on the terrain, the levels do). Null for old saves. */
        public int[] levels;
        public Site(UUID id, SiteTier tier, long seed, int rotation, int palette, BlockPos origin, boolean auto) {
            this.id = id; this.tier = tier; this.seed = seed; this.rotation = rotation; this.palette = palette; this.origin = origin.immutable(); this.auto = auto;
        }
        public synchronized SitePlan plan() {
            if (plan == null) {
                int level = origin.getY();
                plan = SitePlanner.plan(tier, seed, rotation, origin.getX(), origin.getZ(), palette, (x, z) -> level, false);
                if (plan != null && levels != null && levels.length == plan.parts.size()) plan = plan.withLevels(levels);
            }
            return plan;
        }
        /** A vacant plot may be reserved for an active migration before it becomes founded. */
        public boolean reservable() { return state == SiteState.SEED || state == SiteState.ABANDONED; }
        public boolean vacant() { return group == null && reservable(); }
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
    // Stable structural snapshots let callers remove entries while iterating; contained objects remain live.
    private List<Group> cachedGroups;
    private List<Site> cachedSites;
    private final Set<Long> discovered = new HashSet<>();
    private final NavigableSet<UUID> cleanupGroups = new TreeSet<>();
    private final ArrayDeque<UUID> cleanupRoots = new ArrayDeque<>();
    private Consumer<UUID> retain = cleanupRoots::add;
    private UUID cleanupGroupCursor, cleanupMemberCursor, cleanupWaitingMember;
    private Group cleanupGroup;
    private long cleanupRevision;
    private boolean cleanupScanning, cleanupAllDead, cleanupDecided, cleanupCommonsTurn;
    private int cleanupMemberCount;
    private ArrayDeque<UUID> cleanupDead = new ArrayDeque<>();
    private RelationshipCleanup.Presence cleanupPresence;
    public void bindCleanup(Consumer<UUID> retention) { retain=retention; }
    private void retain(UUID id) { if(id!=null)retain.accept(id); }
    public boolean cleanupRootStep() {
        UUID id=cleanupRoots.poll();if(id==null)return false;retain.accept(id);return true;
    }
    private void startCleanup(Group group) {
        cleanupGroup=group;cleanupRevision=group.rosterRevision;cleanupMemberCursor=cleanupWaitingMember=null;
        cleanupPresence=null;cleanupScanning=cleanupAllDead=true;cleanupDecided=false;cleanupMemberCount=0;cleanupDead=new ArrayDeque<>();
    }
    /** One roster row, presence lookup, Commons row, or deletion; classification precedes member removal. */
    public void cleanupGroupStep(ServerLevel level,FamilyData family,YukkuriWorldData custody,List<ServerLevel> levels) {
        if(cleanupGroup!=null&&groups.get(cleanupGroup.id)!=cleanupGroup)cleanupGroup=null;
        if(cleanupGroup==null) {
            UUID id=cleanupGroupCursor==null?(cleanupGroups.isEmpty()?null:cleanupGroups.first()):cleanupGroups.higher(cleanupGroupCursor);
            cleanupGroupCursor=id;if(id==null)return;
            Group group=groups.get(id);if(group==null) { cleanupGroups.remove(id);return; }
            startCleanup(group);return;
        }
        Group group=cleanupGroup;
        cleanupCommonsTurn=!cleanupCommonsTurn;
        if(cleanupCommonsTurn) {
            if(group.commons.cleanupStep(family,this,group,level.getGameTime()))setDirty();return;
        }
        if(group.rosterRevision!=cleanupRevision) { startCleanup(group);return; }
        if(cleanupWaitingMember!=null) {
            if(!cleanupPresence.complete(levels)) { cleanupPresence.step(levels);return; }
            if(cleanupPresence.alive()||custody.capturedForCleanup(cleanupWaitingMember)||!family.deadForCleanup(cleanupWaitingMember))cleanupAllDead=false;
            else cleanupDead.add(cleanupWaitingMember);
            cleanupWaitingMember=null;cleanupPresence=null;return;
        }
        if(cleanupScanning) {
            UUID id=cleanupMemberCursor==null?(group.cleanupMembers.isEmpty()?null:group.cleanupMembers.first()):group.cleanupMembers.higher(cleanupMemberCursor);
            cleanupMemberCursor=id;
            if(id==null) { cleanupScanning=false;return; }
            cleanupMemberCount++;
            if(!family.deadForCleanup(id)||custody.capturedForCleanup(id)) { cleanupAllDead=false;return; }
            cleanupWaitingMember=id;cleanupPresence=new RelationshipCleanup.Presence(id);return;
        }
        if(!cleanupDecided) {
            cleanupDecided=true;
            if(group.kind==Kind.FAMILY&&group.rosterTrusted&&cleanupMemberCount>0&&cleanupAllDead
                    &&(group.leader==null||group.members.containsKey(group.leader))) {
                Site site=site(group.siteId);
                if(site!=null&&group.id.equals(site.group)) { site.group=null;site.state=SiteState.ABANDONED; }
                remove(group.id);cleanupGroup=null;cleanupDead=new ArrayDeque<>();return;
            }
        }
        UUID dead=cleanupDead.poll();
        if(dead==null) { cleanupGroup=null;return; }
        if(family.deadForCleanup(dead)&&!custody.capturedForCleanup(dead))leave(group,dead,level.getGameTime());
        cleanupRevision=group.rosterRevision;
    }

    public static SettlementData get(ServerLevel level) {
        var data=level.getDataStorage().computeIfAbsent(new Factory<>(SettlementData::new, SettlementData::load), "mcyukkuri_settlements");
        if(data.logDimension==null) {
            data.logDimension=level.dimension().location().toString();
            for(var group:data.groups.values())group.commons.logContext(data.logDimension,group.id);
        }
        return data;
    }
    private String logDimension;

    public Site site(UUID id) { return id == null ? null : sites.get(id); }
    public Collection<Site> sites() {
        if (cachedSites == null) cachedSites = List.copyOf(sites.values());
        return cachedSites;
    }
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
        site.levels = plan.levels();
        sites.put(site.id, site);cachedSites = null;setDirty();
        return site;
    }
    public void removeSite(UUID id) { if (sites.remove(id) != null) { cachedSites = null;setDirty(); } }
    /** True for columns of granary hollows, plazas, nurseries and waste pits: ordinary shelter hunting must never claim these. */
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
    public Collection<Group> groups() {
        if (cachedGroups == null) cachedGroups = List.copyOf(groups.values());
        return cachedGroups;
    }
    public void add(Group group) {
        if (groups.put(group.id,group) != group) cachedGroups = null;
        group.commons.logContext(logDimension,group.id);
        cleanupGroups.add(group.id);group.commons.retainWith(this::retain);retain(group.leader);setDirty();
    }
    public void remove(UUID id) { if (groups.remove(id) != null) { cachedGroups = null;cleanupGroups.remove(id);setDirty(); } }

    /** A successful household move changes membership without deleting the family ledger. */
    public void leave(Group group, UUID id) {
        leave(group, id, 1);
    }

    public void leave(Group group, UUID id, long now) {
        if (group == null || id == null) return;
        if (id.equals(group.leader)) vacate(group, now);
        Member removed=group.members.remove(id);
        if(removed!=null) { forgetAssignment(group,removed);group.cleanupMembers.remove(id);group.rosterRevision++; }
        if (id.equals(group.successor) || id.equals(group.successionWitness)) resetSuccession(group);
        setDirty();
    }

    /** Unlike member(), observation of a candidate cannot invent membership. */
    public Member existingMember(Group group, UUID id) { return group == null || id == null ? null : group.members.get(id); }

    private void forgetAssignment(Group group,Member member) {
        if(member==null||member.assignedBy==null)return;
        var deadlines=group.assignmentsByIssuer.get(member.assignedBy);
        if(deadlines==null)return;
        deadlines.computeIfPresent(member.assignedUntil,(key,count)->count==1?null:count-1);
        if(deadlines.isEmpty())group.assignmentsByIssuer.remove(member.assignedBy);
    }
    private void rememberAssignment(Group group,Member member) {
        if(member.assignedBy!=null) {
            group.assignmentsByIssuer.computeIfAbsent(member.assignedBy,id->new TreeMap<>()).merge(member.assignedUntil,1,Integer::sum);
            retain(member.assignedBy);
        }
    }
    /** Preserve the original field writes; callers retain their existing dirty/response ordering. */
    void assign(Group group,Member member,UUID issuer,long until) {
        forgetAssignment(group,member);member.assignedBy=issuer;member.assignedUntil=until;rememberAssignment(group,member);
    }
    boolean assignmentProtects(Group group,UUID id,long now) {
        var member=group.members.get(id);
        if(member!=null&&(member.assignedUntil>now||member.role==Role.UNUN_SLAVE))return true;
        var deadlines=group.assignmentsByIssuer.get(id);return deadlines!=null&&deadlines.lastKey()>now;
    }

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
            group.cleanupMembers.add(id);group.rosterRevision++;retain(id);
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
            Member removed=group.members.remove(id);
            if(removed!=null) { forgetAssignment(group,removed);group.cleanupMembers.remove(id);group.rosterRevision++; }
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
        assign(group,member,null,0);
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

    /** Danger seen near the community; migration consumes the accumulated evidence. */
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
            value.putBoolean("RosterTrusted",group.rosterTrusted);
            value.putLong("Center", group.center.asLong());
            if (group.siteId != null) value.putUUID("Site", group.siteId);
            if (group.waterCommons != null) value.putLong("WaterCommons", group.waterCommons.asLong());
            if (group.pit != null) value.putLong("Pit", group.pit.asLong());
            if (group.migrateWater != null) value.putLong("MigrateWater", group.migrateWater.asLong());
            if (group.leader != null) value.putUUID("Leader", group.leader);
            value.putBoolean("HasLeaderOffice", group.hasLeaderOffice);
            value.putBoolean("VacancyConfirmed", group.vacancyConfirmed);
            value.putLong("VacantSince", group.vacantSince);
            value.putInt("SuccessionTicks", group.successionTicks);
            if (group.successor != null) value.putUUID("Successor", group.successor);
            if (group.successionWitness != null) value.putUUID("SuccessionWitness", group.successionWitness);
            value.putInt("Threat", group.threat);
            value.put("Rumours", Rumours.save(group));
            value.put("Commons", group.commons.save());
            value.putLong("ThreatTime", group.threatTime);
            if (group.threatPos != null) value.putLong("ThreatPos", group.threatPos.asLong());
            if (group.migrateTo != null) {
                value.putLong("MigrateTo", group.migrateTo.asLong());
                value.putLong("MigrateSince", group.migrateSince);
                if (group.migrateVanguard != null) value.putUUID("MigrateVanguard", group.migrateVanguard);
                value.putLong("MigrateArrivedAt", group.migrateArrivedAt);
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
            if (site.levels != null) value.putIntArray("Levels", site.levels);
            value.putBoolean("Auto", site.auto);
            value.putString("State", site.state.name());
            value.putInt("Prosperity", site.prosperity);
            value.putInt("Failures", site.failures);
            value.putLong("EmptySince", Math.clamp(site.emptySince, 0L, Site.ABANDON_TICKS));
            if (site.group != null) value.putUUID("Group", site.group);
            siteList.add(value);
        }
        tag.putInt("Version", 6);
        tag.put("Sites", siteList);
        tag.put("Groups", list);
        tag.putLongArray("Discovered", discovered.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    public static SettlementData load(CompoundTag tag, HolderLookup.Provider registries) {
        SettlementData data = new SettlementData();
        Map<UUID,UUID> siteOwners=new HashMap<>();Set<UUID> ambiguousSites=new HashSet<>();
        for (long region : tag.getLongArray("Discovered")) data.discovered.add(region);
        for (Tag entry : tag.getList("Sites", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)entry;
            if (!value.hasUUID("Id") || !value.contains("Origin", Tag.TAG_LONG)) continue;
            try {
                Site site = new Site(value.getUUID("Id"), SiteTier.valueOf(value.getString("Tier")), value.getLong("Seed"), value.getInt("Rotation"),
                        value.getInt("Palette"), BlockPos.of(value.getLong("Origin")), value.getBoolean("Auto"));
                if (value.contains("Levels", Tag.TAG_INT_ARRAY)) site.levels = value.getIntArray("Levels");
                site.state = SiteState.valueOf(value.getString("State"));
                site.prosperity = Math.clamp(value.getInt("Prosperity"), 0, 3);
                site.failures = Math.max(0, value.getInt("Failures"));
                // Older saves have no counter: begin at zero, without offline catch-up.
                site.emptySince = Math.clamp(value.getLong("EmptySince"), 0L, Site.ABANDON_TICKS);
                site.group = value.hasUUID("Group") ? value.getUUID("Group") : null;
                data.sites.put(site.id, site);
                // A group link on an otherwise empty site is an in-progress migration reservation, not a second home.
                if(site.group!=null && !site.reservable()) {
                    UUID previous=siteOwners.put(site.group,site.id);
                    if(previous!=null&&!previous.equals(site.id))ambiguousSites.add(site.group);
                }
            } catch (IllegalArgumentException ignored) { /* Unknown tiers or states from a future version are skipped. */ }
        }
        for (Tag entry : tag.getList("Groups", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag)entry;
            if (!value.hasUUID("Id") || !value.contains("Center", Tag.TAG_LONG)) continue;
            try {
                Group group = new Group(value.getUUID("Id"), Kind.valueOf(value.getString("Kind")), BlockPos.of(value.getLong("Center")),
                        value.hasUUID("Leader") ? value.getUUID("Leader") : null);
                group.commons.retainWith(data::retain);data.retain(group.leader);
                if (value.hasUUID("Site")) group.siteId = value.getUUID("Site");
                if (value.contains("WaterCommons", Tag.TAG_LONG)) group.waterCommons = BlockPos.of(value.getLong("WaterCommons"));
                if (value.contains("Pit", Tag.TAG_LONG)) group.pit = BlockPos.of(value.getLong("Pit"));
                if (value.contains("MigrateWater", Tag.TAG_LONG)) group.migrateWater = BlockPos.of(value.getLong("MigrateWater"));
                loadOffice(value, group);
                data.loadMembers(value, group);
                // Keep malformed communities rather than delete the group and strand a noncanonical site backlink.
                if(ambiguousSites.contains(group.id)||siteOwners.containsKey(group.id)&&!siteOwners.get(group.id).equals(group.siteId))group.rosterTrusted=false;
                if (group.vacancyConfirmed && value.hasUUID("Successor") && value.hasUUID("SuccessionWitness")) {
                    UUID candidate = value.getUUID("Successor"), witness = value.getUUID("SuccessionWitness");
                    if (!candidate.equals(witness) && group.members.containsKey(candidate) && group.members.containsKey(witness)) {
                        group.successor = candidate;
                        group.successionWitness = witness;
                        group.successionTicks = Math.clamp(value.getInt("SuccessionTicks"), 0, LeaderSuccession.DELAY_TICKS - 1);
                    }
                }
                data.retain(group.successor);data.retain(group.successionWitness);
                group.threat = Math.max(0, value.getInt("Threat"));
                Rumours.load(value.getList("Rumours", Tag.TAG_COMPOUND), group);
                group.commons.load(value.getCompound("Commons"));
                group.threatTime = value.getLong("ThreatTime");
                if (value.contains("ThreatPos", Tag.TAG_LONG)) group.threatPos = BlockPos.of(value.getLong("ThreatPos"));
                if (value.contains("MigrateTo", Tag.TAG_LONG)) {
                    group.migrateTo = BlockPos.of(value.getLong("MigrateTo"));
                    group.migrateSince = value.getLong("MigrateSince");
                    if (value.hasUUID("MigrateVanguard")) group.migrateVanguard = value.getUUID("MigrateVanguard");
                    group.migrateArrivedAt = value.getLong("MigrateArrivedAt");
                }
                data.groups.put(group.id, group);data.cleanupGroups.add(group.id);
            } catch (IllegalArgumentException ignored) { /* Unknown future kinds do not invent communities. */ }
        }
        data.cachedGroups = null;data.cachedSites = null;
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

    private void loadMembers(CompoundTag value, Group group) {
        group.rosterTrusted=value.contains("Members",Tag.TAG_LIST)
                &&(!value.contains("RosterTrusted")||value.contains("RosterTrusted",Tag.TAG_BYTE)&&value.getBoolean("RosterTrusted"));
        if(!(value.get("Members") instanceof ListTag members))return;
        for (Tag raw : members) {
            if(!(raw instanceof CompoundTag record)||!record.hasUUID("Id")) { group.rosterTrusted=false;continue; }
            UUID id = record.getUUID("Id");
            if(group.members.containsKey(id))group.rosterTrusted=false;
            Role base = id.equals(group.leader) ? Role.LEADER : Role.MEMBER;
            var member = new Member(base);
            try { member.role = Role.valueOf(record.getString("Role")); }
            catch (IllegalArgumentException ignored) { group.rosterTrusted=false; /* Preserve the old role fallback, but not a claim of complete knowledge. */ }
            if (member.role == Role.FREE || member.role == Role.LEADER && base != Role.LEADER) { member.role = base;group.rosterTrusted=false; }
            if (record.contains("PreviousRole")) member.previousRole = "LEADER".equals(record.getString("PreviousRole")) && base == Role.LEADER
                    ? Role.LEADER : Role.MEMBER;
            member.rejections = Math.clamp(record.getInt("Rejections"), 0, 5);
            member.nextObservation = Math.max(0, record.getLong("NextObservation"));
            member.rememberedUntil = Math.max(0, record.getLong("RememberedUntil"));
            member.assignedUntil = Math.max(0, record.getLong("AssignedUntil"));
            member.observer = record.hasUUID("Observer") ? record.getUUID("Observer") : null;
            member.assignedBy = record.hasUUID("AssignedBy") ? record.getUUID("AssignedBy") : null;
            forgetAssignment(group,group.members.put(id,member));rememberAssignment(group,member);
            group.cleanupMembers.add(id);
            retain(id);retain(member.observer);retain(member.assignedBy);
        }
    }
}
