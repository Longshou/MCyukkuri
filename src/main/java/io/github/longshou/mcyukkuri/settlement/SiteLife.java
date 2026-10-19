package io.github.longshou.mcyukkuri.settlement;

import static io.github.longshou.mcyukkuri.world.SearchBudget.generation;
import static io.github.longshou.mcyukkuri.world.SearchBudget.cancel;

import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.settlement.SettlementData.Site;
import io.github.longshou.mcyukkuri.settlement.site.SitePlan;
import io.github.longshou.mcyukkuri.settlement.site.SiteSlot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;

/** What the behaviour code needs from a planned site: anchors, stores, burrow allocation and founding. Server side only. */
public final class SiteLife {
    /** Per-entity transient continuation. Values retain only immutable search identity, never the weak key. */
    private static final Map<YukkuriEntity, SettleSearch> SETTLEMENTS = new WeakHashMap<>();
    private static final class SettleSearch {
        final UUID site;
        final ResourceKey<Level> dimension;
        final SitePlan plan;
        final boolean allDwellings;
        final List<SiteSlot> slots;
        final long generation;
        int existing, placement, attempts;
        boolean placing;
        SettleSearch(Site site, YukkuriEntity entity) {
            this.site=site.id; dimension=entity.level().dimension(); plan=site.plan(); generation=generation();
            allDwellings=allDwellings(entity);
            var ordered=new ArrayList<SiteSlot>();
            for(var slot:plan.slots())if(slot.dwelling()&&(slot.kind()==SiteSlot.Kind.HOME||allDwellings))ordered.add(slot);
            var anchor=entity.blockPosition();
            ordered.sort(Comparator.comparingDouble(slot->slot.anchor().distSqr(anchor)));
            slots=List.copyOf(ordered);
        }
        boolean current(Site value,YukkuriEntity entity) { return site.equals(value.id)&&dimension.equals(entity.level().dimension())
                &&plan==value.plan()&&allDwellings==allDwellings(entity); }
        private static boolean allDwellings(YukkuriEntity entity) {
            var group=entity.settlement().group();
            return group!=null&&(group.leader==null||entity.getUUID().equals(group.leader));
        }
    }
    public static Site of(YukkuriEntity entity) {
        return entity.level() instanceof ServerLevel level ? of(level, entity.settlement().group()) : null;
    }
    /** The site the group lives on; while it is moving, the site it is moving to (arrivals already belong to it). */
    public static Site of(ServerLevel level, SettlementData.Group group) {
        if (group == null) return null;
        var data = SettlementData.get(level);
        if (group.migrateTo != null) {
            var target = bySpot(data, group.migrateTo);
            if (target != null) return target;
        }
        return group.siteId == null ? null : data.site(group.siteId);
    }
    /** The site whose plaza gathering cell is {@code spot}, if any. */
    public static Site bySpot(SettlementData data, BlockPos spot) {
        for (Site site : data.sites()) if (spot.equals(anchor(site, SiteSlot.Kind.PLAZA))) return site;
        return null;
    }
    public static BlockPos anchor(Site site, SiteSlot.Kind kind) {
        var slot = site == null ? null : site.first(kind);
        return slot == null ? null : slot.anchor();
    }
    /** The pool water block closest to {@code from}. */
    public static BlockPos water(Site site, BlockPos from) {
        BlockPos best = null;
        for (var slot : site.slots(SiteSlot.Kind.WATER)) if (best == null || slot.anchor().distSqr(from) < best.distSqr(from)) best = slot.anchor();
        return best;
    }
    public static List<BlockPos> latrines(Site site) {
        var out = new ArrayList<BlockPos>();
        for (var slot : site.slots(SiteSlot.Kind.LATRINE)) out.add(slot.anchor());
        return out;
    }
    /** The granary pile nearest to {@code from} (loaded, within 64 blocks) that satisfies {@code ok}. */
    public static ForagePileBlockEntity store(ServerLevel level, Site site, BlockPos from, Predicate<ForagePileBlockEntity> ok) {
        return StorePiles.nearest(StorePiles.atSite(level, site), from, ok);
    }
    /** Total portions lying on the granary piles that are currently loaded. */
    public static int stock(ServerLevel level, Site site) {
        int total = 0;
        for (var pile : StorePiles.atSiteIncludingRemoved(level, site)) total += pile.food();
        return total;
    }
    /** The nearest auto-claimable site that nobody lives on within {@code radius} blocks (horizontal). */
    public static Site vacantNear(ServerLevel level, BlockPos pos, int radius) {
        Site best = null;
        double distance = (double)radius * radius;
        for (Site site : SettlementData.get(level).sites()) {
            if (!site.auto || !site.vacant()) continue;
            double dx = site.origin.getX() - pos.getX(), dz = site.origin.getZ() - pos.getZ(), d = dx * dx + dz * dz;
            if (d <= distance) { distance = d; best = site; }
        }
        return best;
    }

