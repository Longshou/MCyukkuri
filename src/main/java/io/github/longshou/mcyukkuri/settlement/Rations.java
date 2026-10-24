package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.social.Temperament;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.IdentityHashMap;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;

/**
 * The leader's evening food check and relief from the common store by the number of mouths (按饅數分取所需, komicolle
 * 40720 No.11518859). User correction 2026-10-18: the commons is filled by the levy on every ordinary outing
 * ({@link Levy}); sharing out by mouths happens only when food is short (a household short of food, a famine, a winter
 * shortage), not as a fixed evening routine. Project design:
 * <ul>
 * <li>Once a day, at the evening gathering (day time 11000-13000; on a planned site standing at the plaza unless the
 *     winter keeps it indoors), the leader takes stock ({@link #dailyCheck}): the food of every loaded household and of
 *     the common store, in days of the community's need ({@link #PER_MOUTH} portions per weighted mouth and day), whether
 *     the foraging since the last check brought anything, and the famine level.</li>
 * <li>A household is short when its own pile holds less than one day of its need (two in winter, when nobody can
 *     forage). Only then does the leader give it relief: enough to reach that minimum, priority share first. When no
 *     household is short nothing is shared out; entitlements of an earlier relief lapse at the next check.</li>
 * <li>Mouths are weighted: adult 1, child (子油) 0.75, baby (赤油) 0.5, elder 0.75; pregnant +0.5, a parent with a
 *     baby still on mash +0.25, sick or wounded +0.25; a ration-cut offender counts half, an ununn slave a quarter,
 *     an outcast nothing. Babies, pregnant mothers, the sick and the young are the priority share.</li>
 * <li>Famine level: 2 (famine) when the whole community holds less than one day of food, or less than two days after
 *     two checks of failed foraging or with most members hungry; 1 (short) below two days or when the commons cannot
 *     cover the short households; a famine ends only at two days or more. In famine the leader hunts twice a day,
 *     nobody may court and theft weighs one step heavier.</li>
 * <li>A selfish leader (selfishness &ge; 65, self-control &lt; 50) takes half again for its own household when that one
 *     is short; the others feel it ({@code unfair_ration}).</li>
 * </ul>
 * Households fetch their share themselves (HabitatLife RATION errand: a real walk to the store, a real mouthful of up to
 * {@link #FETCH_MAX} portions, deposited on the own pile); a hungry member may also eat one ration portion at the store.
 * Taking from the store without a ration left is theft ({@link CommunityRules.Offence#THEFT}).
 */
public final class Rations {
    public static final float PER_MOUTH = 2f;
    public static final int FETCH_MAX = 4;
    static final int FETCH_HOLD = 1200;
    /** A landing can take longer than a land errand; the same one-hat household remains exclusive throughout it. */
    static final int WATER_FETCH_HOLD = 3200;
    static final int START = 11000, END = 13000;
    /** Days of current community need the common store aims at; hunts stop and the levy pauses at that target. */
    static final int STORE_DAYS = 3;

    static long key(ShelterBlockEntity home) { return home.getBlockPos().asLong(); }

    /** The member's community shares by mouth and has a real common store. */
    public static boolean governs(YukkuriEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        var group = entity.settlement().group();
        return CommunityRules.rule(group, Commons.SHARE_BY_MOUTH) && !CommonStore.piles(level, group).isEmpty();
    }

    /** The current share of the household {@code entity} lives in, or null. */
    public static Commons.Ration ration(YukkuriEntity entity) {
        var group = entity.settlement().group();
        if (group == null) return null;
        var boat = entity.habitat().waterHome();
        if (boat != null) return group.commons.waterRations.get(boat.getUUID());
        var home = entity.habitat().shelter();
        return home == null ? null : group.commons.rations.get(key(home));
    }

