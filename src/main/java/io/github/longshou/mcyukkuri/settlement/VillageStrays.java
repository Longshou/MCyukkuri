package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.TownAware;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class VillageStrays {
    private record Village(List<BlockPos> beds, int vacancies) {}

    private static Stream<BlockPos> nearby(PoiManager pois, ResourceKey<PoiType> kind, BlockPos centre) {
        return pois.getInSquare(type -> type.is(kind), centre, StrayDensity.RADIUS, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(p -> StrayDensity.inFootprint(p.getX() - centre.getX(), p.getZ() - centre.getZ()));
    }

    private static Village survey(ServerLevel level, BlockPos pos) {
        var pois = level.getPoiManager();
        var centre = nearby(pois, PoiTypes.MEETING, pos).min(Comparator.comparingDouble(pos::distSqr)).orElse(pos);
        var beds = nearby(pois, PoiTypes.HOME, centre).toList();
        int target = StrayDensity.target(beds.size(), level.isNight(),
                level.isRaining() && level.getBiome(centre).value().hasPrecipitation());
        var streets = new AABB(centre).inflate(StrayDensity.RADIUS + 12)
                .setMinY(level.getMinBuildHeight()).setMaxY(level.getMaxBuildHeight());
        int residents = level.getEntitiesOfClass(YukkuriEntity.class, streets,
                y -> y.isAlive() && y.status().identity() == Identity.STRAY).size();
        return new Village(beds, target - residents);
    }

    private static boolean admits(ServerLevel level, BlockPos pos, int vacancies, boolean existing) {
        if (vacancies <= 0) return false;
        int nearby = level.getEntitiesOfClass(YukkuriEntity.class, new AABB(pos).inflate(32)).size();
        return StrayDensity.admits(vacancies, nearby, FamilyData.get(level).population(level, pos),
                YukkuriConfig.LOCAL_SPAWN_CAP.get(), FamilyConfig.POPULATION_CAP.get(), existing);
    }

    static void discover(YukkuriEntity seed) {
        var level = (ServerLevel) seed.level();
        var pos = seed.blockPosition();
        if (SurfaceApproach.supported(seed, seed.position()) && admits(level, pos, survey(level, pos).vacancies(), true)) {
            seed.pet().markTownRoots();
            GameplayLog.event(seed, "settlement", "village stray from natural discovery");
        }
    }

    /** One player, eight candidate columns and at most one accompanying child per half minute. */
    public static int tick(ServerLevel level, boolean enemies, boolean friendlies) {
        if (level.getGameTime() % 600 != 0 || !friendlies || !YukkuriConfig.NATURAL_SPAWNS.get()
                || !YukkuriConfig.COMMUNITIES.get()) return 0;
        var player = level.getRandomPlayer();
        if (player == null || player.isSpectator() || !TownAware.inTown(level, player.blockPosition())) return 0;
        var village = survey(level, player.blockPosition());
        if (village.vacancies() <= 0) return 0;
        var random = ModRandom.world(level);
        for (int i = 0; i < 8; i++) {
            var bed = village.beds().get(random.nextInt(village.beds().size()));
            var pos = street(level, bed.offset(random.nextIntBetweenInclusive(-12, 12), 0, random.nextIntBetweenInclusive(-12, 12)));
            if (pos == null) continue;
            if (!admits(level, pos, village.vacancies(), false)) return 0;
            var adult = SettlementSpawner.common(level);
            if (adult == null || !arrive(level, adult, pos, null)) return 0;
            if (village.vacancies() > 1 && random.nextInt(5) == 0) {
                var side = Direction.Plane.HORIZONTAL.getRandomDirection(random);
                var beside = street(level, pos.relative(side));
                if (beside != null && Math.abs(beside.getY() - pos.getY()) <= 1
                        && admits(level, beside, village.vacancies() - 1, false)) {
                    var child = ModEntities.forVariant(adult.getVariant()).create(level);
                    if (child != null && arrive(level, child, beside, adult)) return 2;
                }
            }
            return 1;
        }
        return 0;
    }

    private static BlockPos street(ServerLevel level, BlockPos column) {
        if (!level.isPositionEntityTicking(column) || !level.getWorldBorder().isWithinBounds(column)) return null;
        var pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column);
        if (Math.abs(pos.getY() - column.getY()) > 4 || level.hasNearbyAlivePlayer(pos.getX()+.5, pos.getY(), pos.getZ()+.5, 24)) return null;
        var ground = level.getBlockState(pos.below());
        return ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.DIRT_PATH)
                || ground.is(Blocks.GRAVEL) || ground.is(Blocks.SNOW_BLOCK) ? pos : null;
    }

    private static boolean arrive(ServerLevel level, YukkuriEntity body, BlockPos pos, YukkuriEntity parent) {
        var feet = SurfaceApproach.floor(body, Vec3.atBottomCenterOf(pos));
        if (feet == null) return false;
        Motor.relocate(body, feet, body.getYRot(), "village_stray_arrival");
        body.pet().markTownRoots();
        if (parent == null) body.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
        if (!level.addFreshEntity(body)) return false;
        if (parent != null) {
            body.family().generatedChild(parent, null);
            FamilyData.get(level).register(body);
        }
        GameplayLog.event(body, "settlement", "village stray arrived parent={}", parent == null ? "none" : parent.getUUID());
        return true;
    }

    private VillageStrays() {}
}