    /**
     * Assign each unit (leader / household) a burrow slot: the leader the longhouse, a leaderless village its biggest
     * household, families with children the slots nearest the nursery and the granary, couples the outer ring.
     * Returns plans aligned with {@code units}, or an empty list when the site cannot take them all.
     */
    public static List<ShelterPlacement.Plan> allocate(ServerLevel level, Site site, List<Integer> units, SettlementComposition composition, boolean seedLeader) {
        int n = units.size(), offset = seedLeader ? 1 : 0;
        int leaderUnit = seedLeader ? 0 : composition.leader() ? n - 1 : -1;
        var homes = site.slots(SiteSlot.Kind.HOME);
        var hall = site.first(SiteSlot.Kind.HALL);
        var nursery = anchor(site, SiteSlot.Kind.NURSERY);
        var granary = anchor(site, SiteSlot.Kind.GRANARY);
        var center = site.origin;
        var order = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort(Comparator.<Integer>comparingInt(i -> i == leaderUnit ? -1 : 0).thenComparing(Comparator.<Integer>comparingInt(i -> -units.get(i))).thenComparingInt(i -> i));
        var result = new ShelterPlacement.Plan[n];
        var used = new HashSet<BlockPos>();
        boolean biggestTakesHall = leaderUnit < 0 && hall != null;
        for (int idx : order) {
            int size = units.get(idx), children = idx == leaderUnit ? 0 : composition.households().get(idx - offset).children();
            var candidates = new ArrayList<SiteSlot>();
            if (hall != null && (idx == leaderUnit || biggestTakesHall && idx == order.getFirst())) candidates.add(hall);
            var rest = new ArrayList<SiteSlot>(homes);
            rest.sort(Comparator.comparingDouble(slot -> children > 0
                    ? 2 * dist(slot.anchor(), nursery) + dist(slot.anchor(), granary) : -dist(slot.anchor(), center)));
            candidates.addAll(rest);
            if (hall != null && !candidates.contains(hall)) candidates.add(hall);
            for (var slot : candidates) {
                if (used.contains(slot.anchor()) || size > slot.capacity()) continue;
                var plan = new ShelterPlacement.Plan(slot.anchor(), ShelterBlock.Kind.BURROW, slot.facing());
                if (!ShelterPlacement.canPlace(level, plan) || plan.beds(level).size() < size) continue;
                result[idx] = plan; used.add(slot.anchor());
                break;
            }
            if (result[idx] == null) {
                var why = new StringBuilder();
                for (var slot : candidates) why.append(slot.kind()).append('@').append(slot.anchor().toShortString()).append(" cap").append(slot.capacity())
                        .append(used.contains(slot.anchor()) ? " used" : size > slot.capacity() ? " small" : !ShelterPlacement.canPlace(level,
                                new ShelterPlacement.Plan(slot.anchor(), ShelterBlock.Kind.BURROW, slot.facing())) ? " cannotPlace" : " fewBeds").append("; ");
                SettlementSpawner.failed("household of " + size + " found no burrow among: " + why);
                return List.of();
            }
        }
        return List.of(result);
    }
    private static double dist(BlockPos a, BlockPos b) { return b == null ? 0 : Math.sqrt(a.distSqr(b)); }

