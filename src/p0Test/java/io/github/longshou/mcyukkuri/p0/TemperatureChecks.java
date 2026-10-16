package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/** Remote-only world checks inside the existing loaded shelter fixture; no movement claims. */
final class TemperatureChecks {
    static void run(ServerLevel level, BlockPos origin, BiConsumer<String, Boolean> check) {
        var actors = new ArrayList<YukkuriEntity>();
        var probe = origin.east(10);
        var source = probe.east(3);
        var wall = probe.east();
        var oldSource = level.getBlockState(source);
        var oldWall = level.getBlockState(wall);
        var oldWallAbove = level.getBlockState(wall.above());
        long time = level.getDayTime();
        double oldCold = YukkuriConfig.COLD_THRESHOLD.get(), oldFreeze = YukkuriConfig.FREEZE_THRESHOLD.get(), oldHot = YukkuriConfig.HOT_THRESHOLD.get();
        var placed = ShelterPlacement.place(level, new ShelterPlacement.Plan(origin, ShelterBlock.Kind.CARDBOARD, Direction.SOUTH));
        try {
            var adult = ModEntities.REIMU.get().create(level); actors.add(adult);
            adult.moveTo(Vec3.atBottomCenterOf(probe)); adult.setOnGround(true); level.addFreshEntity(adult);
            var thermal = adult.life().temperature();
            level.setBlockAndUpdate(source, Blocks.TORCH.defaultBlockState());
            check.accept("temperature: exposed real torch warms the observer", thermal.sample(probe).warmth() > 0);
            level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(wall.above(), Blocks.STONE.defaultBlockState());
            check.accept("temperature: solid wall blocks thermal radiation", thermal.sample(probe).warmth() == 0);
            level.setBlockAndUpdate(wall, oldWall); level.setBlockAndUpdate(wall.above(), oldWallAbove);
            level.setBlockAndUpdate(source, Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, false));
            check.accept("temperature: extinguished campfire supplies no warmth", thermal.sample(probe).warmth() == 0);
            level.setBlockAndUpdate(source, Blocks.CAMPFIRE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
            check.accept("temperature: lit campfire supplies real warmth", thermal.sample(probe).warmth() > 0);
            level.setBlockAndUpdate(source, oldSource);

            // The shared flat-world fixture is plains. Temporary original thresholds exercise
            // loaded-time physiology without changing the world's biome or waiting several minutes.
            YukkuriConfig.COLD_THRESHOLD.set(1.0); YukkuriConfig.FREEZE_THRESHOLD.set(1.0); YukkuriConfig.HOT_THRESHOLD.set(4.0);
            level.setDayTime(18000); level.updateSkyBrightness();
            equalBoundary(level, probe.south(4), actors, check);
            check.accept("temperature: controlled fixture actually exposes an unprotected body", thermal.sample(probe).felt() <= 1.0);
            var baby = ModEntities.REIMU.get().create(level); actors.add(baby);
            baby.moveTo(Vec3.atBottomCenterOf(probe.south())); baby.setOnGround(true); level.addFreshEntity(baby);
            var family = baby.family().save(); family.putString("Stage", "BABY"); baby.family().load(family);
            for (int i = 0; i < 200; i++) { thermal.tick(); baby.life().temperature().tick(); }
            check.accept("temperature: true baby stage accumulates more cold exposure", baby.life().temperature().coldExposure() > thermal.coldExposure() && thermal.coldExposure() > 0);
            var saved = baby.life().save(); int exposure = baby.life().temperature().coldExposure(); baby.life().load(saved);
            check.accept("temperature: cold exposure survives entity life NBT", baby.life().temperature().coldExposure() == exposure);
            saved.remove("Temperature"); baby.life().load(saved);
            check.accept("temperature: older life NBT starts with zero exposure", baby.life().temperature().coldExposure() == 0);

            var nearInjury = new CompoundTag(); nearInjury.putInt("ColdExposure", YukkuriConfig.COLD_EXPOSURE_TICKS.get());
            nearInjury.putInt("DamageTicks", YukkuriConfig.FREEZE_DAMAGE_INTERVAL.get() - 20);
            thermal.load(nearInjury); float health = adult.getHealth();
            for (int i = 0; i < 20; i++) thermal.tick();
            check.accept("temperature: sustained exposure deals actual freezing injury", adult.getHealth() < health);

            check.accept("temperature: physical cardboard fixture exists", placed != null);
            if (placed != null) {
                var home = placed.shelter();
                var resident = ModEntities.REIMU.get().create(level); actors.add(resident);
                resident.moveTo(Vec3.atBottomCenterOf(home.entrance())); resident.setOnGround(true); level.addFreshEntity(resident);
                check.accept("temperature: resident can claim the real shelter", resident.habitat().choose(home));
                for (int i = 0; i < 3; i++) home.deposit(new ItemStack(Items.PAPER));
                resident.moveTo(Vec3.atBottomCenterOf(home.bed(resident.getUUID())));
                var protectedBody = resident.life().temperature();
                check.accept("temperature: real supplied shelter is insulated only inside", protectedBody.sample(resident.blockPosition()).insulated()
                        && !protectedBody.sample(home.entrance().south(2)).insulated());
                protectedBody.load(nearInjury); float protectedHealth = resident.getHealth();
                for (int i = 0; i < 20; i++) protectedBody.tick();
                check.accept("temperature: actual insulation stops further freezing injury", resident.getHealth() == protectedHealth
                        && protectedBody.coldExposure() < nearInjury.getInt("ColdExposure"));
                var roof = origin.above(); var oldRoof = level.getBlockState(roof);
                level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
                check.accept("temperature: removing a real roof part immediately removes insulation", !protectedBody.sample(resident.blockPosition()).insulated());
                level.setBlockAndUpdate(roof, oldRoof);
            }
        } finally {
            actors.forEach(YukkuriEntity::discard);
            if (placed != null) placed.rollback(level);
            level.setBlockAndUpdate(source, oldSource); level.setBlockAndUpdate(wall, oldWall); level.setBlockAndUpdate(wall.above(), oldWallAbove);
            level.setDayTime(time); level.updateSkyBrightness();
            YukkuriConfig.COLD_THRESHOLD.set(oldCold); YukkuriConfig.FREEZE_THRESHOLD.set(oldFreeze); YukkuriConfig.HOT_THRESHOLD.set(oldHot);
        }
    }
    private static void equalBoundary(ServerLevel level, BlockPos pos, ArrayList<YukkuriEntity> actors, BiConsumer<String, Boolean> check) {
        double oldCold = YukkuriConfig.COLD_THRESHOLD.get(), oldFreeze = YukkuriConfig.FREEZE_THRESHOLD.get();
        var subject = ModEntities.REIMU.get().create(level); actors.add(subject);
        subject.moveTo(Vec3.atBottomCenterOf(pos)); subject.setOnGround(true); level.addFreshEntity(subject);
        var life = subject.life().save(); life.putFloat("Nutrition", 100); subject.life().load(life);
        double boundary = subject.life().temperature().sample(pos).felt();
        // Use the exact sampled double so this checks equality, not an almost-equal threshold.
        YukkuriConfig.COLD_THRESHOLD.set(boundary); YukkuriConfig.FREEZE_THRESHOLD.set(boundary);
        var torch = pos.south(5); var previous = level.getBlockState(torch);
        try {
            level.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
            for (int i = 0; i < 60; i++) subject.life().temperature().tick();
            check.accept("temperature: equal cold/freeze boundary both exposes and seeks warmth", subject.life().temperature().coldExposure() > 0
                    && subject.life().temperature().cold() && subject.life().temperature().unsafeSleep()
                    && subject.life().plan() && subject.life().activity().equals("seek_warmth"));
            check.accept("temperature: planned but unreached refuge cannot start sleep", !subject.life().temperature().beginSleepAtRefuge()
                    && !subject.life().temperature().hasTemporarySleepSite() && !subject.life().isSleeping());
        } finally {
            subject.life().stopGoal(); level.setBlockAndUpdate(torch, previous);
            YukkuriConfig.COLD_THRESHOLD.set(oldCold); YukkuriConfig.FREEZE_THRESHOLD.set(oldFreeze);
        }
    }

