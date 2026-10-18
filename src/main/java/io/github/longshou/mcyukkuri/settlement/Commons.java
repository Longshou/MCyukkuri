package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * What a led community keeps in common (package 12): its rules, the daily ration ledger, the hunt in progress and the
 * record of hunts, offences and judgements. One instance per {@link SettlementData.Group}; saved with it.
 *
 * <p>Lecture basis (komicolle 40720 No.11518859): "the group elects a leader, hunts together, and shares out food by the
 * number of yukkuri; to avoid unfairness and self-destruction, group rules forbid eating one's own kind and limit how
 * many are born". No.11518794: without proper rules yukkuri breed self-destructively, use up the stores and break rules.
 * The concrete numbers below are project design.
 */
public final class Commons {
    /** Rule bits (族规). A community only has rules while it keeps a leader office ({@link CommunityRules#ruled}). */
    public static final int NO_THEFT = 1, NO_KIN_KILL = 2, NO_CANNIBAL = 4, OBEY_CALL = 8, NO_HATLESS = 16, BIRTH_LIMIT = 32, SHARE_BY_MOUTH = 64;
    public static final int DEFAULT_RULES = NO_THEFT | NO_KIN_KILL | NO_CANNIBAL | OBEY_CALL | NO_HATLESS | BIRTH_LIMIT | SHARE_BY_MOUTH;
    public static final String[] RULE_NAMES = {"no_theft", "no_kin_kill", "no_cannibal", "obey_call", "no_hatless", "birth_limit", "share_by_mouth"};
    private static final int LOG_SIZE = 32, HUNT_LOG_SIZE = 10;

    public int rules = DEFAULT_RULES;
    /** Young raised at once per household before the birth rule stops further courtship [original 3]. */
    public int birthLimit = 3;
    /** 0 = enough, 1 = short (rations scaled by priority), 2 = famine (rules change, see {@link Rations}). */
    public int famine;
    public long famineSince;
    /** Forced famine level for tests (-1 = judged from the stores). */
    public int famineOverride = -1;

    // ---- rations -------------------------------------------------------------------------------------------------
    /** Day number ({@code dayTime / 24000}) of the last distribution, -1 before the first. */
    public long rationDay = -1;
    public long rationTime;
    public int rationStock, rationGiven, rationWanted;
    public final Map<Long, Ration> rations = new HashMap<>();

    /** One household's share of the last distribution. Key: the household's shelter position. */
    public static final class Ration {
        public int mouths, allotted, left, own;
        public float weight;
        /** Who is fetching it now (transient claim, saved for simplicity). */
        public UUID fetcher;
        public long fetchUntil;
        public String note = "";
    }

    // ---- hunt ----------------------------------------------------------------------------------------------------
    public enum HuntPhase { IDLE, MUSTER, OUT, RETURN }
    /** The hunt in progress. Transient (not saved): a restart cancels it, the members' trips end on their own. */
    public static final class Hunt {
        public HuntPhase phase = HuntPhase.IDLE;
        public UUID chief;
        public BlockPos muster, dest;
        public long calledAt, departAt, deadline;
        public final Set<UUID> party = new LinkedHashSet<>(), defied = new LinkedHashSet<>(), done = new HashSet<>();
        public int delivered, radius;
        public long day = -1;
        public int today;
        public String outcome = "";
    }
    public final Hunt hunt = new Hunt();
    /** Finished hunts, newest last: "day d party n delivered k outcome". */
    public final Deque<CompoundTag> hunts = new ArrayDeque<>();

    // ---- standing of individuals (members and strangers) --------------------------------------------------------
    public static final class Standing {
        public int offences;
        public long lastOffence, rationCutUntil, banishedUntil, lastIntrusion, illicitCourtship, lastRebuke, lastBlocked;
        public int intrusions;
    }
    public final Map<UUID, Standing> standing = new HashMap<>();
    public Standing standing(UUID id) { return standing.computeIfAbsent(id, k -> new Standing()); }
    public Standing existing(UUID id) { return id == null ? null : standing.get(id); }

    /** Offences waiting for the leader's judgement. */
    public static final class Pending {
        public UUID offender, witness;
        public String offence, detail;
        public long time;
    }
    public final List<Pending> pending = new ArrayList<>();
    /** Recent events (offences, judgements, rations, famine), newest last. */
    public final Deque<CompoundTag> log = new ArrayDeque<>();

    public boolean has(int rule) { return (rules & rule) != 0; }
    public String rulesText() {
        var text = new StringBuilder();
        for (int i = 0; i < RULE_NAMES.length; i++) if ((rules & (1 << i)) != 0) text.append(text.length() == 0 ? "" : ",").append(RULE_NAMES[i]);
        return text.length() == 0 ? "none" : text.toString();
    }