    // ---- weights --------------------------------------------------------------------------------------------------
    record Mouth(float weight, float priority) {}
    static Mouth mouth(YukkuriEntity m, SettlementData.Group group, long now) {
        var role = group.role(m.getUUID());
        if (role == SettlementData.Role.OUTCAST) return new Mouth(0, 0);
        if (role == SettlementData.Role.UNUN_SLAVE) return new Mouth(.25f, 0);
        var family = m.family();
        float base, priority = 0;
        if (family.stage() == FamilyLife.Stage.BABY) { base = .5f; priority = .5f; }
        else if (family.stage() == FamilyLife.Stage.JUVENILE) { base = .75f; priority = .75f; }
        else if (family.elder()) base = .75f;
        else base = 1f;
        float extra = 0;
        if (CommunityRules.pregnant(m)) extra += .5f;
        if (family.adult() && nursing(m)) extra += .25f;
        if (m.getHealth() < m.getMaxHealth() * .6f || m.body().condition().needsExtraRation()) extra += .25f;
        priority += extra;
        float weight = base + extra;
        var s = group.commons.existing(m.getUUID());
        if (s != null && s.rationCutUntil > now) { weight *= .5f; priority *= .5f; }
        return new Mouth(weight, Math.min(priority, weight));
    }
    private static boolean nursing(YukkuriEntity parent) {
        if (!(parent.level() instanceof ServerLevel level)) return false;
        var data = FamilyData.get(level);
        for (UUID id : data.children(parent.getUUID()))
            if (level.getEntity(id) instanceof YukkuriEntity child && child.isAlive() && child.family().needsMash()) return true;
        return false;
    }

    static List<YukkuriEntity> members(ServerLevel level, SettlementData.Group group) {
        return LoadedGroupMembers.query(level, group.id, new AABB(group.center).inflate(64), e -> e.isAlive() && group.id.equals(e.settlement().groupId()));
    }
    /** Human-requested report lines preserve their existing household encounter order. */
    private static List<YukkuriEntity> membersInEncounterOrder(ServerLevel level, SettlementData.Group group) {
        return level.getEntitiesOfClass(YukkuriEntity.class, new AABB(group.center).inflate(64), e -> e.isAlive() && group.id.equals(e.settlement().groupId()));
    }
    /** One day of the whole loaded community's need, in portions. */
    static int communityNeed(ServerLevel level, SettlementData.Group group) {
        long now = level.getGameTime();
        float weight = 0;
        for (var m : members(level, group)) weight += mouth(m, group, now).weight();
        return (int)Math.ceil(weight * PER_MOUTH);
    }
    /** What the common store aims to hold: {@value #STORE_DAYS} days of current need, at most 80 % of its room. */
    public static int storeTarget(ServerLevel level, SettlementData.Group group) {
        return Math.min(communityNeed(level, group) * STORE_DAYS, (int)(CommonStore.capacity(level, group) * .8));
    }
    /** Days of its own need a household must hold before it counts as short: one, two in winter. */
    static int minimumDays(ServerLevel level, SettlementData.Group group) { return Seasons.on(level, group.center, Season.WINTER) ? 2 : 1; }
    /** The household living in {@code home} holds less than its minimum (counting its loaded residents of this community). */
    public static boolean isShort(ServerLevel level, SettlementData.Group group, ShelterBlockEntity home) {
        long now = level.getGameTime();
        float weight = 0;
        for (UUID id : home.residentIds())
            if (level.getEntity(id) instanceof YukkuriEntity m && m.isAlive() && group.id.equals(m.settlement().groupId())) weight += mouth(m, group, now).weight();
        if (weight <= 0) return false;
        return home.food() < Math.ceil(weight * PER_MOUTH) * minimumDays(level, group);
    }
    /**
     * An afloat family has one food inventory: the actual hat cargo.  Do not turn every passenger into a household,
     * otherwise the same cargo is counted once per child and a full hat can be judged as famine.
     */
    public static boolean isShort(YukkuriEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return false;
        var group = entity.settlement().group();
        var boat = entity.habitat().waterHome();
        if (group == null || boat == null) return false;
        var household = waterHouseholds(level, group).get(boat.getUUID());
        return household != null && household.own < Math.ceil(household.weight * PER_MOUTH) * minimumDays(level, group);
    }
    private static boolean onCommons(List<ForagePileBlockEntity> common, ShelterBlockEntity home) {
        var pile = home.pile();
        if (pile == null) return false;
        for (var p : common) if (p.getBlockPos().equals(pile.getBlockPos())) return true;
        return false;
    }

