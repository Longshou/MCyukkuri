package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.TownAware;
import io.github.longshou.mcyukkuri.life.FoodNoticeGoal;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.random.ModRandom;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.Identity;
import io.github.longshou.mcyukkuri.status.LearnedFear;
import io.github.longshou.mcyukkuri.status.Reactions;
import io.github.longshou.mcyukkuri.status.YukkuriStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class VillageStrays {
    private static int vacancies(ServerLevel level, VillageSurvey.Streets streets) {
        if (streets == null || streets.beds().isEmpty()) return 0;
        int target = StrayDensity.target(streets.beds().size(), StrayDensity.night(level.getDayTime()),
                (level.isRaining() || level.isThundering()) && level.getBiome(streets.centre()).value().hasPrecipitation());
        // Bell catchments that overlap share their saved residents, including unloaded families.
        return target - FamilyData.get(level).villageResidents(level, streets.address(), 2 * StrayDensity.RADIUS);
    }

    private static void settle(YukkuriEntity body, VillageSurvey.Streets streets) {
        var level = (ServerLevel)body.level();
        body.pet().markTownRoots();
        var data = FamilyData.get(level);
        data.village(data.register(body), GlobalPos.of(level.dimension(), streets.address()));
    }

    public static boolean rooted(YukkuriEntity body) {
        if (!body.pet().townRoots() || !(body.level() instanceof ServerLevel level)) return false;
        var person = FamilyData.get(level).person(body.getUUID());
        return person != null && person.village() != null && withinVillage(body, person.village());
    }

    /** A rooted free stray nothing keeps at home, in weather it can stand: the one that may spend daytime on the street. */
    public static boolean walksStreet(YukkuriEntity body) {
        var temperature = body.life().temperature();
        return FoundShelter.streetUser(body) && rooted(body) && !body.family().hasDependentYoung() && !body.settlement().underway()
                && !body.life().scolded() && !Reactions.Weather.rainAt(body, body.blockPosition())
                && !temperature.cold() && !temperature.hot();
    }

    /** The one eligibility for a stray's optional stop at the badge plaza: a street walker that is also free to travel, awake and unthreatened. */
    public static boolean browsesPlaza(YukkuriEntity body) {
        return walksStreet(body) && FoundShelter.invitationTraveler(body) && !body.life().isSleeping();
    }

    /** The bell of the village this stray is rooted in (its roots, not its feet, pick the village); null with no bell standing. */
    public static BlockPos heart(YukkuriEntity body) {
        var level = (ServerLevel) body.level();
        var root = FamilyData.get(level).person(body.getUUID()).village().pos();
        var streets = SettlementData.get(level).villageSurvey.at(level, root);
        return streets != null && level.hasChunkAt(streets.centre()) && level.getBlockState(streets.centre()).is(Blocks.BELL)
                ? streets.centre() : null;
    }

    /**
     * The nearest player holding food this stray would take, beyond {@code beyond} blocks (inside that the notice goal or the ask owns
     * it) and within scent range of the notice range, standing well inside the village so the stray is never lured out of it. The
     * same gates as FoodNoticeGoal, so a stray that would not react is not drawn.
     */
    public static Player holder(YukkuriEntity body, double beyond) {
        var life = body.life();
        long now = body.level().getGameTime();
        double reach = life.foodNoticeRange() + StreetDraw.SCENT;
        if (!life.canEat() || !life.needsFood()) return null;
        var root = FamilyData.get((ServerLevel) body.level()).person(body.getUUID()).village().pos();
        Player nearest = null;
        for (var player : body.level().players()) {
            double distance = body.distanceTo(player);
            if (!player.isAlive() || player.isSpectator() || distance <= beyond || distance > reach
                    || !life.seeksOfferedFood(player) || LearnedFear.waryOf(body, player)
                    || !body.social().begs().allows(now, life.nutrition(), FoodNoticeGoal.key(player))
                    || !StrayDensity.wellInside(player.getBlockX() - root.getX(), player.getBlockZ() - root.getZ())) continue;
            if (nearest == null || distance < body.distanceTo(nearest)) nearest = player;
        }
        return nearest;
    }

    private static boolean withinVillage(YukkuriEntity body, GlobalPos root) {
        return StrayDensity.stillResident(body.level().dimension().equals(root.dimension()),
                body.getBlockX() - root.pos().getX(), body.getBlockZ() - root.pos().getZ());
    }

    public static void forget(YukkuriEntity body) {
        if (!(body.level() instanceof ServerLevel level)) return;
        var data = FamilyData.get(level);
        var person = data.person(body.getUUID());
        if (person != null) data.village(person, null);
    }

    public static void observe(YukkuriEntity body) {
        if (!body.pet().townRoots() || !(body.level() instanceof ServerLevel level)) return;
        var person = FamilyData.get(level).person(body.getUUID());
        if (body.status().identity() != Identity.STRAY) { forget(body); return; }
        if (person != null && person.village() != null) {
            if (!withinVillage(body, person.village())) {
                body.pet().leaveTownLife();
                YukkuriStatus.identityChanged(body, Identity.STRAY, "village_emigration");
            }
        } else if (TownAware.inTown(level, body.blockPosition())) {
            var streets = SettlementData.get(level).villageSurvey.at(level, body.blockPosition());
            if (streets != null && !streets.beds().isEmpty() && streets.contains(body.blockPosition()))
                settle(body, streets);
        }
    }

    public static boolean hasLocalRoom(ServerLevel level, BlockPos pos, boolean existing) {
        int nearby = level.getEntitiesOfClass(YukkuriEntity.class, new AABB(pos).inflate(32)).size();
        return StrayDensity.hasLocalRoom(nearby, FamilyData.get(level).population(level, pos),
                YukkuriConfig.LOCAL_SPAWN_CAP.get(), FamilyConfig.POPULATION_CAP.get(), existing);
    }

    static void discover(YukkuriEntity seed) {
        var level = (ServerLevel) seed.level();
        var pos = seed.blockPosition();
        var streets = SettlementData.get(level).villageSurvey.at(level, pos);
        if (streets != null && streets.contains(pos)
                && SurfaceApproach.supported(seed, seed.position()) && vacancies(level, streets) > 0 && hasLocalRoom(level, pos, true)) {
            settle(seed, streets);
            GameplayLog.event(seed, "settlement", "village stray from natural discovery");
        }
    }

    /** One player, eight candidate columns and at most one accompanying child per half minute. */
    public static int tick(ServerLevel level, boolean enemies, boolean friendlies) {
        if (level.getGameTime() % 600 != 0 || !friendlies || !YukkuriConfig.NATURAL_SPAWNS.get()
                || !YukkuriConfig.COMMUNITIES.get()) return 0;
        var player = level.getRandomPlayer();
        if (player == null || player.isSpectator() || !TownAware.inTown(level, player.blockPosition())) return 0;
        var village = SettlementData.get(level).villageSurvey.at(level, player.blockPosition());
        int vacancies = vacancies(level, village);
        if (vacancies <= 0) return 0;
        var random = ModRandom.world(level);
        var adult = SettlementSpawner.common(level);
        if (adult == null) return 0;
        for (int i = 0; i < 8; i++) {
            var bed = village.beds().get(random.nextInt(village.beds().size()));
            var column = bed.offset(random.nextIntBetweenInclusive(-12, 12), 0, random.nextIntBetweenInclusive(-12, 12));
            if (!village.contains(column)) continue;
            if (i < 4 && VillageSurvey.horizontal(column, village.centre()) < VillageSurvey.horizontal(bed, village.centre())) continue;
            var pos = street(level, column);
            if (pos == null) continue;
            var feet = hiddenStand(level, adult, pos);
            if (feet == null) continue;
            if (!hasLocalRoom(level, pos, false) || !arrive(level, adult, feet, null, village)) return 0;
            if (vacancies > 1 && random.nextInt(5) == 0) {
                var side = Direction.Plane.HORIZONTAL.getRandomDirection(random);
                var beside = street(level, pos.relative(side));
                if (beside != null && village.contains(beside) && Math.abs(beside.getY() - pos.getY()) <= 1) {
                    var child = ModEntities.forVariant(adult.getVariant()).create(level);
                    if (child != null) {
                        var childFeet = hiddenStand(level, child, beside);
                        if (childFeet != null && hasLocalRoom(level, beside, false)
                                && arrive(level, child, childFeet, adult, village)) return 2;
                    }
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

    public static boolean hidden(ServerLevel level, AABB box, int trackingChunks) {
        var cursor = new BlockPos.MutableBlockPos();
        StraySight.Terrain terrain = (x, y, z) -> {
            var chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) return false;
            cursor.set(x, y, z);
            return chunk.getBlockState(cursor).isSolidRender(level, cursor);
        };
        var center = box.getCenter();
        int entityRange = level.getServer().getScaledTrackingDistance(trackingChunks * 16);
        int viewChunks = level.getServer().getPlayerList().getViewDistance();
        for (var player : level.players()) {
            int range = StraySight.trackingRange(entityRange, viewChunks, player.requestedViewDistance());
            if (!StraySight.hidden(terrain, range, player.getX(), player.getEyeY(), player.getZ(),
                    center.x, center.y, center.z, box.getXsize() / 2, box.getYsize() / 2)) return false;
        }
        return true;
    }

    private static Vec3 hiddenStand(ServerLevel level, YukkuriEntity body, BlockPos pos) {
        var feet = SurfaceApproach.floor(body, Vec3.atBottomCenterOf(pos));
        return feet != null && hidden(level, body.getDimensions(body.getPose()).makeBoundingBox(feet),
                body.getType().clientTrackingRange()) ? feet : null;
    }

    private static boolean arrive(ServerLevel level, YukkuriEntity body, Vec3 feet, YukkuriEntity parent,
                                  VillageSurvey.Streets village) {
        Motor.relocate(body, feet, body.getYRot(), "village_stray_arrival");
        body.pet().markTownRoots();
        if (parent == null) {
            body.finalizeSpawn(level, level.getCurrentDifficultyAt(body.blockPosition()), MobSpawnType.NATURAL, null);
            if (!hidden(level, body.getBoundingBox(), body.getType().clientTrackingRange())) return false;
        }
        if (!level.addFreshEntity(body)) return false;
        if (parent != null) body.family().generatedChild(parent, null);
        settle(body, village);
        GameplayLog.event(body, "settlement", "village stray arrived parent={}", parent == null ? "none" : parent.getUUID());
        return true;
    }

    private VillageStrays() {}
}