    public void note(long time, String kind, UUID who, String detail) {
        var entry = new CompoundTag();
        entry.putLong("T", time);
        entry.putString("K", kind);
        if (who != null) entry.putUUID("W", who);
        entry.putString("D", detail == null ? "" : detail);
        log.addLast(entry);
        while (log.size() > LOG_SIZE) log.removeFirst();
    }
    public void huntRecord(CompoundTag record) {
        hunts.addLast(record);
        while (hunts.size() > HUNT_LOG_SIZE) hunts.removeFirst();
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("Rules", rules);
        tag.putInt("BirthLimit", birthLimit);
        tag.putInt("Famine", famine);
        tag.putLong("FamineSince", famineSince);
        tag.putInt("FamineOverride", famineOverride);
        tag.putLong("RationDay", rationDay);
        tag.putLong("RationTime", rationTime);
        tag.putInt("RationStock", rationStock);
        tag.putInt("RationGiven", rationGiven);
        tag.putInt("RationWanted", rationWanted);
        var rationList = new ListTag();
        rations.forEach((key, r) -> {
            var e = new CompoundTag();
            e.putLong("Home", key); e.putInt("Mouths", r.mouths); e.putFloat("Weight", r.weight); e.putInt("Allotted", r.allotted);
            e.putInt("Left", r.left); e.putInt("Own", r.own); e.putString("Note", r.note);
            rationList.add(e);
        });
        tag.put("Rations", rationList);
        var standingList = new ListTag();
        standing.forEach((id, s) -> {
            var e = new CompoundTag();
            e.putUUID("Id", id); e.putInt("Offences", s.offences); e.putLong("LastOffence", s.lastOffence);
            e.putLong("RationCutUntil", s.rationCutUntil); e.putLong("BanishedUntil", s.banishedUntil); e.putLong("LastIntrusion", s.lastIntrusion);
            e.putInt("Intrusions", s.intrusions); e.putLong("IllicitCourtship", s.illicitCourtship);
            standingList.add(e);
        });
        tag.put("Standing", standingList);
        var pendingList = new ListTag();
        for (var p : pending) {
            var e = new CompoundTag();
            e.putUUID("Offender", p.offender); if (p.witness != null) e.putUUID("Witness", p.witness);
            e.putString("Offence", p.offence); e.putString("Detail", p.detail == null ? "" : p.detail); e.putLong("Time", p.time);
            pendingList.add(e);
        }
        tag.put("Pending", pendingList);
        var logList = new ListTag(); logList.addAll(log); tag.put("Log", logList);
        var huntList = new ListTag(); huntList.addAll(hunts); tag.put("Hunts", huntList);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) return;
        rules = tag.contains("Rules", Tag.TAG_INT) ? tag.getInt("Rules") & DEFAULT_RULES : DEFAULT_RULES;
        birthLimit = tag.contains("BirthLimit", Tag.TAG_INT) ? Math.clamp(tag.getInt("BirthLimit"), 1, 8) : 3;
        famine = Math.clamp(tag.getInt("Famine"), 0, 2);
        famineSince = tag.getLong("FamineSince");
        famineOverride = tag.contains("FamineOverride", Tag.TAG_INT) ? Math.clamp(tag.getInt("FamineOverride"), -1, 2) : -1;
        rationDay = tag.contains("RationDay", Tag.TAG_LONG) ? tag.getLong("RationDay") : -1;
        rationTime = tag.getLong("RationTime");
        rationStock = tag.getInt("RationStock"); rationGiven = tag.getInt("RationGiven"); rationWanted = tag.getInt("RationWanted");
        rations.clear();
        for (Tag raw : tag.getList("Rations", Tag.TAG_COMPOUND)) {
            var e = (CompoundTag)raw; var r = new Ration();
            r.mouths = e.getInt("Mouths"); r.weight = e.getFloat("Weight"); r.allotted = e.getInt("Allotted");
            r.left = Math.max(0, e.getInt("Left")); r.own = e.getInt("Own"); r.note = e.getString("Note");
            rations.put(e.getLong("Home"), r);
        }
        standing.clear();
        for (Tag raw : tag.getList("Standing", Tag.TAG_COMPOUND)) {
            var e = (CompoundTag)raw;
            if (!e.hasUUID("Id")) continue;
            var s = new Standing();
            s.offences = Math.max(0, e.getInt("Offences")); s.lastOffence = e.getLong("LastOffence"); s.rationCutUntil = e.getLong("RationCutUntil");
            s.banishedUntil = e.getLong("BanishedUntil"); s.lastIntrusion = e.getLong("LastIntrusion"); s.intrusions = Math.max(0, e.getInt("Intrusions"));
            s.illicitCourtship = e.getLong("IllicitCourtship");
            standing.put(e.getUUID("Id"), s);
        }
        pending.clear();
        for (Tag raw : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            var e = (CompoundTag)raw;
            if (!e.hasUUID("Offender")) continue;
            var p = new Pending();
            p.offender = e.getUUID("Offender"); p.witness = e.hasUUID("Witness") ? e.getUUID("Witness") : null;
            p.offence = e.getString("Offence"); p.detail = e.getString("Detail"); p.time = e.getLong("Time");
            pending.add(p);
        }
        log.clear();
        for (Tag raw : tag.getList("Log", Tag.TAG_COMPOUND)) log.addLast((CompoundTag)raw);
        while (log.size() > LOG_SIZE) log.removeFirst();
        hunts.clear();
        for (Tag raw : tag.getList("Hunts", Tag.TAG_COMPOUND)) hunts.addLast((CompoundTag)raw);
        while (hunts.size() > HUNT_LOG_SIZE) hunts.removeFirst();
    }
}