    /** Runtime-only family address for a physical hat.  The boat UUID is the key, never a derived land position. */
    private static final class WaterHousehold {
        final HatBoatEntity boat;
        final List<YukkuriEntity> people = new ArrayList<>();
        float weight, priority;
        int need, minimum, own;
        boolean leaders;
        WaterHousehold(HatBoatEntity boat) { this.boat = boat; }
    }

    /** All loaded members presently addressed by a physical hat, with cargo recorded exactly once per hat. */
    private static Map<UUID, WaterHousehold> waterHouseholds(ServerLevel level, SettlementData.Group group) {
        long now = level.getGameTime();
        Map<UUID, WaterHousehold> homes = new LinkedHashMap<>();
        for (var m : members(level, group)) {
            var boat = m.habitat().waterHome();
            var mouth = mouth(m, group, now);
            if (boat == null || mouth.weight() <= 0) continue;
            var home = homes.computeIfAbsent(boat.getUUID(), id -> new WaterHousehold(boat));
            home.people.add(m); home.weight += mouth.weight(); home.priority += mouth.priority();
            if (m.getUUID().equals(group.leader)) home.leaders = true;
        }
        for (var home : homes.values()) {
            // availableFood has one cargo count per observer.  Only its non-cargo remainder belongs to that observer.
            int cargo = home.boat.cargo().count();
            int personal = 0;
            for (var person : home.people) personal += Math.max(0, person.aquaticLife().availableFood() - cargo);
            home.own = cargo + personal;
        }
        return homes;
    }

    /** Report lines (operator command): each loaded household with its pile, food, minimum and whether it is short. */
    static List<String> households(ServerLevel level, SettlementData.Group group) {
        long now = level.getGameTime();
        var commonPiles = CommonStore.piles(level, group);
        Map<Long, Household> homes = new LinkedHashMap<>();
        for (var m : membersInEncounterOrder(level, group)) {
            var home = m.habitat().shelter();
            var mouth = mouth(m, group, now);
            if (home == null || mouth.weight() <= 0) continue;
            var h = homes.computeIfAbsent(key(home), k -> new Household(home));
            h.people.add(m); h.weight += mouth.weight();
        }
        int days = minimumDays(level, group);
        var out = new ArrayList<String>();
        for (var h : homes.values()) {
            int minimum = (int)Math.ceil(h.weight * PER_MOUTH) * days;
            var pile = h.home.pile();
            out.add("household " + h.home.getBlockPos().toShortString() + " pile " + (pile == null ? "-" : pile.getBlockPos().toShortString())
                    + " people " + h.people.size() + " food " + h.home.food() + " minimum " + minimum
                    + (onCommons(commonPiles, h.home) ? " commons" : h.home.food() < minimum ? " SHORT" : ""));
        }
        for (var h : waterHouseholds(level, group).values()) {
            int minimum = (int)Math.ceil(h.weight * PER_MOUTH) * days;
            out.add("household hat " + h.boat.getUUID() + " people " + h.people.size() + " food " + h.own + " minimum " + minimum
                    + (h.own < minimum ? " SHORT" : ""));
        }
        return out;
    }

    // ---- the evening check ----------------------------------------------------------------------------------------
    static void tickLeader(YukkuriEntity leader, SettlementData.Group group) {
        if (!(leader.level() instanceof ServerLevel level) || !CommunityRules.ruled(group) || Migration.moving(group)) return;
        long day = level.getDayTime() / 24000, time = level.getDayTime() % 24000;
        if (group.commons.checkDay == day || time < START || time >= END) return;
        if (leader.life().isSleeping() || leader.isPanicking()) return;
        if (SiteLife.of(level, group) != null && !SettlementSeasons.confined(leader) && leader.distanceToSqr(Vec3.atBottomCenterOf(group.center)) > 16 * 16) return;
        dailyCheck(level, group, leader);
    }

