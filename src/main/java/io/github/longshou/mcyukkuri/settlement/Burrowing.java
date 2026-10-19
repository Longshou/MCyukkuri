package io.github.longshou.mcyukkuri.settlement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a yukkuri can do to terrain to make a home (project design): scrape a small hollow into soft ground, one block at a time, and
 * widen an existing one. It cannot pile up walls or roofs, cut stone or wood, or open a hollow without solid earth
 * above it. Everything here only plans; {@code HabitatLife} does the (slow) digging and asks {@code mobGriefing} first.
 *
 * <p>A planned hollow is a short passage into the face of a mound or slope with a small chamber at its end:
 * the doorway cell, a second passage cell, then a chamber one cell wider than the passage on each side, and so on, until
 * the wanted number of cells is reached. The doorway remains one block high; every other interior floor cell is cleared
 * one block higher. The second layer still needs an earth roof and earth outer neighbours, so a hollow never breaks
 * through to the open air.
 */
public final class Burrowing {
    /** Fewest cells that make a home: doorway, passage and a chamber with room for a nest and a store. */
    public static final int MIN_CELLS = NaturalShelter.MIN_CELLS_FOR_STORE;
    /** Ticks of work for one block of this ground. Slow on purpose: the animals only scrape with their bodies. */
    public static int digTicks(BlockState state) {
        if (state.is(BlockTags.SAND)) return 60;
        if (state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY)) return 90;
        return 120;
    }
    /** A soft block a yukkuri can scrape away: loose soil, sand, gravel or clay, never stone, wood, ore or anything with a block entity. */
    public static boolean soft(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos) || level.getBlockEntity(pos) != null || !level.getFluidState(pos).isEmpty()) return false;
        var state = level.getBlockState(pos);
        return state.is(BlockTags.DIRT) && !state.is(Blocks.MOSS_BLOCK) || state.is(BlockTags.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.CLAY);
    }
    /** Walls must be what the survey counts as a bank, so a finished hollow is recognised as a home. */
    private static boolean wall(Level level, BlockPos pos) { return NaturalShelter.earth(level, pos); }
    /** A collision-free cell that does not need digging. */
    private static boolean clear(Level level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getFluidState(pos).isEmpty()
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
    /** A floor cell that can hold a hollow. The doorway keeps its earth lintel; other cells clear a second interior layer. */
    private static boolean fits(Level level, BlockPos cell, boolean doorway) {
        if (!level.hasChunkAt(cell) || !level.getFluidState(cell.below()).isEmpty() || !level.getFluidState(cell).isEmpty()) return false;
        if (!level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP)) return false;
        if (!soft(level, cell) && !NaturalShelter.stand(level, cell)) return false;
        var upper = cell.above();
        if (doorway) return NaturalShelter.earth(level, upper) && !level.getBlockState(upper).is(BlockTags.LOGS);
        return (soft(level, upper) || clear(level, upper)) && NaturalShelter.earth(level, upper.above())
                && !level.getBlockState(upper.above()).is(BlockTags.LOGS);
    }
    /** The upper layer may only join other upper interior cells; all other sides stay banked. */
    private static boolean highInteriorClosed(Level level, BlockPos cell, Set<BlockPos> highFloors) {
        for (var direction : Direction.Plane.HORIZONTAL) {
            if (highFloors.contains(cell.relative(direction))) continue;
            if (!wall(level, cell.above().relative(direction))) return false;
        }
        return true;
    }

    /** A hollow to scrape: {@code cells} in digging order (the first is the doorway), reached from {@code mouth} in direction {@code dig}. */
    public record Plan(BlockPos mouth, Direction dig, List<BlockPos> cells) {
        public BlockPos doorway() { return cells.getFirst(); }
        /** The facing a shelter core at the doorway needs: from the doorway cell out to the mouth. */
        public Direction facing() { return dig.getOpposite(); }
        /** The standing cell next to {@code cell} from which it is dug: the previous cell of the passage or chamber. */
        public BlockPos reach(BlockPos cell) {
            // The second layer is scraped from its already opened floor cell, never while standing in a ceiling block.
            if (cell.getY() > doorway().getY() && cells.contains(cell.below())) return cell.below();
            int index = cells.indexOf(cell);
            if (index <= 0) return mouth;
            for (var side : Direction.Plane.HORIZONTAL) {
                var near = cell.relative(side);
                if (cells.indexOf(near) >= 0 && cells.indexOf(near) < index) return near;
            }
            return mouth;
        }
    }

    /** A place to dig from: standing room in the open, never the inside of a cave, whether registered as somebody's home or not. */
    static boolean openAir(Level level, BlockPos pos) {
        return NaturalShelter.stand(level, pos) && !NaturalShelter.roof(level, pos) && ShelterBlock.shelter(level, pos) == null;
    }

    /** The cells a hollow of {@code want} cells would have at {@code mouth}, or null if the ground does not allow at least {@link #MIN_CELLS}. */
    public static Plan plan(Level level, BlockPos mouth, Direction dig, int want) {
        if (!openAir(level, mouth)) return null;
        var order = new ArrayList<BlockPos>();
        var spine = new ArrayList<BlockPos>();
        var side = dig.getClockWise();
        var at = mouth;
        for (int row = 1; row <= 7; row++) {
            at = at.relative(dig);
            spine.add(at);
            order.add(at);
            if (row >= 3) { order.add(at.relative(side)); order.add(at.relative(side.getOpposite())); }
            if (order.size() >= want + 2) break;
        }
        var cells = new ArrayList<BlockPos>(order);
        // Drop what does not fit; a broken passage ends the hollow there.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var cell : List.copyOf(cells)) {
                boolean spineCell = spine.contains(cell);
                if (!fits(level, cell, cell.equals(spine.getFirst())) || spineCell && spine.indexOf(cell) > 0 && !cells.contains(spine.get(spine.indexOf(cell) - 1))) { cells.remove(cell); changed = true; continue; }
                // Closed walls: every neighbour outside the hollow is earth, except the mouth itself in front of the doorway.
                for (var direction : Direction.Plane.HORIZONTAL) {
                    var neighbour = cell.relative(direction);
                    if (cells.contains(neighbour) || neighbour.equals(mouth)) continue;
                    if (!wall(level, neighbour)) { cells.remove(cell); changed = true; break; }
                }
            }
        }
        // Keep the floor digging order, then open the upper layer everywhere inside except the one-block doorway.
        var floors = new ArrayList<BlockPos>();
        for (var cell : order) if (cells.contains(cell) && floors.size() < want) floors.add(cell);
        if (floors.size() < MIN_CELLS || !floors.contains(spine.getFirst())) return null;
        var highFloors = new HashSet<BlockPos>(floors);
        highFloors.remove(spine.getFirst());
        if (highFloors.stream().anyMatch(cell -> !highInteriorClosed(level, cell, highFloors))) return null;
        var result = new ArrayList<BlockPos>(floors);
        for (var cell : floors) if (!cell.equals(spine.getFirst())) result.add(cell.above());
        return new Plan(mouth.immutable(), dig, List.copyOf(result));
    }

    /**
     * The next block to scrape when a family has outgrown its cave, as a plan of one cell; null if no soft wall can be opened without
     * breaking through. The new cell touches a cell of the cave other than its doorway, so the way in stays as it was.
     */
    public static Plan extension(Level level, ShelterBlockEntity home) {
        var cells = new HashSet<BlockPos>(home.beds());
        cells.add(home.getBlockPos());
        if (home.pileCell() != null) cells.add(home.pileCell());
        if (cells.size() >= NaturalShelter.MAX_CELLS) return null;
        BlockPos best = null, from = null;
        double bestScore = -1;
        for (var cell : cells) {
            if (cell.equals(home.getBlockPos())) continue;
            for (var direction : Direction.Plane.HORIZONTAL) {
                var next = cell.relative(direction);
                if (cells.contains(next) || next.equals(home.entrance()) || !soft(level, next) || !fits(level, next, false)) continue;
                if (Math.abs(next.getX() - home.getBlockPos().getX()) > NaturalShelter.RADIUS || Math.abs(next.getZ() - home.getBlockPos().getZ()) > NaturalShelter.RADIUS) continue;
                if (next.distManhattan(home.getBlockPos()) < 2) continue;
                boolean closed = true;
                for (var around : Direction.Plane.HORIZONTAL) {
                    var neighbour = next.relative(around);
                    if (!cells.contains(neighbour) && !wall(level, neighbour)) closed = false;
                }
                if (!closed) continue;
                var highFloors = new HashSet<BlockPos>();
                for (var existing : cells)
                    if (clear(level, existing.above()) && wall(level, existing.above(2))) highFloors.add(existing);
                // An open pit or an open-sided shelf is not a second interior layer to join.
                boolean removed;
                do {
                    removed = false;
                    for (var existing : List.copyOf(highFloors)) if (!highInteriorClosed(level, existing, highFloors)) {
                        highFloors.remove(existing); removed = true;
                    }
                } while (removed);
                highFloors.add(next);
                if (!highInteriorClosed(level, next, highFloors)) continue;
                double score = next.distSqr(home.getBlockPos());
                if (score > bestScore) { best = next.immutable(); from = cell; bestScore = score; }
            }
        }
        if (best == null) return null;
        return new Plan(from, Direction.fromDelta(best.getX() - from.getX(), 0, best.getZ() - from.getZ()), List.of(best, best.above()));
    }

    /** True once the block at {@code cell} is open standing room (dug, or never solid). */
    public static boolean open(Level level, BlockPos cell) { return NaturalShelter.stand(level, cell); }

    /** True once a planned block has been cleared, including an upper interior cell with no floor of its own. */
    public static boolean cleared(Level level, BlockPos cell) { return clear(level, cell); }

    /** Candidate openings for a new hollow near {@code origin}: standing cells with soft ground straight ahead. */
    public static List<BlockPos> faces(Level level, BlockPos origin, int radius) {
        var result = new ArrayList<BlockPos>();
        Set<BlockPos> seen = new HashSet<>();
        for (var pos : BlockPos.betweenClosed(origin.offset(-radius, -2, -radius), origin.offset(radius, 2, radius))) {
            if (!level.hasChunkAt(pos) || !openAir(level, pos)) continue;
            for (var dir : Direction.Plane.HORIZONTAL) {
                var ahead = pos.relative(dir);
                if (soft(level, ahead) && seen.add(pos.immutable())) { result.add(pos.immutable()); break; }
            }
        }
        return result;
    }
    private Burrowing() {}
}
