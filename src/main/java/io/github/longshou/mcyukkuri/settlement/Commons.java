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
 * What a led community keeps in common (package 12): its rules, the food check and relief ledger, the levy, the hunt in
 * progress, the record of hunts, offences, judgements and deaths, and the migration pressure. One instance per
 * {@link SettlementData.Group}; saved with it.
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
    /** 0 = enough, 1 = short, 2 = famine (rules change, see {@link Rations}); judged at the leader's evening food check. */
    public int famine;
    public long famineSince;
    /** Forced famine level for tests (-1 = judged from the stores). */
    public int famineOverride = -1;

    // ---- the daily food check (Rations.dailyCheck) -------------------------------------------------------------------
    /** Day number of the leader's last evening food check, -1 before the first. */
    public long checkDay = -1;
    /** Food in the community (households + common store) in days of its whole need, at the last check (x100). */
    public int foodDays100;
    /** Households below their minimum at the last check. */
    public int shortHomes;
    /** Consecutive checks in famine (2) and in shortage or worse (&ge;1). */
    public int famineDays, shortDays;
    /** Consecutive checks whose foraging outings (family trips and hunts) all came back empty; checks without outings leave it as it is. */
    public int failDays;
    /** Outings that ended since the last check, and how many brought food. */
    public int outings, fruitful;
    /** Consecutive communal hunts that brought nothing or found no meadow (territory worn out). */
    public int huntFails;
    public long noMeadowDay = -1;

    // ---- levy (公仓上缴) ------------------------------------------------------------------------------------------
    /** Portions paid into the common store and kept by the households, since the last check and in total. */
    public int levyToday, keptToday, levyTotal;

    // ---- migration pressure (Migration.pressure) ----------------------------------------------------------------
    /** Member deaths of the last days, newest last: T (time), C (cause: predator, starve, kin, player, other). */
    public final Deque<CompoundTag> deaths = new ArrayDeque<>();
    /** Last computed pressure (x100) and its main reason; the leader's deliberation reads these. */
    public int pressure100;
    public String pressureMain = "", pressureText = "";
    /** Why the current or last migration was decided (deaths, famine, predators, ...). */
    public String migrateReason = "";
    /** No new migration and no pressure from before the move until then (settling in). */
    public long settledUntil;

    // ---- rations (relief only) ----------------------------------------------------------------------------------
    /** Day number ({@code dayTime / 24000}) of the last relief distribution, -1 before the first. */
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
        /** Portions paid into the common store (the levy) and taken home by the hunters. */
        public int delivered, kept, radius;
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
    /** A foraging outing (family trip or communal hunt) ended; {@code fruitful} when it brought food back. */
    public void outing(boolean fruitful) { outings++; if (fruitful) this.fruitful++; }
    public void death(long time, String cause) {
        var entry = new CompoundTag();
        entry.putLong("T", time);
        entry.putString("C", cause);
        deaths.addLast(entry);
        while (deaths.size() > 24) deaths.removeFirst();
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
        tag.putLong("CheckDay", checkDay);
        tag.putInt("FoodDays", foodDays100);
        tag.putInt("ShortHomes", shortHomes);
        tag.putInt("FamineDays", famineDays);
        tag.putInt("ShortDays", shortDays);
        tag.putInt("FailDays", failDays);
        tag.putInt("Outings", outings);
        tag.putInt("Fruitful", fruitful);
        tag.putInt("HuntFails", huntFails);
        tag.putLong("NoMeadowDay", noMeadowDay);
        tag.putInt("LevyToday", levyToday);
        tag.putInt("KeptToday", keptToday);
        tag.putInt("LevyTotal", levyTotal);
        var deathList = new ListTag(); deathList.addAll(deaths); tag.put("Deaths", deathList);
        tag.putInt("Pressure", pressure100);
        tag.putString("PressureMain", pressureMain);
        tag.putString("PressureText", pressureText);
        tag.putString("MigrateReason", migrateReason);
        tag.putLong("SettledUntil", settledUntil);
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
        checkDay = tag.contains("CheckDay", Tag.TAG_LONG) ? tag.getLong("CheckDay") : -1;
        foodDays100 = Math.max(0, tag.getInt("FoodDays"));
        shortHomes = Math.max(0, tag.getInt("ShortHomes"));
        famineDays = Math.max(0, tag.getInt("FamineDays"));
        shortDays = Math.max(0, tag.getInt("ShortDays"));
        failDays = Math.max(0, tag.getInt("FailDays"));
        outings = Math.max(0, tag.getInt("Outings"));
        fruitful = Math.max(0, tag.getInt("Fruitful"));
        huntFails = Math.max(0, tag.getInt("HuntFails"));
        noMeadowDay = tag.contains("NoMeadowDay", Tag.TAG_LONG) ? tag.getLong("NoMeadowDay") : -1;
        levyToday = Math.max(0, tag.getInt("LevyToday"));
        keptToday = Math.max(0, tag.getInt("KeptToday"));
        levyTotal = Math.max(0, tag.getInt("LevyTotal"));
        deaths.clear();
        for (Tag raw : tag.getList("Deaths", Tag.TAG_COMPOUND)) deaths.addLast((CompoundTag)raw);
        while (deaths.size() > 24) deaths.removeFirst();
        pressure100 = Math.max(0, tag.getInt("Pressure"));
        pressureMain = tag.getString("PressureMain");
        pressureText = tag.getString("PressureText");
        migrateReason = tag.getString("MigrateReason");
        settledUntil = tag.getLong("SettledUntil");
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