    private static final class Household {
        final ShelterBlockEntity home;
        final List<YukkuriEntity> people = new ArrayList<>();
        float weight, priority;
        int need, minimum, want, own, quota;
        boolean leaders, common;
        Household(ShelterBlockEntity home) { this.home = home; }
    }
    /** One distribution target, with either a land pile or one physical afloat hat as its address. */
    private static final class ReliefTarget {
        final Household land;
        final WaterHousehold water;
        final List<YukkuriEntity> people;
        final float weight, priority;
        final boolean leaders;
        int want, quota;
        ReliefTarget(Household home) {
            land = home; water = null; people = home.people; weight = home.weight; priority = home.priority; leaders = home.leaders;
        }
        ReliefTarget(WaterHousehold home) {
            land = null; water = home; people = home.people; weight = home.weight; priority = home.priority; leaders = home.leaders;
        }
        int own() { return land != null ? land.own : water.own; }
        int minimum() { return land != null ? land.minimum : water.minimum; }
        boolean common() { return land != null && land.common; }
    }

    /**
     * The leader's evening check now: takes stock, judges the famine level, and gives relief to the short households if
     * the community shares by mouth. Returns a one-line summary. {@code leader} may be null (test command).
     */
    public static String dailyCheck(ServerLevel level, SettlementData.Group group, YukkuriEntity leader) {
        var commons = group.commons;
        long now = level.getGameTime(), day = level.getDayTime() / 24000;
        var members = members(level, group);
        var commonPiles = CommonStore.piles(level, group);
        Map<Long, Household> homes = new LinkedHashMap<>();
        var waterHomes = waterHouseholds(level, group);
        int hungry = 0, minDays = minimumDays(level, group);
        float totalWeight = 0;
        for (var m : members) {
            if (m.life().nutrition() < 30) hungry++;
            var mouth = mouth(m, group, now);
            totalWeight += mouth.weight();
            var home = m.habitat().shelter();
            if (home == null || mouth.weight() <= 0) continue;
            var h = homes.computeIfAbsent(key(home), k -> new Household(home));
            h.people.add(m); h.weight += mouth.weight(); h.priority += mouth.priority();
            if (m.getUUID().equals(group.leader)) h.leaders = true;
        }
        int stock = CommonStore.stock(level, group), held = 0;
        for (var h : homes.values()) {
            h.need = (int)Math.ceil(h.weight * PER_MOUTH);
            h.minimum = h.need * minDays;
            h.own = h.home.food();
            // In a cave community the leader's own pile is the commons: counted once, never short of relief.
            h.common = onCommons(commonPiles, h.home);
            if (!h.common) held += h.own;
        }
        int waterShortHomes = 0, waterShortWant = 0;
        for (var h : waterHomes.values()) {
            h.need = (int)Math.ceil(h.weight * PER_MOUTH);
            h.minimum = h.need * minDays;
            held += h.own;
            if (h.own < h.minimum) { waterShortHomes++; waterShortWant += h.minimum - h.own; }
        }
        int need = (int)Math.ceil(totalWeight * PER_MOUTH);

        // Foraging since the last check: every outing empty is one more failed day; no outing at all changes nothing.
        if (commons.outings > 0) commons.failDays = commons.fruitful > 0 ? 0 : commons.failDays + 1;
        int outings = commons.outings, fruitful = commons.fruitful, levy = commons.levyToday, kept = commons.keptToday;
        commons.outings = commons.fruitful = commons.levyToday = commons.keptToday = 0;

        float days = need <= 0 ? 99 : (stock + held) / (float)need;
        int shortHomes = waterShortHomes, shortWant = waterShortWant;
        for (var h : homes.values()) if (!h.common && h.own < h.minimum) { shortHomes++; shortWant += h.minimum - h.own; }
        int before = commons.famine, famine;
        if (commons.famineOverride >= 0) famine = commons.famineOverride;
        else if (need <= 0) famine = 0;
        else if (days < 1 || days < 2 && (commons.failDays >= 2 || hungry * 2 > members.size())) famine = 2;
        else if (before >= 2 && days < 2) famine = 2; // a famine ends only with two days of food in hand
        else if (days < 2 || shortWant > stock) famine = 1;
        else famine = 0;
        commons.famine = famine;
        commons.famineDays = famine >= 2 ? commons.famineDays + 1 : 0;
        commons.shortDays = famine >= 1 ? commons.shortDays + 1 : 0;
        commons.foodDays100 = Math.round(Math.min(days, 99) * 100);
        commons.shortHomes = shortHomes;
        commons.checkDay = day;
        String daysText = String.valueOf(Math.round(days * 10) / 10f);
        if (famine >= 2 && before < 2) {
            commons.famineSince = now;
            commons.note(now, "famine", leader == null ? null : leader.getUUID(), "days " + daysText + " failDays " + commons.failDays);
            if (leader != null) leader.showReaction("teary", "famine", 100);
            for (var m : members) m.mind().add(5, "famine");
        } else if (famine < 2 && before >= 2) {
            commons.note(now, "famine_over", leader == null ? null : leader.getUUID(), "days " + daysText);
            for (var m : members) m.mind().add(-3, "famine_over");
        }
        String relief = CommunityRules.rule(group, Commons.SHARE_BY_MOUTH) ? relieve(level, group, leader, homes, waterHomes, stock) : "no share rule";
        String summary = "day " + day + " food " + daysText + " days (store " + stock + " homes " + held + " need " + need + ")"
                + " outings " + fruitful + "/" + outings + " failDays " + commons.failDays + " levy " + levy + " kept " + kept
                + " famine " + famine + " short " + shortHomes + "; " + relief;
        commons.note(now, "check", leader == null ? null : leader.getUUID(), summary);
        SettlementData.get(level).setDirty();
        return summary;
    }

