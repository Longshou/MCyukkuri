package io.github.longshou.mcyukkuri.entity;

import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.life.TravelRange;
import java.util.Set;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/** Dry-area routines may route around an exposed gap; hunger and danger can release the preference. */
final class YukkuriGroundNavigation extends GroundPathNavigation {
    YukkuriGroundNavigation(YukkuriEntity animal, Level level) { super(animal, level); }

    @Override protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy) {
        return super.createPath(targets, margin, above, accuracy, TravelRange.PATH_RANGE);
    }
    @Override protected PathFinder createPathFinder(int nodes) {
        nodeEvaluator = new WalkNodeEvaluator() {
            private boolean keepDry, keepHome;
            @Override public void prepare(PathNavigationRegion region, Mob mob) {
                super.prepare(region, mob);
                keepDry = mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal);
                keepHome = mob instanceof YukkuriEntity animal && TravelRange.keepBabyHome(animal);
            }
            @Override protected PathType getCachedPathType(int x, int y, int z) {
                PathType type = super.getCachedPathType(x, y, z);
                if (keepHome && mob instanceof YukkuriEntity animal
                        && !TravelRange.babyDestination(animal,new BlockPos(x,y,z))) return PathType.BLOCKED;
                if (keepDry && mob instanceof YukkuriEntity animal
                        && Reactions.Weather.rainAt(animal, new BlockPos(x, y, z))) return PathType.BLOCKED;
                return type;
            }
        };
        nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(nodeEvaluator, Math.max(nodes, TravelRange.PATH_RANGE * 16));
    }

    @Override public void tick() {
        if (mob instanceof YukkuriEntity animal && Reactions.Weather.keepDry(animal) && path != null && !path.isDone()) {
            var next = path.getNextNodePos();
            if (Reactions.Weather.rainAt(animal, next)) stop();
        }
        super.tick();
    }
}
