package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.season.SeasonClimate;
import io.github.longshou.mcyukkuri.settlement.Season;
import io.github.longshou.mcyukkuri.settlement.Seasons;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only: local biome facts drive thermal exposure and never advance a calendar. */
@PrefixGameTestTemplate(false)
public final class SeasonGameTests {
    private static final BlockPos ROOT = new BlockPos(2814, 65, 8);

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 500, batch = "seasons")
    public static void biomeSourceAndRealRefuges(GameTestHelper helper) {
        var level = helper.getLevel();
        long oldTime = level.getDayTime();
        boolean oldDaylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean oldRain = level.isRaining(), oldThunder = level.isThundering();
        float oldRainLevel = level.getRainLevel(1), oldThunderLevel = level.getThunderLevel(1);
        boolean oldBiomeSeasons = YukkuriConfig.BIOME_SEASONS.get();
        var temperatures = java.util.List.of(YukkuriConfig.SPRING_TEMPERATURE, YukkuriConfig.SUMMER_TEMPERATURE,
                YukkuriConfig.AUTUMN_TEMPERATURE, YukkuriConfig.WINTER_TEMPERATURE, YukkuriConfig.COLD_THRESHOLD,
                YukkuriConfig.FREEZE_THRESHOLD, YukkuriConfig.HOT_THRESHOLD, YukkuriConfig.NEST_INSULATION);
        var oldTemperatures = temperatures.stream().map(value -> value.get()).toList();
        var forced = new HashSet<>(level.getForcedChunks());
        var blocks = new HashMap<BlockPos, BlockState>();
        var biomes = new HashMap<BlockPos, String>();
        YukkuriEntity[] actor = {null};
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            if (actor[0] != null) actor[0].discard();
            var home = ShelterBlock.shelter(level, ROOT);
            if (home != null) home.rollback();
            blocks.forEach(level::setBlockAndUpdate);
            biomes.forEach((cell, id) -> fillBiome(level, cell, cell.offset(3, 3, 3), id));
            level.setDayTime(oldTime); level.updateSkyBrightness();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(oldDaylight, level.getServer());
            level.setWeatherParameters(12_000, 12_000, oldRain, oldThunder);
            level.setRainLevel(oldRainLevel); level.setThunderLevel(oldThunderLevel);
            YukkuriConfig.BIOME_SEASONS.set(oldBiomeSeasons);
            for (int i = 0; i < temperatures.size(); i++) temperatures.get(i).set(oldTemperatures.get(i));
            for (int x = 174; x <= 176; x++) for (int z = -1; z <= 1; z++)
                GameTestChunks.setForced(level, x, z, forced.contains(net.minecraft.world.level.ChunkPos.asLong(x, z)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo, net.minecraft.gametest.framework.GameTestInfo newInfo, net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        for (int x = 174; x <= 176; x++) for (int z = -1; z <= 1; z++) GameTestChunks.setForced(level, x, z, true);
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper, 174, 176, -1, 1)).thenExecute(() -> {
            rememberBiomes(level, biomes, ROOT.offset(-12, -5, -12), ROOT.offset(12, 8, 12));
            for (var pos : BlockPos.betweenClosed(ROOT.offset(-10, -1, -10), ROOT.offset(10, 4, 10))) {
                blocks.put(pos.immutable(), level.getBlockState(pos));
                level.setBlockAndUpdate(pos, pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            YukkuriConfig.BIOME_SEASONS.set(true);
            double[] defaults = {0, .45, -.25, -.8, .2, 0, 1.5, 1.3};
            for (int i = 0; i < temperatures.size(); i++) temperatures.get(i).set(defaults[i]);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
            level.setWeatherParameters(12_000, 0, false, false); level.setRainLevel(0); level.setThunderLevel(0);

            var sun = ROOT.south(5); var shade = ROOT.west(6);
            level.setBlockAndUpdate(shade.above(2), Blocks.STONE.defaultBlockState());
            fillBiome(level, ROOT.offset(-12, -5, -12), ROOT.offset(12, 8, 12), "minecraft:desert");
            helper.assertTrue(Seasons.at(level, sun) == Season.SUMMER, "Summer is selected from the local desert biome");
            helper.assertTrue(SeasonClimate.offset(level, sun) == .45, "Summer thermal offset comes from the same biome fact");
            level.setDayTime(6_000); level.updateSkyBrightness();
            level.setDayTime(102_000); level.updateSkyBrightness();
            helper.assertTrue(Seasons.at(level, sun) == Season.SUMMER, "Time changes cannot rotate the biome season");

            actor[0] = ModEntities.REIMU.get().create(level);
            actor[0].moveTo(Vec3.atBottomCenterOf(sun)); actor[0].setOnGround(true); level.addFreshEntity(actor[0]);
            var family = actor[0].family().save(); family.putBoolean("Initialized", true); family.putString("Stage", "ADULT"); family.putBoolean("Sterile", true); actor[0].family().load(family);
            var life = actor[0].life().save(); life.putFloat("Nutrition", 100); life.putFloat("Wetness", 0); life.putFloat("Bladder", 0); actor[0].life().load(life);
            var thermal = actor[0].life().temperature();
            helper.assertTrue(thermal.sample(sun).felt() > thermal.sample(shade).felt(), "A real roof cools a summer biome");
            for (int i = 0; i < 60; i++) thermal.tick();
            helper.assertTrue(thermal.plan() && thermal.activity().equals("seek_shade"), "Summer conditions use the ordinary refuge planner");
            thermal.stop();

            var placed = ShelterPlacement.place(level, new ShelterPlacement.Plan(ROOT, ShelterBlock.Kind.CARDBOARD, Direction.SOUTH));
            helper.assertTrue(placed != null && placed.shelter().sound(), "Real shelter placement succeeds");
            helper.assertTrue(actor[0].life().dryStand(ROOT) && actor[0].life().reachable(ROOT, false), "Outdoor actor has a real route through the shelter entrance");
            helper.assertTrue(actor[0].habitat().choose(placed.shelter()), "Actor can claim its reachable shelter");
            for (int i = 0; i < 3; i++) helper.assertTrue(placed.shelter().deposit(new ItemStack(Items.PAPER)), "Real supplied paper creates bedding");

            fillBiome(level, ROOT.offset(-12, -5, -12), ROOT.offset(12, 8, 12), "minecraft:snowy_plains");
            helper.assertTrue(Seasons.at(level, sun) == Season.WINTER, "Winter is selected from the local snowy biome");
            level.setDayTime(18_000); level.updateSkyBrightness();
            var bed = placed.shelter().bed(actor[0].getUUID());
            helper.assertTrue(thermal.sample(sun).felt() < YukkuriConfig.COLD_THRESHOLD.get(), "A snowy winter night is actually cold");
            helper.assertTrue(thermal.sample(bed).insulated() && thermal.sample(bed).felt() >= YukkuriConfig.COLD_THRESHOLD.get(), "Configured dry bedding offsets local winter exposure");
            actor[0].moveTo(Vec3.atBottomCenterOf(ROOT.west(4))); actor[0].setOnGround(true);
            for (int i = 0; i < 120; i++) thermal.tick();
            helper.assertTrue(thermal.plan() && thermal.activity().equals("seek_warmth"), "Winter conditions use the ordinary warmth planner");
            thermal.stop();

            fillBiome(level, ROOT.offset(-12, -5, -12), ROOT.offset(12, 8, 12), "minecraft:forest");
            helper.assertTrue(Seasons.at(level, sun) == Season.AUTUMN, "Forest selects autumn without a date transition");
            fillBiome(level, ROOT.offset(-12, -5, -12), ROOT.offset(12, 8, 12), "minecraft:plains");
            helper.assertTrue(Seasons.at(level, sun) == Season.SPRING, "Plains selects spring without a date transition");
            climateFormula(helper);
        }).thenExecute(cleanup).thenSucceed();
    }

    private static void climateFormula(GameTestHelper helper) {
        for (double biome : new double[] {-2, -.7, 0, .199, .2, .799, .8, 1, 1.45, 2, 4})
            for (boolean sky : new boolean[] {false, true}) for (boolean night : new boolean[] {false, true})
                for (boolean shade : new boolean[] {false, true}) for (boolean rain : new boolean[] {false, true}) {
                    double previous = biome;
                    if (sky) {
                        if (night) previous -= shade ? .15 : .35;
                        else if (biome >= .8) previous += shade ? -Math.min(.65, biome - .8) : .35;
                        if (!shade && rain && biome < .2) previous -= .2;
                    }
                    helper.assertTrue(Double.compare(previous, SeasonClimate.ambient(biome, 0, sky, night, shade, rain)) == 0,
                            "Zero offset preserves the previous thermal formula");
                }
        var base = helper.getLevel().dimensionType();
        var fixed = new DimensionType(OptionalLong.of(6000), true, base.hasCeiling(), base.ultraWarm(), base.natural(),
                base.coordinateScale(), base.bedWorks(), base.respawnAnchorWorks(), base.minY(), base.height(), base.logicalHeight(),
                base.infiniburn(), base.effectsLocation(), base.ambientLight(), base.monsterSettings());
        helper.assertTrue(!SeasonClimate.applies(fixed), "A fixed-time dimension has no seasonal thermal offset");
    }

    private static void rememberBiomes(ServerLevel level, Map<BlockPos, String> before, BlockPos min, BlockPos max) {
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        int minX = Math.floorDiv(min.getX(), 4) * 4, minY = Math.floorDiv(min.getY(), 4) * 4, minZ = Math.floorDiv(min.getZ(), 4) * 4;
        for (int x = minX; x <= max.getX(); x += 4) for (int y = minY; y <= max.getY(); y += 4) for (int z = minZ; z <= max.getZ(); z += 4) {
            var cell = new BlockPos(x, y, z);
            before.put(cell, registry.getKey(level.getChunkAt(cell).getNoiseBiome(x >> 2, y >> 2, z >> 2).value()).toString());
        }
    }

    private static void fillBiome(ServerLevel level, BlockPos min, BlockPos max, String id) {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4),
                "fillbiome " + min.getX() + " " + min.getY() + " " + min.getZ() + " " + max.getX() + " " + max.getY() + " " + max.getZ() + " " + id);
    }
}
