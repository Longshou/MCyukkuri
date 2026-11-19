package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.world.SearchBudget;
import io.github.longshou.mcyukkuri.world.SearchBudget.Cost;
import io.github.longshou.mcyukkuri.world.SearchBudget.Purpose;
import io.github.longshou.mcyukkuri.world.SearchBudget.Result;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Claims loaded complete nests first. Founders then rank the surveyed canopy, checking flight in score order. */
final class PredatorNestSearch {
    private static final int RADIUS = 24, SLICE = 2048, ROUTES = 8;
    private final ServerLevel level;
    private final BlockPos origin;
    private final long generation = SearchBudget.generation();
    private final Iterator<BlockPos> nests;
    private final Iterator<BlockPos> cells;
    private final Iterator<BlockPos> pathColumns;
    private final ArrayList<CanopySite> sites = new ArrayList<>();
    private final ArrayList<BlockPos> paths = new ArrayList<>();
    private Iterator<CanopySite> ranked;
    private CanopySite selected;
    private long lastSlice = Long.MIN_VALUE;

    PredatorNestSearch(ServerLevel level, YukkuriEntity body, boolean founding) {
        this.level = level;
        origin = body.blockPosition();
        nests = completeNests().iterator();
        var ground = origin.atY(CanopySite.groundY(level, origin));
        cells = founding ? BlockPos.betweenClosed(ground.offset(-RADIUS, CanopySite.MIN_HEIGHT, -RADIUS),
                ground.offset(RADIUS, CanopySite.MAX_HEIGHT, RADIUS)).iterator() : null;
        pathColumns = founding ? BlockPos.betweenClosed(origin.offset(-2 * RADIUS, 0, -2 * RADIUS),
                origin.offset(2 * RADIUS, 0, 2 * RADIUS)).iterator() : null;
    }

    private ArrayList<BlockPos> completeNests() {
        var found = new ArrayList<BlockPos>();
        for (int z = (origin.getZ() - RADIUS) >> 4; z <= (origin.getZ() + RADIUS) >> 4; z++) {
            for (int x = (origin.getX() - RADIUS) >> 4; x <= (origin.getX() + RADIUS) >> 4; x++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) continue;
                for (var pos : chunk.getBlockEntitiesPos())
                    if (pos.distSqr(origin) <= RADIUS * RADIUS && chunk.getBlockState(pos).is(ModBlocks.PREDATOR_NEST.get()))
                        found.add(pos);
            }
        }
        found.sort(Comparator.comparingDouble(origin::distSqr).thenComparing(Comparator.naturalOrder()));
        return found;
    }

    boolean current(ServerLevel world, YukkuriEntity body) {
        return level == world && (cells != null || origin.distSqr(body.blockPosition()) <= 16);
    }

    void cancel(YukkuriEntity body) { SearchBudget.cancel(level, body, Purpose.PREDATOR_NEST, generation); }

    CanopySite selected() { return selected; }

    Result advance(YukkuriEntity body, Predicate<BlockPos> refused, ToDoubleFunction<BlockPos> penalty) {
        if (lastSlice == level.getGameTime()) return Result.DEFERRED;
        var lease = SearchBudget.request(level, body, Purpose.PREDATOR_NEST, generation, new Cost(SLICE, 0, ROUTES * 3));
        if (lease == null) return Result.DEFERRED;
        lastSlice = level.getGameTime();
        try (lease; var scope = lease.bind(body)) {
            int probes = SLICE, routes = ROUTES;
            while (nests.hasNext() && probes > 0 && routes > 0) {
                var pos = nests.next();
                SearchBudget.probe(level); probes--;
                if (refused.test(pos) || !level.hasChunkAt(pos) || !level.getBlockState(pos).is(ModBlocks.PREDATOR_NEST.get())
                        || !(level.getBlockEntity(pos) instanceof CareBlockEntity bed) || !bed.canClaim(body)) continue;
                routes--;
                if (body.life().assignHome(GlobalPos.of(level.dimension(), pos))) return Result.FOUND;
            }
            if (nests.hasNext()) return Result.DEFERRED;
            if (cells == null) return Result.NOT_FOUND;
            while (cells.hasNext() && probes > 0) {
                var pos = cells.next();
                SearchBudget.probe(level); probes--;
                if (refused.test(pos) || !level.hasChunkAt(pos) || !CanopySite.buildable(level.getBlockState(pos))) continue;
                var site = CanopySite.inspect(level, body, pos);
                if (site != null) sites.add(site);
            }
            if (cells.hasNext()) return Result.DEFERRED;
            if (sites.isEmpty()) return Result.NOT_FOUND;
            while (pathColumns.hasNext() && probes > 0) {
                var pos = pathColumns.next();
                SearchBudget.probe(level); probes--;
                if (pathOrFarm(level, pos)) paths.add(pos.immutable());
            }
            if (pathColumns.hasNext()) return Result.DEFERRED;
        if (ranked == null) {
            double timid = body.life().personality().temperament().timid();
            ranked = sites.stream().map(site -> rank(site, timid, penalty)).sorted(Ranked.ORDER).map(Ranked::site).iterator();
        }
            while (ranked.hasNext() && routes-- > 0) {
                var site = CanopySite.inspect(level, body, ranked.next().cell());
                if (site != null && CanopySite.buildable(level.getBlockState(site.cell())) && site.reachable(body)) {
                    selected = site;
                    return Result.FOUND;
                }
            }
            return ranked.hasNext() ? Result.DEFERRED : Result.NOT_FOUND;
        }
    }

    private Ranked rank(CanopySite site, double timid, ToDoubleFunction<BlockPos> penalty) {
        var cell = site.cell();
        double distance = Math.sqrt(origin.distSqr(cell));
        double score = NestSiteScore.score(site.height(), site.cover(), pathDistance(level, cell, paths), distance, timid);
        return new Ranked(site, level.getBlockState(cell).is(ModBlocks.PREDATOR_NEST_FRAME.get()), score - penalty.applyAsDouble(cell), distance);
    }

    static boolean pathOrFarm(ServerLevel level, BlockPos column) {
        if (!level.hasChunkAt(column)) return false;
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        int y = CanopySite.groundY(level, column.atY(top));
        var state = level.getBlockState(column.atY(y - 1));
        return state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND);
    }

    static double pathDistance(ServerLevel level, BlockPos site, Iterable<BlockPos> paths) {
        double nearest = RADIUS * RADIUS + 1;
        for (var path : paths) nearest = Math.min(nearest, site.atY(path.getY()).distSqr(path));
        double distance = nearest <= RADIUS * RADIUS ? Math.sqrt(nearest) : Double.POSITIVE_INFINITY;
        return Math.min(distance, level.sectionsToVillage(SectionPos.of(site)) * 16.0);
    }

    private record Ranked(CanopySite site, boolean frame, double score, double distance) {
        static final Comparator<Ranked> ORDER = Comparator.comparing(Ranked::frame).reversed()
                .thenComparing((a, b) -> NestSiteScore.compare(b.score, b.distance, a.score, a.distance));
    }
}
