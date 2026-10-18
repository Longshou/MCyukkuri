package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
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
 *     nobody may court, theft weighs one step heavier, and the autumn reserve is released.</li>
 * <li>In autumn two days of the whole community's need stay in the store for the winter (outside famine).</li>
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
    static final int START = 11000, END = 13000;
    /** Days of the community's need the common store aims at (hunts stop, the levy pauses); autumn more, for the winter. */
    static final int STORE_DAYS = 3, AUTUMN_STORE_DAYS = 5;

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
        var home = entity.habitat().shelter();
        return group == null || home == null ? null : group.commons.rations.get(key(home));
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
        return level.getEntitiesOfClass(YukkuriEntity.class, new AABB(group.center).inflate(64), e -> e.isAlive() && group.id.equals(e.settlement().groupId()));
    }
    /** One day of the whole loaded community's need, in portions. */
    static int communityNeed(ServerLevel level, SettlementData.Group group) {
        long now = level.getGameTime();
        float weight = 0;
        for (var m : members(level, group)) weight += mouth(m, group, now).weight();
        return (int)Math.ceil(weight * PER_MOUTH);
    }
    /** What the common store aims to hold: {@value #STORE_DAYS} days of need (autumn {@value #AUTUMN_STORE_DAYS}), at most 80 % of its room. */
    public static int storeTarget(ServerLevel level, SettlementData.Group group) {
        int days = Seasons.on(level, group.center, Season.AUTUMN) ? AUTUMN_STORE_DAYS : STORE_DAYS;
        return Math.min(communityNeed(level, group) * days, (int)(CommonStore.capacity(level, group) * .8));
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
    private static boolean onCommons(List<ForagePileBlockEntity> common, ShelterBlockEntity home) {
        var pile = home.pile();
        if (pile == null) return false;
        for (var p : common) if (p.getBlockPos().equals(pile.getBlockPos())) return true;
        return false;
    }

    /** Report lines (operator command): each loaded household with its pile, food, minimum and whether it is short. */
    static List<String> households(ServerLevel level, SettlementData.Group group) {
        long now = level.getGameTime();
        var commonPiles = CommonStore.piles(level, group);
        Map<Long, Household> homes = new LinkedHashMap<>();
        for (var m : members(level, group)) {
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
        return out;
    }

    // ---- the evening check ----------------------------------------------------------------------------------------
    static void tickLeader(YukkuriEntity leader, SettlementData.Group group) {
        if (!(leader.level() instanceof ServerLevel level) || !CommunityRules.ruled(group) || Migration.moving(group)) return;
        long day = level.getDayTime() / 24000, time = level.getDayTime() % 24000;
        if (group.commons.checkDay == day || time < START || time >= END) return;
        if (leader.life().isSleeping() || leader.isPanicking()) return;
        if (SiteLife.of(level, group) != null && !Seasons.confined(leader) && leader.distanceToSqr(Vec3.atBottomCenterOf(group.center)) > 16 * 16) return;
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
        int need = (int)Math.ceil(totalWeight * PER_MOUTH);

        // Foraging since the last check: every outing empty is one more failed day; no outing at all changes nothing.
        if (commons.outings > 0) commons.failDays = commons.fruitful > 0 ? 0 : commons.failDays + 1;
        int outings = commons.outings, fruitful = commons.fruitful, levy = commons.levyToday, kept = commons.keptToday;
        commons.outings = commons.fruitful = commons.levyToday = commons.keptToday = 0;

        float days = need <= 0 ? 99 : (stock + held) / (float)need;
        int shortHomes = 0, shortWant = 0;
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
        String relief = CommunityRules.rule(group, Commons.SHARE_BY_MOUTH) ? relieve(level, group, leader, homes, stock, totalWeight) : "no share rule";
        String summary = "day " + day + " food " + daysText + " days (store " + stock + " homes " + held + " need " + need + ")"
                + " outings " + fruitful + "/" + outings + " failDays " + commons.failDays + " levy " + levy + " kept " + kept
                + " famine " + famine + " short " + shortHomes + "; " + relief;
        commons.note(now, "check", leader == null ? null : leader.getUUID(), summary);
        SettlementData.get(level).setDirty();
        return summary;
    }

    /** Relief for the short households only, out of the common store. */
    private static String relieve(ServerLevel level, SettlementData.Group group, YukkuriEntity leader, Map<Long, Household> homes, int stock, float totalWeight) {
        var commons = group.commons;
        long now = level.getGameTime(), day = level.getDayTime() / 24000;
        boolean winter = Seasons.on(level, group.center, Season.WINTER);
        int reserve = !winter && commons.famine < 2 && Seasons.on(level, group.center, Season.AUTUMN) ? (int)Math.ceil(totalWeight * PER_MOUTH * 2) : 0;
        int available = Math.max(0, stock - reserve);
        boolean unfair = leader != null && Levy.greedy(leader);
        int wanted = 0;
        var list = new ArrayList<Household>();
        for (var h : homes.values()) {
            h.want = h.common ? 0 : Math.max(0, h.minimum - h.own);
            if (unfair && h.leaders && h.want > 0) h.want = (int)Math.ceil(h.want * 1.5);
            if (h.want > 0) { wanted += h.want; list.add(h); }
        }
        commons.rations.clear();
        commons.rationStock = stock; commons.rationWanted = wanted;
        if (wanted == 0) { commons.rationGiven = 0; return "no household short, nothing shared"; }
        int left = available;
        if (left >= wanted) {
            for (var h : list) h.quota = h.want;
            left -= wanted;
        } else {
            // Priority share first (babies, pregnant, sick, young), largest priority first.
            list.sort(Comparator.comparingDouble((Household h) -> -h.priority));
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
            r.mouths = h.people.size(); r.weight = Math.round(h.weight * 100) / 100f; r.allotted = r.left = h.quota; r.own = h.own;
            r.note = h.quota < h.want ? "short" : "relieved";
            commons.rations.put(key(h.home), r);
            given += h.quota;
        }
        commons.rationDay = day; commons.rationTime = now; commons.rationGiven = given;
        if (leader != null && given > 0) leader.showReaction("happy", "ration_give", 80);
        for (var h : list) if (h.quota > 0) for (var m : h.people) m.mind().add(-2, "ration");
        if (unfair) for (var h : homes.values()) if (!h.leaders) for (var m : h.people) if (m.family().adult()) m.mind().add(3, "unfair_ration");
        String summary = "relief " + given + "/" + wanted + " to " + list.size() + " short households" + (reserve > 0 ? " reserve " + reserve : "")
                + (winter ? " winter" : "") + (unfair ? " unfair" : "");
        commons.note(now, "ration", leader == null ? null : leader.getUUID(), summary);
        return summary;
    }

    // ---- fetching and eating a ration --------------------------------------------------------------------------------
    /**
     * Starts a household's fetch: the common pile to walk to, or null. Claims the errand for 1200 ticks so only one
     * member of the household goes. A household whose own pile is the common store (the leader of a cave community)
     * already has its share at home.
     */
    public static ForagePileBlockEntity fetchStart(YukkuriEntity entity, ShelterBlockEntity home) {
        if (!(entity.level() instanceof ServerLevel level) || home == null || !entity.family().adult() || !governs(entity)) return null;
        var group = entity.settlement().group();
        var r = group.commons.rations.get(key(home));
        long now = level.getGameTime();
        if (r == null || r.left <= 0 || r.fetcher != null && !r.fetcher.equals(entity.getUUID()) && r.fetchUntil > now) return null;
        var pile = CommonStore.nearest(level, group, entity.blockPosition(), p -> !p.peek(stack -> entity.life().acceptsFood(stack)).isEmpty());
        if (pile == null) return null;
        var own = home.pile();
        if (own != null && own.getBlockPos().equals(pile.getBlockPos())) { r.left = 0; r.note = "kept_at_home"; SettlementData.get(level).setDirty(); return null; }
        if (home.foodSpace(pile.peek(stack -> entity.life().acceptsFood(stack))) <= 0) return null;
        r.fetcher = entity.getUUID(); r.fetchUntil = now + 1200;
        SettlementData.get(level).setDirty();
        return pile;
    }

    /** At the store: takes up to the remaining share (at most {@link #FETCH_MAX}) of one kind of food, as one real stack. */
    public static ItemStack fetchTake(YukkuriEntity entity, ShelterBlockEntity home, ForagePileBlockEntity pile) {
        var group = entity.settlement().group();
        var r = group == null || home == null ? null : group.commons.rations.get(key(home));
        if (r == null || r.left <= 0) return ItemStack.EMPTY;
        var first = pile.take(stack -> entity.life().acceptsFood(stack));
        if (first.isEmpty()) return ItemStack.EMPTY;
        int want = Math.min(r.left, FETCH_MAX);
        while (first.getCount() < want) {
            var more = pile.take(stack -> ItemStack.isSameItemSameComponents(stack, first));
            if (more.isEmpty()) break;
            first.grow(more.getCount());
        }
        r.left -= first.getCount(); r.fetcher = null; r.fetchUntil = 0;
        if (entity.level() instanceof ServerLevel level) {
            group.commons.note(level.getGameTime(), "ration_fetched", entity.getUUID(), first.getCount() + " left " + r.left);
            SettlementData.get(level).setDirty();
        }
        entity.showReaction("happy", "ration_get", 60);
        return first;
    }

    public static void fetchAbort(YukkuriEntity entity, ShelterBlockEntity home) {
        var group = entity.settlement().group();
        var r = group == null || home == null ? null : group.commons.rations.get(key(home));
        if (r != null && entity.getUUID().equals(r.fetcher)) { r.fetcher = null; r.fetchUntil = 0; }
    }

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
        return p.selfishness() >= Temperament.SELFISHNESS_AT_60 && p.selfControl() < Temperament.SELF_CONTROL_AT_50;
    }

    private Rations() {}
}
