package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.GroupPits;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where droppings lie (project design). Excrement is no loose item: it is set down as a portion on a ground cell's
 * {@link PoopBlock}. One cell holds {@link #CAPACITY} portions in {@link #TIERS} looks (1 to 2 portions: tier 1, 3 to 4:
 * tier 2, 5 to 6: tier 3, 7 to 8: tier 4). A full cell passes the portion on, in this order: a cell of the preferred set
 * (the waste pit) first, a cell nearby that already holds droppings before a clean one, then the nearer cell; two blocks
 * out at most. Nothing is made or lost: a portion no cell can take stays a real item for the caller to drop.
 */
public final class Poops {
    public static final int CAPACITY = 8, TIERS = 4;
    /** How far (blocks, horizontally) a full cell may pass a portion on. */
    public static final int SPREAD = 2;

    /** {@code overflow}: the portion could not stay on the cell it was meant for. {@code before}/{@code after}: portions on {@code at}. */
    public record Placement(BlockPos at, int before, int after, boolean overflow) {
        public int tierBefore() { return before == 0 ? 0 : tier(before); }
        public int tierAfter() { return tier(after); }
    }

    public static int tier(int portions) { return Math.clamp((portions + 1) / 2, 1, TIERS); }

    // ---- index of loaded heaps (transient, server side, never saved) --------------------------------------------------
    private static final Map<Level, Set<BlockPos>> INDEX = new WeakHashMap<>();
    static void remember(PoopBlockEntity heap) {
        var level = heap.getLevel();
        if (level == null || level.isClientSide) return;
        INDEX.computeIfAbsent(level, key -> new HashSet<>()).add(heap.getBlockPos().immutable());
    }
    static void forget(PoopBlockEntity heap) {
        var level = heap.getLevel();
        if (level == null || level.isClientSide) return;
        var set = INDEX.get(level);
        if (set != null) set.remove(heap.getBlockPos());
    }

    /** The loaded heaps whose cell lies within {@code radius} blocks horizontally and {@code height} vertically of {@code centre}. */
    public static List<PoopBlockEntity> near(ServerLevel level, BlockPos centre, int radius, int height) {
        var set = INDEX.get(level);
        if (set == null || set.isEmpty()) return List.of();
        var found = new ArrayList<PoopBlockEntity>();
        for (var pos : set) {
            if (Math.abs(pos.getX() - centre.getX()) > radius || Math.abs(pos.getZ() - centre.getZ()) > radius
                    || Math.abs(pos.getY() - centre.getY()) > height || !level.hasChunkAt(pos)) continue;
            if (level.getBlockEntity(pos) instanceof PoopBlockEntity heap && !heap.isRemoved()) found.add(heap);
        }
        return found;
    }

    /** Portions lying on exactly these cells. */
    public static int portions(ServerLevel level, Collection<BlockPos> cells) {
        int total = 0;
        for (var cell : cells) if (level.hasChunkAt(cell) && level.getBlockEntity(cell) instanceof PoopBlockEntity heap) total += heap.count();
        return total;
    }
    public static int portions(ServerLevel level, BlockPos cell) { return portions(level, List.of(cell)); }

    /** The fullest heap among these cells (the best meal), nearest first among equals, or null when none holds a portion. */
    public static PoopBlockEntity richest(ServerLevel level, Collection<BlockPos> cells, Vec3 from) {
        PoopBlockEntity best = null;
        for (var cell : cells) {
            if (!level.hasChunkAt(cell) || !(level.getBlockEntity(cell) instanceof PoopBlockEntity heap) || heap.count() == 0) continue;
            if (best == null || heap.count() > best.count()
                    || heap.count() == best.count() && Vec3.atBottomCenterOf(cell).distanceToSqr(from) < Vec3.atBottomCenterOf(best.getBlockPos()).distanceToSqr(from)) best = heap;
        }
        return best;
    }

    // ---- placing -------------------------------------------------------------------------------------------------------
    /** Whether {@code pos} can take one more portion: a heap with room, or a bare supported cell (never another block, water or a shelter's inside). */
    public static boolean canTake(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return false;
        var existing = level.getBlockEntity(pos);
        if (existing instanceof PoopBlockEntity heap) return !heap.full();
        if (existing != null || !level.getFluidState(pos).isEmpty()) return false;
        var state = level.getBlockState(pos);
        if (!state.isAir() && !state.canBeReplaced()) return false;
        return SupplyPiles.supported(level, pos) && !GroupPits.insideShelter(level, pos);
    }

    /**
     * Sets one portion down on {@code origin} or, when that cell is full or unusable, on the best cell around it (see the class
     * comment); {@code prefer} marks the cells to try first (the pit). Null when no cell within {@link #SPREAD} can take it.
     */
    public static Placement place(ServerLevel level, BlockPos origin, ItemStack portion, Predicate<BlockPos> prefer) {
        if (!portion.is(ModItems.WASTE.get()) || portion.getCount() != 1) return null;
        BlockPos target = null;
        boolean overflow = false;
        if (canTake(level, origin)) target = origin.immutable();
        else {
            overflow = true;
            var candidates = new ArrayList<BlockPos>();
            for (int dx = -SPREAD; dx <= SPREAD; dx++) for (int dz = -SPREAD; dz <= SPREAD; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy : new int[]{0, -1, 1}) {
                    var cell = origin.offset(dx, dy, dz);
                    if (canTake(level, cell)) { candidates.add(cell.immutable()); break; }
                }
            }
            candidates.sort(Comparator.<BlockPos>comparingInt(cell -> prefer != null && prefer.test(cell) ? 0 : 1)
                    .thenComparingInt(cell -> level.getBlockEntity(cell) instanceof PoopBlockEntity ? 0 : 1)
                    .thenComparingDouble(cell -> cell.distSqr(origin)));
            if (!candidates.isEmpty()) target = candidates.getFirst();
        }
        if (target == null) return null;
        boolean fresh = !(level.getBlockEntity(target) instanceof PoopBlockEntity);
        if (fresh && !level.setBlock(target, ModBlocks.POOP.get().defaultBlockState(), 3)) return null;
        if (!(level.getBlockEntity(target) instanceof PoopBlockEntity heap)) { level.removeBlock(target, false); return null; }
        int before = heap.count();
        if (!heap.add(portion.copy())) { if (fresh) level.removeBlock(target, false); return null; }
        return new Placement(target, before, heap.count(), overflow);
    }

    private Poops() {}
}
