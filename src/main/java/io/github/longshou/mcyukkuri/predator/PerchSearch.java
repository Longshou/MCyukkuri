package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Sample nearby branches densely, then rings out to 24 blocks. Test up to eight routes in score order. */
final class PerchSearch {
    private record Branch(Vec3 feet, double score) {}

    static Vec3 find(YukkuriEntity body, BlockPos centre, Long last, Long previous, boolean roost) {
        var level = (ServerLevel) body.level();
        var zones = body.predator().foreignTerritories(centre, 24);
        boolean midday = PerchScore.midday(level.getDayTime(), !level.isRaining());
        var columns = new HashSet<BlockPos>();
        columns.add(centre);
        double rotation = body.getRandom().nextDouble() * Math.PI / 8;
        for (int radius : new int[]{1, 2, 4, 8, 12, 16, 20, 24}) {
            for (int i = 0; i < 16; i++) {
                double angle = rotation + i * Math.PI / 8;
                int x = (int)Math.round(radius * Math.cos(angle)), z = (int)Math.round(radius * Math.sin(angle));
                if (x*x + z*z <= 24*24) columns.add(centre.offset(x, 0, z));
            }
        }
        var branches = new ArrayList<Branch>();
        for (var column : columns) {
            if (!level.hasChunkAt(column) || !level.getWorldBorder().isWithinBounds(column)) continue;
            var top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, column);
            // Look below the crown as well: a shaded shelf can be several blocks below the highest leaf.
            for (int depth = 0; depth <= PerchScore.MAX_HEIGHT; depth++) {
                var pos = top.below(depth);
                var support = level.getBlockState(pos.below());
                if (!CanopySite.tree(support)) {
                    if (support.blocksMotion()) break;
                    continue;
                }
                if (!level.getBlockState(pos).isAir()) continue;
                int height = pos.getY() - CanopySite.groundY(level, pos, PerchScore.MAX_HEIGHT);
                boolean shaded = body.life().covered(pos);
                if (roost && !shaded) continue;
                var feet = body.predator().landingFeet(pos);
                if (feet == null || !PredatorLife.outsideTerritories(zones, feet)) continue;
                int visible = view(level, body, pos, feet);
                if (PerchScore.eligible(height, visible, pos.asLong(), last, previous))
                    branches.add(new Branch(feet, PerchScore.score(height, visible, midday, shaded)));
            }
        }
        return branches.stream().sorted(Comparator.comparingDouble(Branch::score).reversed()
                        .thenComparingDouble(branch -> body.distanceToSqr(branch.feet())))
                .limit(8).map(Branch::feet).filter(body.predator()::canDepart)
                .filter(body.predator()::canFlyToFeet).findFirst().orElse(null);
    }

    private static int view(ServerLevel level, YukkuriEntity body, BlockPos pos, Vec3 feet) {
        int visible = 0;
        var eye = feet.add(0, body.getEyeHeight(), 0);
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            var column = pos.offset((int)Math.round(12 * Math.cos(angle)), 0, (int)Math.round(12 * Math.sin(angle)));
            if (!level.hasChunkAt(column)) continue;
            int ground = CanopySite.groundY(level, column, PerchScore.MAX_HEIGHT);
            if (pos.getY() - ground > PerchScore.MAX_HEIGHT) continue;
            var target = Vec3.atBottomCenterOf(column.atY(ground)).add(0, .5, 0);
            if (level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body))
                    .getType() == HitResult.Type.MISS) visible++;
        }
        return visible;
    }
}
