package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyLife;
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
 * Sharing out the common store by the number of mouths (按饅數分取所需, komicolle 40720 No.11518859), and what changes
 * in a famine. Project design:
 * <ul>
 * <li>Once a day, at the evening gathering (day time 11000-13000), the leader (on a planned site: standing at the plaza)
 *     shares out the common store. Each household's need is its mouths times {@link #PER_MOUTH} portions; a household
 *     whose own pile already holds two days of that gets nothing ("take what is needed").</li>
 * <li>Mouths are weighted: adult 1, child (子油) 0.75, baby (赤油) 0.5, elder 0.75; pregnant +0.5, a parent with a
 *     baby still on mash +0.25, sick or wounded +0.25; a ration-cut offender counts half, an ununn slave a quarter,
 *     an outcast nothing. Babies, pregnant mothers, the sick and the young are the priority share.</li>
 * <li>When the store cannot cover the wants (shortage) the priority share is served first and the rest pro rata. When it
 *     covers less than a third (or is empty while members go hungry) the community is in famine: the leader hunts twice
 *     a day, fathers may forage for their own families again, nobody may court, theft weighs one step heavier.</li>
 * <li>In autumn two days of the whole community's need stay in the store for the winter.</li>
 * <li>A selfish leader (selfishness &ge; 65, self-control &lt; 50) takes half again for its own household; the others feel
 *     it ({@code unfair_ration}). That is the "unfairness" the lecture says rules exist to prevent.</li>
 * </ul>
 * Households fetch their share themselves (HabitatLife RATION errand: a real walk to the store, a real mouthful of up to
 * {@link #FETCH_MAX} portions, deposited on the own pile); a hungry member may also eat one ration portion at the store.
 * Taking from the store without a ration left is theft ({@link CommunityRules.Offence#THEFT}).
 */
public final class Rations {
    public static final float PER_MOUTH = 2f;
    public static final int FETCH_MAX = 4;
    static final int START = 11000, END = 13000;

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
        if (m.getHealth() < m.getMaxHealth() * .6f || m.body().wound() > 0 || m.body().infection() > 0) extra += .25f;
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

    // ---- the daily distribution -------------------------------------------------------------------------------------
    static void tickLeader(YukkuriEntity leader, SettlementData.Group group) {
        if (!(leader.level() instanceof ServerLevel level) || !CommunityRules.rule(group, Commons.SHARE_BY_MOUTH) || Migration.moving(group)) return;
        long day = level.getDayTime() / 24000, time = level.getDayTime() % 24000;
        if (group.commons.rationDay == day || time < START || time >= END) return;
        if (leader.life().isSleeping() || leader.isPanicking()) return;
        if (SiteLife.of(level, group) != null && leader.distanceToSqr(Vec3.atBottomCenterOf(group.center)) > 16 * 16) return;
        distribute(level, group, leader);
    }

    private static final class Household {
        final ShelterBlockEntity home;
        final List<YukkuriEntity> people = new ArrayList<>();
        float weight, priority;
        int need, want, own, quota;
        boolean leaders;
        Household(ShelterBlockEntity home) { this.home = home; }
    }

    /** Shares out the common store now; returns a one-line summary. {@code leader} may be null (test command). */
    public static String distribute(ServerLevel level, SettlementData.Group group, YukkuriEntity leader) {
        var commons = group.commons;
        long now = level.getGameTime(), day = level.getDayTime() / 24000;
        var members = level.getEntitiesOfClass(YukkuriEntity.class, new AABB(group.center).inflate(64),
                e -> e.isAlive() && group.id.equals(e.settlement().groupId()));
        Map<Long, Household> homes = new LinkedHashMap<>();
        boolean hungry = false;
        for (var m : members) {
            if (m.life().nutrition() < 30) hungry = true;
            var home = m.habitat().shelter();
            if (home == null) continue;
            var mouth = mouth(m, group, now);
            if (mouth.weight() <= 0) continue;
            var h = homes.computeIfAbsent(key(home), k -> new Household(home));
            h.people.add(m); h.weight += mouth.weight(); h.priority += mouth.priority();
            if (m.getUUID().equals(group.leader)) h.leaders = true;
        }
        int stock = CommonStore.stock(level, group);
        float totalWeight = 0;
        for (var h : homes.values()) totalWeight += h.weight;
        int reserve = Seasons.on(level, group.center, Season.AUTUMN) ? (int)Math.ceil(totalWeight * PER_MOUTH * 2) : 0;
        int available = Math.max(0, stock - reserve);
        // A selfish leader takes half again for its own household [original].
        boolean unfair = false;
        if (leader != null) {
            var p = leader.life().personality();
            unfair = p.selfishness() >= 65 && p.selfControl() < 50;
        }
        int wanted = 0;
        for (var h : homes.values()) {
            h.need = (int)Math.ceil(h.weight * PER_MOUTH);
            h.own = h.home.food();
            h.want = Math.clamp(2L * h.need - h.own, 0, h.need);
            if (unfair && h.leaders) h.want = (int)Math.ceil(h.want * 1.5);
            wanted += h.want;
        }
        int famine;
        if (commons.famineOverride >= 0) famine = commons.famineOverride;
        else if (wanted == 0) famine = 0;
        else {
            float ratio = available / (float)wanted;
            famine = ratio < 1 / 3f || available == 0 && hungry ? 2 : ratio < .75f ? 1 : 0;
        }
        int left = available;
        var list = new ArrayList<>(homes.values());
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
        commons.rations.clear();
        int given = 0;
        for (var h : list) {
            var r = new Commons.Ration();
            r.mouths = h.people.size(); r.weight = Math.round(h.weight * 100) / 100f; r.allotted = r.left = h.quota; r.own = h.own;
            r.note = h.want == 0 ? "own_store_enough" : h.quota < h.want ? "short" : "full";
            commons.rations.put(key(h.home), r);
            given += h.quota;
        }
        int before = commons.famine;
        commons.famine = famine;
        commons.rationDay = day; commons.rationTime = now; commons.rationStock = stock; commons.rationGiven = given; commons.rationWanted = wanted;
        if (famine >= 2 && before < 2) {
            commons.famineSince = now;
            commons.note(now, "famine", leader == null ? null : leader.getUUID(), "stock " + stock + " wanted " + wanted);
            if (leader != null) leader.showReaction("teary", "famine", 100);
            for (var m : members) m.mind().add(5, "famine");
        } else if (famine < 2 && before >= 2) {
            commons.note(now, "famine_over", leader == null ? null : leader.getUUID(), "stock " + stock);
            for (var m : members) m.mind().add(-3, "famine_over");
        }
        if (leader != null && given > 0) leader.showReaction("happy", "ration_give", 80);
        for (var h : list) {
            if (h.quota <= 0) continue;
            for (var m : h.people) {
                if (unfair && !h.leaders && m.family().adult()) m.mind().add(3, "unfair_ration");
                else m.mind().add(-2, "ration");
            }
        }
        String summary = "day " + day + " households " + list.size() + " given " + given + "/" + wanted + " stock " + stock
                + (reserve > 0 ? " reserve " + reserve : "") + " famine " + famine + (unfair ? " unfair" : "");
        commons.note(now, "ration", leader == null ? null : leader.getUUID(), summary);
        SettlementData.get(level).setDirty();
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
        if (own != null && own.getBlockPos().equals(pile.getBlockPos())) { r.left = 0; r.note = "kept_at_home"; return null; }
        if (home.foodSpace(pile.peek(stack -> entity.life().acceptsFood(stack))) <= 0) return null;
        r.fetcher = entity.getUUID(); r.fetchUntil = now + 1200;
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
        if (nutrition < 15) return p.selfControl() < 70;
        if (famine && nutrition < 25) return p.selfControl() < 60;
        return p.selfishness() >= 60 && p.selfControl() < 50;
    }

    private Rations() {}
}