    /** Relief for the short households only, out of the common store. */
    private static String relieve(ServerLevel level, SettlementData.Group group, YukkuriEntity leader,
                                  Map<Long, Household> homes, Map<UUID, WaterHousehold> waterHomes, int stock) {
        var commons = group.commons;
        long now = level.getGameTime(), day = level.getDayTime() / 24000;
        boolean winter = Seasons.on(level, group.center, Season.WINTER);
        int available = Math.max(0, stock);
        boolean unfair = leader != null && Levy.greedy(leader);
        int wanted = 0;
        var list = new ArrayList<ReliefTarget>();
        for (var h : homes.values()) {
            var target = new ReliefTarget(h);
            target.want = target.common() ? 0 : Math.max(0, target.minimum() - target.own());
            if (unfair && target.leaders && target.want > 0) target.want = (int)Math.ceil(target.want * 1.5);
            if (target.want > 0) { wanted += target.want; list.add(target); }
        }
        for (var h : waterHomes.values()) {
            var target = new ReliefTarget(h);
            target.want = Math.max(0, target.minimum() - target.own());
            if (unfair && target.leaders && target.want > 0) target.want = (int)Math.ceil(target.want * 1.5);
            if (target.want > 0) { wanted += target.want; list.add(target); }
        }
        commons.clearRations();
        commons.rationStock = stock; commons.rationWanted = wanted;
        if (wanted == 0) { commons.rationGiven = 0; return "no household short, nothing shared"; }
        int left = available;
        if (left >= wanted) {
            for (var h : list) h.quota = h.want;
            left -= wanted;
        } else {
            if (left > 0 && tiedPriority(list)) restoreHouseholdOrder(level, group, list);
            // Priority share first (babies, pregnant, sick, young), largest priority first.
            list.sort(Comparator.comparingDouble((ReliefTarget h) -> -h.priority));
            for (var h : list) {
                int first = h.weight <= 0 ? 0 : Math.min(h.want, (int)Math.ceil(h.want * h.priority / h.weight));
                int give = Math.min(first, left);
                h.quota = give; left -= give;
            }
            int rest = 0;
            for (var h : list) rest += h.want - h.quota;
            if (rest > 0 && left > 0) {
                int pool = left;
                for (var h : list) {
                    int give = (int)Math.floor(pool * (h.want - h.quota) / (double)rest);
                    give = Math.min(give, left);
                    h.quota += give; left -= give;
                }
                for (var h : list) { if (left <= 0) break; if (h.quota < h.want) { h.quota++; left--; } }
            }
        }
        int given = 0;
        for (var h : list) {
            var r = new Commons.Ration();
            r.mouths = h.people.size(); r.weight = Math.round(h.weight * 100) / 100f; r.allotted = r.left = h.quota; r.own = h.own();
            r.note = h.quota < h.want ? "short" : "relieved";
            if (h.land != null) commons.rations.put(key(h.land.home), r);
            else commons.waterRations.put(h.water.boat.getUUID(), r);
            given += h.quota;
        }
        commons.rationDay = day; commons.rationTime = now; commons.rationGiven = given;
        if (leader != null && given > 0) leader.showReaction("happy", "ration_give", 80);
        for (var h : list) if (h.quota > 0) for (var m : h.people) m.mind().add(-2, "ration");
        if (unfair) for (var h : homes.values()) if (!h.leaders) for (var m : h.people) if (m.family().adult()) m.mind().add(3, "unfair_ration");
        if (unfair) for (var h : waterHomes.values()) if (!h.leaders) for (var m : h.people) if (m.family().adult()) m.mind().add(3, "unfair_ration");
        String summary = "relief " + given + "/" + wanted + " to " + list.size() + " short households"
                + (winter ? " winter" : "") + (unfair ? " unfair" : "");
        commons.note(now, "ration", leader == null ? null : leader.getUUID(), summary);
        return summary;
    }