    /** Actual server goal/navigation ticks: a cold owned home must not repeatedly steal sleep. */
    static void refugeSleep(net.minecraft.gametest.framework.GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = new BlockPos(968, 65, 8); var torch = origin.south(5);
        long previousTime = level.getDayTime();
        boolean previousRain = level.isRaining(), previousThunder = level.isThundering();
        float previousRainLevel = level.getRainLevel(1), previousThunderLevel = level.getThunderLevel(1);
        boolean previousDaylight = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT);
        boolean previousGrief = level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        double oldCold = YukkuriConfig.COLD_THRESHOLD.get(), oldFreeze = YukkuriConfig.FREEZE_THRESHOLD.get(), oldHot = YukkuriConfig.HOT_THRESHOLD.get();
        var previousForced = new java.util.HashSet<Long>(level.getForcedChunks());
        var previousBlocks = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        var actor = new YukkuriEntity[1];
        var home = new ShelterPlacement.Placed[1];
        var trace = new ArrayList<java.util.Map<String, Object>>();
        var cleaned = new boolean[1];
        Runnable cleanup = () -> {
            if (cleaned[0]) return; cleaned[0] = true;
            if (actor[0] != null) actor[0].discard();
            if (home[0] != null) home[0].rollback(level);
            previousBlocks.forEach((pos, state) -> level.setBlockAndUpdate(pos, state));
            level.setWeatherParameters(previousRain ? 0 : 12000, previousRain ? 12000 : 0, previousRain, previousThunder);
            level.setRainLevel(previousRainLevel); level.setThunderLevel(previousThunderLevel);
            level.setDayTime(previousTime); level.updateSkyBrightness();
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(previousDaylight, level.getServer());
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(previousGrief, level.getServer());
            YukkuriConfig.COLD_THRESHOLD.set(oldCold); YukkuriConfig.FREEZE_THRESHOLD.set(oldFreeze); YukkuriConfig.HOT_THRESHOLD.set(oldHot);
            for (int x = 59; x <= 61; x++) for (int z = -1; z <= 1; z++)
                GameTestChunks.setForced(level, x, z, previousForced.contains(net.minecraft.world.level.ChunkPos.asLong(x, z)));
            P0Control.write("temperature-sleep-trace", trace);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo, net.minecraft.gametest.framework.GameTestInfo newInfo, net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        for (int x = 59; x <= 61; x++) for (int z = -1; z <= 1; z++) GameTestChunks.setForced(level, x, z, true);
        helper.onEachTick(() -> {
            if (actor[0] == null || cleaned[0]) return;
            trace.add(java.util.Map.of("tick", helper.getTick(), "position", actor[0].position().toString(),
                    "activity", actor[0].life().activity(), "temporarySleep", actor[0].life().temperature().hasTemporarySleepSite(),
                    "sleepCompletions", actor[0].pet().routine(2)));
        });
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper, 59, 61, -1, 1)).thenExecute(() -> {
            for (var pos : BlockPos.betweenClosed(origin.offset(-7, -1, -7), origin.offset(7, 3, 7))) {
                previousBlocks.put(pos.immutable(), level.getBlockState(pos));
                level.setBlockAndUpdate(pos, pos.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            level.setWeatherParameters(12000, 0, false, false); level.setRainLevel(0); level.setThunderLevel(0);
            level.setDayTime(18000); level.updateSkyBrightness();
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(false, level.getServer());
            YukkuriConfig.COLD_THRESHOLD.set(1.0); YukkuriConfig.FREEZE_THRESHOLD.set(0.0); YukkuriConfig.HOT_THRESHOLD.set(4.0);
            level.getEntitiesOfClass(YukkuriEntity.class, new net.minecraft.world.phys.AABB(origin).inflate(16)).forEach(YukkuriEntity::discard);
            home[0] = ShelterPlacement.place(level, new ShelterPlacement.Plan(origin, ShelterBlock.Kind.CARDBOARD, Direction.SOUTH));
            helper.assertTrue(home[0] != null, "Physical unlined cold home exists");
            actor[0] = ModEntities.REIMU.get().create(level);
            var family = actor[0].family().save(); family.putBoolean("Initialized", true); family.putBoolean("Sterile", true); actor[0].family().load(family);
            actor[0].moveTo(Vec3.atBottomCenterOf(home[0].shelter().entrance())); actor[0].setOnGround(true); level.addFreshEntity(actor[0]);
            helper.assertTrue(actor[0].habitat().choose(home[0].shelter()), "Actor owns the cold home");
            actor[0].moveTo(Vec3.atBottomCenterOf(origin));
            var life = actor[0].life().save(); life.putFloat("Nutrition", 100); life.putFloat("Energy", 50); life.putInt("RestWait", 0); actor[0].life().load(life);
            level.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
        }).thenWaitUntil(() -> helper.assertTrue(actor[0].life().isSleeping() && actor[0].life().temperature().hasTemporarySleepSite(),
                "Waiting for real walk from cold home and normal sleep at warmth"))
        .thenExecute(() -> {
            helper.assertTrue(actor[0].distanceToSqr(Vec3.atBottomCenterOf(origin)) > 1, "Sleep happens after actually leaving the cold home");
            helper.assertTrue(actor[0].pet().routine(2) == 0, "Arrival and brief pause grant no completed-sleep credit");
        }).thenIdle(60).thenExecute(() -> level.setBlockAndUpdate(torch, Blocks.AIR.defaultBlockState()))
        .thenWaitUntil(() -> helper.assertTrue(!actor[0].life().isSleeping(), "Losing actual warmth interrupts temporary sleep"))
        .thenExecute(() -> {
            helper.assertTrue(actor[0].pet().routine(2) == 0, "Interrupted partial sleep grants no sleep credit");
            level.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
        }).thenWaitUntil(() -> helper.assertTrue(actor[0].life().isSleeping() && actor[0].life().temperature().hasTemporarySleepSite(),
                "Waiting for a fresh full sleep after warmth is restored"))
        .thenIdle(160).thenExecute(() -> helper.assertTrue(actor[0].pet().routine(2) == 0, "Fresh sleep still observes the normal minimum duration"))
        .thenWaitUntil(() -> helper.assertTrue(actor[0].pet().routine(2) == 1, "Warm refuge permits one normally completed sleep without home oscillation"))
        .thenExecute(() -> {
            helper.assertTrue(home[0].shelter().owns(actor[0].getUUID()) && actor[0].life().home().pos().equals(origin),
                    "Temporary sleep does not change permanent home or resident ownership");
            helper.assertTrue(actor[0].distanceToSqr(Vec3.atBottomCenterOf(origin)) > 1, "Completion occurs at the reached refuge");
            cleanup.run(); helper.succeed();
        });
    }
    private TemperatureChecks() {}
}