    /**
     * An entity of a site group without a burrow: claim an existing burrow core of the site, or set a core into a free
     * planned burrow. At most three placements are tried per search, one bounded slice per admission.
     */
    public static Result settle(YukkuriEntity entity, HabitatLife habitat, Site site) {
        if (!(entity.level() instanceof ServerLevel level)) return Result.NOT_FOUND;
        if (site.plan()==null) { clearSettle(level,entity); return Result.NOT_FOUND; }
        var search=SETTLEMENTS.get(entity);
        if(search!=null&&!search.current(site,entity)) { clearSettle(level,entity); search=null; }
        if(search==null) { search=new SettleSearch(site,entity); SETTLEMENTS.put(entity,search); }
        var lease=SearchBudget.request(level,entity,Purpose.SETTLE,search.generation,SearchBudget.SITE_SETTLE_SLICE);
        if(lease==null)return Result.DEFERRED;
        Result result;
        try(lease;var scope=lease.bind(entity)){result=settleNow(entity,habitat,site,search);}
        if(result!=Result.DEFERRED)clearSettle(level,entity);
        return result;
    }
    private static void clearSettle(ServerLevel level,YukkuriEntity entity) {
        var search=SETTLEMENTS.remove(entity);
        if(search!=null)cancel(level.getServer(),search.dimension,entity,Purpose.SETTLE,search.generation);
    }
    static void clearSettle(YukkuriEntity entity) {
        if(entity.level() instanceof ServerLevel level)clearSettle(level,entity);
    }
    private static Result settleNow(YukkuriEntity entity, HabitatLife habitat, Site site, SettleSearch search) {
        if (!(entity.level() instanceof ServerLevel level) || !search.current(site,entity)) return Result.NOT_FOUND;
        var slots = search.slots;
        // Relatives first: an existing burrow of the site takes a family member only if the family already lives there.
        if(!search.placing && search.existing<slots.size()) {
            var slot=slots.get(search.existing++);
            SearchBudget.probe(level);
            if (slot.anchor().distSqr(entity.blockPosition()) <= 48 * 48 && level.hasChunkAt(slot.anchor())
                    && level.getBlockEntity(slot.anchor()) instanceof ShelterBlockEntity core && habitat.choose(core)) return Result.FOUND;
            return search.existing<slots.size()?Result.DEFERRED:beginPlacement(search);
        }
        search.placing=true;
        if(search.placement<slots.size()) {
            var slot=slots.get(search.placement++);
            SearchBudget.probe(level);
            if (slot.anchor().distSqr(entity.blockPosition()) > 48 * 48 || !level.hasChunkAt(slot.anchor())
                    || level.getBlockEntity(slot.anchor()) instanceof ShelterBlockEntity)
                return search.placement<slots.size()?Result.DEFERRED:Result.NOT_FOUND;
            if (++search.attempts > 3) return Result.NOT_FOUND;
            var plan = new ShelterPlacement.Plan(slot.anchor(), ShelterBlock.Kind.BURROW, slot.facing());
            var placed = ShelterPlacement.place(level, plan);
            if (placed == null) return Result.DEFERRED;
            if (habitat.choose(placed.shelter())) return Result.FOUND;
            placed.rollback(level);
            return Result.DEFERRED;
        }
        return Result.NOT_FOUND;
    }
    private static Result beginPlacement(SettleSearch search) {
        search.placing=true;
        return Result.DEFERRED;
    }

    /** Called once the group exists: marks the site lived in and puts the first stock on the granary piles. */
    public static void found(ServerLevel level, Site site, SettlementData.Group group, int population) {
        site.state = SettlementData.SiteState.FOUNDED;
        site.group = group.id;
        site.failures = 0;
        SettlementData.get(level).setDirty();
        boolean autumn = site.palette <= 1;
        int portions = Math.min(64, (int)Math.round((population * (autumn ? 5 : 3) / 2 + 4) * site.plan().profile().stock));
        for (var slot : site.slots(SiteSlot.Kind.STORE)) {
            var at = slot.anchor();
            if (!level.hasChunkAt(at)) continue;
            if (!(level.getBlockEntity(at) instanceof ForagePileBlockEntity)
                    && level.getBlockState(at).isAir() && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP))
                level.setBlock(at, ModBlocks.FORAGE_PILE.get().defaultBlockState(), 3);
            if (level.getBlockEntity(at) instanceof ForagePileBlockEntity pile) {
                pile.setApproach(slot.facing());
                pile.returnFood(new ItemStack(Items.SHORT_GRASS, portions));
            }
        }
    }

    /** Trees that grew in front of a door after the site was generated: logs and leaves in the three-block lane are cleared. */
    public static void clearLanes(ServerLevel level, Site site) {
        var layout = site.plan();
        if (layout == null) return;
        for (var slot : layout.slots()) {
            if (!slot.dwelling() && slot.kind() != SiteSlot.Kind.GRANARY) continue;
            var door = slot.kind() == SiteSlot.Kind.GRANARY ? slot.anchor() : slot.door();
            for (int k = 0; k <= 3; k++) for (int side = k == 0 ? 0 : -1; side <= (k == 0 ? 0 : 1); side++) for (int y = 0; y <= 2; y++) {
                var pos = door.relative(slot.facing(), k).relative(slot.facing().getClockWise(), side).above(y);
                if (!level.hasChunkAt(pos)) continue;
                var state = level.getBlockState(pos);
                if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }
    private SiteLife() {}
}