    private static boolean tiedPriority(List<ReliefTarget> homes) {
        var priorities = new HashSet<Float>();
        for (var home : homes) if (!priorities.add(home.priority)) return true;
        return false;
    }

    /** Scarce equal-priority shares (including the final one-portion remainder) retain original household encounter order. */
    private static void restoreHouseholdOrder(ServerLevel level, SettlementData.Group group, List<ReliefTarget> homes) {
        var owners = new IdentityHashMap<YukkuriEntity, ReliefTarget>();
        var people = new ArrayList<YukkuriEntity>();
        for (var home : homes) for (var person : home.people) { owners.put(person, home);people.add(person); }
        var order = new IdentityHashMap<ReliefTarget, Integer>();
        for (var person : LoadedGroupMembers.encounterOrder(level, new AABB(group.center).inflate(64), people)) {
            var home = owners.get(person);
            if (!order.containsKey(home)) order.put(home, order.size());
        }
        homes.sort(Comparator.comparingInt(home -> order.getOrDefault(home, Integer.MAX_VALUE)));
    }

    // ---- fetching and eating a ration --------------------------------------------------------------------------------
    /** One transient attempt, bound to the actual allocation, household and source pile. Identity is its generation. */
    public static final class Fetch {
        final ServerLevel level;
        final SettlementData.Group group;
        final ShelterBlockEntity home;
        final ForagePileBlockEntity pile;
        final Commons.Ration ration;
        final UUID fetcher;
        final long homeKey;
        private Fetch(ServerLevel level, SettlementData.Group group, ShelterBlockEntity home, ForagePileBlockEntity pile,
                      Commons.Ration ration, UUID fetcher) {
            this.level=level;this.group=group;this.home=home;this.pile=pile;this.ration=ration;this.fetcher=fetcher;homeKey=key(home);
        }
        public ForagePileBlockEntity pile() { return pile; }
    }
    /**
     * Starts a household's fetch: the common pile to walk to, or null. Claims the errand for 1200 ticks so only one
     * member of the household goes. A household whose own pile is the common store (the leader of a cave community)
     * already has its share at home.
     */
    public static Fetch fetchStart(YukkuriEntity entity, ShelterBlockEntity home) {
        if (!(entity.level() instanceof ServerLevel level) || home == null || entity.habitat().shelter()!=home || !entity.family().adult() || !governs(entity)) return null;
        var group = entity.settlement().group();
        var r = group.commons.rations.get(key(home));
        long now = level.getGameTime();
        if (r == null || r.left <= 0 || !group.commons.rationFree(key(home),now)) return null;
        var pile = CommonStore.nearest(level, group, entity.blockPosition(), p -> !p.peek(stack -> entity.life().acceptsFood(stack)).isEmpty());
        if (pile == null) return null;
        var own = home.pile();
        if (own != null && own.getBlockPos().equals(pile.getBlockPos())) { r.left = 0; r.note = "kept_at_home"; SettlementData.get(level).setDirty(); return null; }
        if (home.foodSpace(pile.peek(stack -> entity.life().acceptsFood(stack))) <= 0) return null;
        var fetch=new Fetch(level,group,home,pile,r,entity.getUUID());
        if(!group.commons.claimRation(fetch,now))return null;
        SettlementData.get(level).setDirty();
        return fetch;
    }

