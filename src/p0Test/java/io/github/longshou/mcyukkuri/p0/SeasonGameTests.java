package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.season.SeasonClimate;
import io.github.longshou.mcyukkuri.season.SeasonData;
import io.github.longshou.mcyukkuri.season.SeasonData.Season;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only: persistence, actual server clock, shared dimensions and physical thermal refuges. */
@PrefixGameTestTemplate(false)
public final class SeasonGameTests {
    @GameTest(templateNamespace = P0Mod.ID, template = "empty", batch = "season_calendar")
    public static void persistenceAndTimeCommands(GameTestHelper helper) {
        var lookup = helper.getLevel().registryAccess();
        var data = new SeasonData();
        data.sample(80_000_000, true, 7);
        date(helper, data, Season.SPRING, 0, 7, "Old worlds start at spring, without old-time catch-up");
        data.sample(80_000_500, true, 7); data.sample(80_000_500, true, 7);
        date(helper, data, Season.SPRING, 500, 7, "Repeated reads/samples cannot double-count time");
        data.sample(13_000, true, 7);
        data.sample(24_000, true, 7); // The same positive delta produced by sleeping through a night.
        date(helper, data, Season.SPRING, 11_500, 7, "Backward set rebases; a skipped night counts normally");
        data.sample(400_000, false, 7);
        data = SeasonData.load(data.save(new CompoundTag(), lookup), lookup);
        data.sample(900_000, false, 7); data.sample(900_001, true, 7);
        date(helper, data, Season.SPRING, 11_501, 7, "Paused commands and reload do not build a catch-up backlog");
        var saved = data.save(new CompoundTag(), lookup);
        var restored = SeasonData.load(saved, lookup);
        restored.sample(900_001, true, 7);
        helper.assertTrue(restored.snapshot().equals(data.snapshot()), "Reload at the saved timestamp is idempotent");
        restored.sample(900_008, true, 7);
        date(helper, restored, Season.SPRING, 11_508, 7, "Reload retains the fractional day and counts only new time");

        var resize = new SeasonData(); resize.sample(0, true, 7); resize.sample(252_000, true, 7);
        resize.sample(252_000, false, 3);
        date(helper, resize, Season.SUMMER, 36_000, 3, "Changing season length retains season and half-season progress");
        resize.sample(252_000, false, 0);
        date(helper, resize, Season.SUMMER, 12_000, 1, "Invalid short configuration is bounded to one day");
        resize.sample(252_000, false, Integer.MAX_VALUE);
        date(helper, resize, Season.SUMMER, 4_380_000, 365, "Extreme long configuration is bounded without overflow");

        for (int days : new int[] {1, 7, 365}) {
            var extreme = new SeasonData(); extreme.sample(Long.MIN_VALUE, true, days); extreme.sample(Long.MAX_VALUE, true, days);
            long cycle = 4 * days * SeasonData.DAY_TICKS;
            long expected = BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.valueOf(Long.MIN_VALUE))
                    .mod(BigInteger.valueOf(cycle)).longValueExact();
            helper.assertTrue(phase(extreme) == expected, "Full signed-long positive jump agrees with an independent BigInteger oracle");
            var before = extreme.snapshot(); extreme.sample(Long.MIN_VALUE, true, days);
            helper.assertTrue(extreme.snapshot().equals(before), "Signed-long wrap is a backward rebase, never overflow progress");
            extreme.sample(Long.MIN_VALUE + 1, true, days);
            helper.assertTrue(phase(extreme) == (expected + 1) % cycle, "Time advances normally after a negative baseline");
        }
        for (String field : List.of("Version", "LastDayTime", "PhaseTicks", "DaysPerSeason")) {
            var incomplete = saved.copy(); incomplete.remove(field);
            var fresh = SeasonData.load(incomplete, lookup); fresh.sample(Long.MAX_VALUE, true, 7);
            date(helper, fresh, Season.SPRING, 0, 7, "Missing " + field + " cannot invent elapsed seasons");
        }
        for (long invalid : new long[] {-1, Long.MIN_VALUE, Long.MAX_VALUE, 672_000}) {
            var damaged = saved.copy(); damaged.putLong("PhaseTicks", invalid);
            var fresh = SeasonData.load(damaged, lookup); fresh.sample(900_009, true, 7);
            date(helper, fresh, Season.SPRING, 0, 7, "Invalid saved phase restarts conservatively");
        }
        for (int invalid : new int[] {0, -1, 366, Integer.MAX_VALUE}) {
            var damaged = saved.copy(); damaged.putInt("DaysPerSeason", invalid);
            var fresh = SeasonData.load(damaged, lookup); fresh.sample(900_009, true, 7);
            date(helper, fresh, Season.SPRING, 0, 7, "Invalid saved duration cannot divide by zero or overflow");
        }
        climateFormula(helper);
        helper.succeed();
    }
    private static long phase(SeasonData data) {
        var date = data.snapshot();
        return date.season().ordinal() * date.daysPerSeason() * SeasonData.DAY_TICKS + date.ticksIntoSeason();
    }
    private static void date(GameTestHelper helper, SeasonData data, Season season, long within, int days, String message) {
        var actual = data.snapshot();
        helper.assertTrue(actual.season() == season && actual.ticksIntoSeason() == within && actual.daysPerSeason() == days
                && actual.day() == within / SeasonData.DAY_TICKS + 1, message + ": " + actual);
    }
    private static void climateFormula(GameTestHelper helper) {
        for (double biome : new double[] {-2, -.7, -0.0, 0, .199, .2, .799, .8, 1, 1.45, 2, 4})
            for (boolean sky : new boolean[] {false, true}) for (boolean night : new boolean[] {false, true})
                for (boolean shade : new boolean[] {false, true}) for (boolean rain : new boolean[] {false, true}) {
                    double previous = biome;
                    if (sky) {
                        if (night) previous -= shade ? .15 : .35;
                        else if (biome >= .8) previous += shade ? -Math.min(.65, biome - .8) : .35;
                        if (!shade && rain && biome < .2) previous -= .2;
                    }
                    helper.assertTrue(Double.compare(previous, SeasonClimate.ambient(biome, 0, sky, night, shade, rain)) == 0,
                            "Default spring preserves the previous formula exactly, including cold biomes and dimensions without skylight");
                }
        near(helper, SeasonClimate.ambient(.8, .45, true, false, false, false), 1.6, "Summer plains sun is hot");
        near(helper, SeasonClimate.ambient(.8, .45, true, false, true, false), .8, "Actual shade keeps summer plains comfortable");
        near(helper, SeasonClimate.ambient(2, .45, true, false, true, false), 1.35, "Summer desert shade remains useful");
        near(helper, SeasonClimate.ambient(.8, -.8, true, false, false, false), .35, "Winter plains sunlight still permits outdoor activity");
        near(helper, SeasonClimate.ambient(.8, -.8, true, false, true, false), 0, "Winter shade adds no artificial cooling below its ambient climate");
        near(helper, SeasonClimate.ambient(.8, -.8, true, true, true, false) + 1, .85, "A real lined winter nest can be warm");
        near(helper, SeasonClimate.ambient(.8, -.8, false, true, true, true), .8, "No-skylight environments ignore the seasonal offset");
        var base = helper.getLevel().dimensionType();
        var fixed = new DimensionType(OptionalLong.of(6000), true, base.hasCeiling(), base.ultraWarm(), base.natural(),
                base.coordinateScale(), base.bedWorks(), base.respawnAnchorWorks(), base.minY(), base.height(), base.logicalHeight(),
                base.infiniburn(), base.effectsLocation(), base.ambientLight(), base.monsterSettings());
        helper.assertTrue(!SeasonClimate.applies(fixed), "A fixed-time dimension with skylight still has no seasonal climate");
    }
    private static void near(GameTestHelper helper, double actual, double expected, String message) {
        helper.assertTrue(Math.abs(actual - expected) < .00001, message + ": " + actual);
    }

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 500, batch = "season_world")
    public static void sharedClockAndRealRefuges(GameTestHelper helper) {
        var level = helper.getLevel(); var server = level.getServer(); var overworld = server.overworld();
        var originalCalendar = SeasonData.get(level);
        var oldDate = originalCalendar.save(new CompoundTag(), level.registryAccess());
        long oldTime = overworld.getDayTime();
        boolean oldDaylight = overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean oldRain = level.isRaining(), oldThunder = level.isThundering();
        float oldRainLevel = level.getRainLevel(1), oldThunderLevel = level.getThunderLevel(1);
        int oldDays = YukkuriConfig.SEASON_DAYS.get();
        var config = List.of(YukkuriConfig.SPRING_TEMPERATURE, YukkuriConfig.SUMMER_TEMPERATURE, YukkuriConfig.AUTUMN_TEMPERATURE,
                YukkuriConfig.WINTER_TEMPERATURE, YukkuriConfig.COLD_THRESHOLD, YukkuriConfig.FREEZE_THRESHOLD,
                YukkuriConfig.HOT_THRESHOLD, YukkuriConfig.NEST_INSULATION);
        var oldConfig = config.stream().map(value -> value.get()).toList();
        var forced = new HashSet<>(level.getForcedChunks());
        var blocks = new HashMap<BlockPos, BlockState>(); var actors = new ArrayList<YukkuriEntity>();
        boolean[] cleaned = {false}; long[] start = {0};
        Runnable cleanup = () -> {
            if (cleaned[0]) return; cleaned[0] = true;
            actors.forEach(YukkuriEntity::discard);
            var home = ShelterBlock.shelter(level, new BlockPos(2814, 65, 8)); if (home != null) home.rollback();
            blocks.forEach(level::setBlockAndUpdate);
            overworld.setDayTime(oldTime); level.updateSkyBrightness();
            overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(oldDaylight, server);
            overworld.getDataStorage().set(SeasonData.DATA_NAME, SeasonData.load(oldDate, level.registryAccess()));
            level.setWeatherParameters(12000, 12000, oldRain, oldThunder); level.setRainLevel(oldRainLevel); level.setThunderLevel(oldThunderLevel);
            YukkuriConfig.SEASON_DAYS.set(oldDays);
            for (int i = 0; i < config.size(); i++) config.get(i).set(oldConfig.get(i));
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
            helper.assertTrue(level == overworld, "The remote physical fixture runs in the overworld");
            YukkuriConfig.SEASON_DAYS.set(1);
            double[] defaults = {0, .45, -.25, -.8, .2, 0, 1.5, 1};
            for (int i = 0; i < config.size(); i++) config.get(i).set(defaults[i]);
            overworld.setDayTime(6000); start[0] = 6000;
            overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
            var calendar = new SeasonData(); calendar.sample(6000, true, 1);
            overworld.getDataStorage().set(SeasonData.DATA_NAME, calendar);
        }).thenExecuteAfter(5, () -> {
            long elapsed = overworld.getDayTime() - start[0];
            // GameTest runs during the level tick; the Post listener has sampled through the prior tick.
            long progress = phase(SeasonData.get(level));
            helper.assertTrue(elapsed >= 4 && progress >= elapsed - 1 && progress <= elapsed,
                    "Actual registered server ticks advance the calendar once, before any fixture Yukkuri exists");
            var nether = server.getLevel(Level.NETHER); var end = server.getLevel(Level.END);
            helper.assertTrue(nether != null && end != null, "Vanilla other dimensions exist in this server fixture");
            var before = SeasonData.get(level).snapshot();
            for (int i = 0; i < 20; i++) {
                helper.assertTrue(SeasonData.get(nether) == SeasonData.get(level) && SeasonData.get(end) == SeasonData.get(level),
                        "Every dimension reads the same overworld SavedData");
                io.github.longshou.mcyukkuri.season.SeasonCalendar.page(nether);
            }
            helper.assertTrue(SeasonData.get(level).snapshot().equals(before), "Repeated cross-dimension notebook reads do not advance the clock");
            helper.assertTrue(SeasonClimate.offset(nether) == 0 && SeasonClimate.offset(end) == 0, "Nether and End have no ordinary seasonal climate");
            realRefuges(helper, blocks, actors);
            overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            start[0] = phase(SeasonData.get(level));
            overworld.setDayTime(900_000); // A real time change while frozen must be rebased by the registered hook.
        }).thenExecuteAfter(4, () -> {
            helper.assertTrue(phase(SeasonData.get(level)) == start[0], "Frozen daylight ignores a real command-style forward jump");
            overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
        }).thenExecuteAfter(4, () -> {
            long progress = phase(SeasonData.get(level)) - start[0];
            helper.assertTrue(progress >= 3 && progress <= 5, "Resume advances a few new ticks, never the paused command jump");
            cleanup.run();
        }).thenSucceed();
    }
    private static void realRefuges(GameTestHelper helper, HashMap<BlockPos, BlockState> blocks, ArrayList<YukkuriEntity> actors) {
        var level = helper.getLevel(); var shade = new BlockPos(2808, 65, 8); var sun = shade.west(5); var root = shade.east(6);
        for (var pos : BlockPos.betweenClosed(shade.offset(-10, -1, -10), shade.offset(10, 4, 10))) {
            blocks.put(pos.immutable(), level.getBlockState(pos));
            level.setBlockAndUpdate(pos, pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(shade.above(2), Blocks.STONE.defaultBlockState());
        level.setWeatherParameters(12000, 0, false, false); level.setRainLevel(0); level.setThunderLevel(0);
        near(helper, level.getBiome(sun).value().getBaseTemperature(), .8, "Physical fixture is a plains biome");
        var actor = ModEntities.REIMU.get().create(level); actors.add(actor);
        actor.moveTo(Vec3.atBottomCenterOf(sun)); actor.setOnGround(true); level.addFreshEntity(actor);
        var family = actor.family().save(); family.putBoolean("Initialized", true); family.putString("Stage", "ADULT"); family.putBoolean("Sterile", true); actor.family().load(family);
        var life = actor.life().save(); life.putFloat("Nutrition", 100); life.putFloat("Wetness", 0); life.putFloat("Bladder", 0); actor.life().load(life);
        var thermal = actor.life().temperature();
        setSeason(level, Season.SUMMER);
        near(helper, thermal.sample(sun).felt(), 1.6, "Summer reaches actual exposed body temperature");
        near(helper, thermal.sample(shade).felt(), .8, "A real roof supplies effective summer shade");
        for (int i = 0; i < 60; i++) thermal.tick(); // Normal post-load goal cooldown, with no calendar catch-up.
        helper.assertTrue(thermal.plan() && thermal.activity().equals("seek_shade"), "Actual summer conditions drive existing refuge planning");
        thermal.stop();
        var placed = ShelterPlacement.place(level, new ShelterPlacement.Plan(root, ShelterBlock.Kind.CARDBOARD, Direction.SOUTH));
        helper.assertTrue(placed != null && actor.habitat().choose(placed.shelter()), "Actor can use a real owned winter shelter");
        for (int i = 0; i < 3; i++) placed.shelter().deposit(new ItemStack(Items.PAPER));
        long age = actor.family().age(); float nutrition = actor.life().nutrition(); int exposure = thermal.coldExposure();
        setSeason(level, Season.WINTER);
        helper.assertTrue(actor.family().age() == age && actor.life().nutrition() == nutrition && thermal.coldExposure() == exposure,
                "Changing calendar time does not issue growth, hunger or cold-exposure catch-up");
        near(helper, thermal.sample(sun).felt(), .35, "Winter plains sunlight can remain comfortable");
        level.setDayTime(level.getDayTime() + 12_000); level.updateSkyBrightness();
        var bed = placed.shelter().bed(actor.getUUID());
        near(helper, thermal.sample(sun).felt(), -.35, "Actual exposed winter night is cold");
        near(helper, thermal.sample(bed).felt(), .85, "Real dry bedding follows seasonal cooling and supplies insulation");
        helper.assertTrue(thermal.sample(bed).insulated(), "Remembered home alone is not the insulation proof");
        actor.moveTo(Vec3.atBottomCenterOf(root.west(4))); actor.setOnGround(true);
        for (int i = 0; i < 120; i++) thermal.tick(); // Let the previous refuge cooldown expire, using loaded exposure only.
        helper.assertTrue(thermal.plan() && thermal.activity().equals("seek_warmth"), "Winter body conditions drive existing warmth planning");
        thermal.stop();
        actor.setNoAi(true); // The remaining clock checks must not depend on this fixture actor's goals.
        var roof = root.above(); var roofState = level.getBlockState(roof); level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
        helper.assertTrue(!thermal.sample(bed).insulated(), "Winter does not give a broken real shelter free insulation");
        level.setBlockAndUpdate(roof, roofState);
    }
    private static void setSeason(ServerLevel level, Season season) {
        var data = new SeasonData(); data.sample(6000, true, 1);
        level.setDayTime(6000 + season.ordinal() * SeasonData.DAY_TICKS); level.updateSkyBrightness();
        data.sample(level.getDayTime(), true, 1);
        level.getServer().overworld().getDataStorage().set(SeasonData.DATA_NAME, data);
    }
}