    /** At the store: takes up to the remaining share (at most {@link #FETCH_MAX}) of one kind of food, as one real stack. */
    public static ItemStack fetchTake(YukkuriEntity entity, ShelterBlockEntity home, ForagePileBlockEntity pile, Fetch fetch) {
        if(fetch==null||entity.level()!=fetch.level||entity.settlement().group()!=fetch.group
                ||home!=fetch.home||entity.habitat().shelter()!=home||pile!=fetch.pile
                ||!entity.getUUID().equals(fetch.fetcher)||!fetch.group.commons.ownsRation(fetch,fetch.level.getGameTime())
                ||!fetch.level.hasChunkAt(pile.getBlockPos())||fetch.level.getBlockEntity(pile.getBlockPos())!=pile
                ||fetch.ration.left<=0)return ItemStack.EMPTY;
        var group=fetch.group;var r=fetch.ration;
        var first = pile.take(stack -> entity.life().acceptsFood(stack));
        if (first.isEmpty()) return ItemStack.EMPTY;
        int want = Math.min(r.left, FETCH_MAX);
        while (first.getCount() < want) {
            var more = pile.take(stack -> ItemStack.isSameItemSameComponents(stack, first));
            if (more.isEmpty()) break;
            first.grow(more.getCount());
        }
        r.left -= first.getCount(); group.commons.releaseRation(fetch);
        if (entity.level() instanceof ServerLevel level) {
            group.commons.note(level.getGameTime(), "ration_fetched", entity.getUUID(), first.getCount() + " left " + r.left);
            SettlementData.get(level).setDirty();
        }
        entity.showReaction("happy", "ration_get", 60);
        return first;
    }

    public static void fetchAbort(Fetch fetch) { if(fetch!=null)fetch.group.commons.releaseRation(fetch); }

    /** A water-household fetch begun by the actual owner while aboard its actual afloat hat. */
    public static final class WaterFetch {
        final ServerLevel level;
        final SettlementData.Group group;
        final UUID boatId;
        final ForagePileBlockEntity pile;
        final Commons.Ration ration;
        final UUID fetcher;
        private WaterFetch(ServerLevel level, SettlementData.Group group, UUID boatId, ForagePileBlockEntity pile,
                           Commons.Ration ration, UUID fetcher) {
            this.level=level;this.group=group;this.boatId=boatId;this.pile=pile;this.ration=ration;this.fetcher=fetcher;
        }
        public ForagePileBlockEntity pile() { return pile; }
    }

    /**
     * Reserves one afloat household's allocation before it asks its own captain to land. The bound hat must really be
     * afloat, carrying this exact adult owner, and protecting it at the moment of the request; after landing the same
     * transient token carries that verified address through the dry-bank contact rather than inventing a land home.
     */
    public static WaterFetch waterFetchStart(YukkuriEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !entity.family().adult() || !governs(entity)) return null;
        var group = entity.settlement().group();
        var boat = entity.habitat().waterHome();
        if (group == null || boat == null || entity.getVehicle()!=boat || !boat.isAfloat()
                || !entity.getUUID().equals(boat.ownerYukkuri()) || !boat.protects(entity)) return null;
        var r = group.commons.waterRations.get(boat.getUUID());
        long now = level.getGameTime();
        if (r == null || r.left <= 0 || !group.commons.waterRationFree(boat.getUUID(), now)) return null;
        var pile = CommonStore.nearest(level, group, entity.blockPosition(), p -> !p.peek(stack -> entity.life().acceptsFood(stack)).isEmpty());
        if (pile == null) return null;
        var fetch = new WaterFetch(level, group, boat.getUUID(), pile, r, entity.getUUID());
        if (!group.commons.claimWaterRation(fetch, now)) return null;
        SettlementData.get(level).setDirty();
        return fetch;
    }

    /** The transient landing hand-off may wait through a dismount, but it may never act after its lease expired. */
    static boolean waterFetchActive(WaterFetch fetch) {
        return fetch != null && fetch.group.commons.ownsWaterRation(fetch, fetch.level.getGameTime());
    }

    /**
     * At the real common pile after the landing contact: take one physical stack, carry only what the still-owned
     * aquatic provisions slot can accept, and put the remainder back before reducing the allocation. The boat identity
     * was checked before landing; this method deliberately requires an actual dry-bank actor instead of a hidden cargo
     * transfer.
     */
    public static int waterFetchTake(YukkuriEntity entity, ForagePileBlockEntity pile, WaterFetch fetch) {
        if (fetch==null || entity.level()!=fetch.level || entity.settlement().group()!=fetch.group || pile!=fetch.pile
                || !entity.getUUID().equals(fetch.fetcher) || !fetch.group.commons.ownsWaterRation(fetch,fetch.level.getGameTime())
                || !entity.profile().aquatic() || entity.isPassenger() || !entity.onGround()
                || !fetch.level.hasChunkAt(pile.getBlockPos()) || fetch.level.getBlockEntity(pile.getBlockPos())!=pile
                || !CommonStore.isCommon(fetch.level,fetch.group,pile) || fetch.ration.left<=0) return 0;
        var first = pile.take(stack -> entity.life().acceptsFood(stack));
        if (first.isEmpty()) return 0;
        int want = Math.min(fetch.ration.left, FETCH_MAX);
        while (first.getCount() < want) {
            var more = pile.take(stack -> ItemStack.isSameItemSameComponents(stack, first));
            if (more.isEmpty()) break;
            first.grow(more.getCount());
        }
        int moved = entity.aquaticLife().receiveProvisions(first, first.getCount());
        if (!first.isEmpty()) pile.returnFood(first);
        if (moved > 0) fetch.ration.left -= moved;
        fetch.group.commons.releaseWaterRation(fetch);
        fetch.group.commons.note(fetch.level.getGameTime(), moved > 0 ? "ration_water_fetched" : "ration_water_unloaded",
                entity.getUUID(), "hat " + fetch.boatId + " moved " + moved + " left " + fetch.ration.left);
        SettlementData.get(fetch.level).setDirty();
        if (moved > 0) entity.showReaction("happy", "ration_get", 60);
        return moved;
    }

    public static void waterFetchAbort(WaterFetch fetch) { if(fetch!=null)fetch.group.commons.releaseWaterRation(fetch); }

    /** True when eating one portion at the store is this household's ration; false means it would be theft. */
    public static boolean allowance(YukkuriEntity entity) {
        var r = ration(entity);
        return r != null && r.left > 0;
    }
    /** One ration portion was eaten at the store. */
    public static void ateAllowance(YukkuriEntity entity) {
        var r = ration(entity);
        if (r != null && r.left > 0) r.left--;
    }

    /**
     * Whether a hungry member with no ration left takes from the store anyway (project design): the starving give way
     * unless very self-controlled; otherwise only the selfish with little self-control. Famine makes it likelier.
     */
    public static boolean wouldSteal(YukkuriEntity entity) {
        var p = entity.life().personality();
        float nutrition = entity.life().nutrition();
        var group = entity.settlement().group();
        boolean famine = group != null && group.commons.famine >= 2;
        if (nutrition < 15) return p.selfControl() < Temperament.SELF_CONTROL_AT_70;
        if (famine && nutrition < 25) return p.selfControl() < Temperament.SELF_CONTROL_AT_60;
        return p.friendliness() <= 40 && p.selfControl() < Temperament.SELF_CONTROL_AT_50;
    }

    private Rations() {}
}
