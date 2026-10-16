package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Remote-only: real local precipitation and real server ticks, without changing production clocks. */
final class ShellWeatherChecks {
    static void run(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = new BlockPos(3545, 65, 9);
        var roof = pos.above(3);
        var beforeBlocks = new LinkedHashMap<BlockPos, BlockState>();
        var beforeBiomes = new LinkedHashMap<BlockPos, String>();
        boolean forced = level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(221, 0));
        boolean rain = level.isRaining(), thunder = level.isThundering();
        float rainLevel = level.getRainLevel(1), thunderLevel = level.getThunderLevel(1);
        long time = level.getDayTime();
        var actor = new YukkuriEntity[1];
        var initialAge = new long[1];
        var done = new boolean[1];
        Runnable cleanup = () -> {
            if (done[0]) return;
            done[0] = true;
            if (actor[0] != null) actor[0].discard();
            beforeBlocks.forEach(level::setBlockAndUpdate);
            beforeBiomes.forEach((cell, id) -> fillBiome(level, cell, cell.offset(3, 3, 3), id));
            level.setDayTime(time); level.updateSkyBrightness();
            level.setWeatherParameters(rain ? 0 : 12000, rain ? 12000 : 0, rain, thunder);
            level.setRainLevel(rainLevel); level.setThunderLevel(thunderLevel);
            GameTestChunks.setForced(level, 221, 0, forced);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo, net.minecraft.gametest.framework.GameTestInfo newInfo, net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        GameTestChunks.setForced(level, 221, 0, true);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(pos), "waiting for shell weather arena"))
            .thenExecute(() -> {
                // BiomeManager samples eight neighboring quart cells after subtracting two blocks.
                // Save those exact cells; sampling a single block does not identify every original cell.
                var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
                for (int x=3540;x<=3544;x+=4) for (int y=60;y<=64;y+=4) for (int z=4;z<=8;z+=4) {
                    var cell = new BlockPos(x,y,z);
                    beforeBiomes.put(cell, registry.getKey(level.getChunkAt(cell).getNoiseBiome(x>>2,y>>2,z>>2).value()).toString());
                }
                for (var p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, 31, 2))) {
                    beforeBlocks.put(p.immutable(), level.getBlockState(p));
                    level.setBlockAndUpdate(p, p.getY() == 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
                biome(level, "minecraft:plains");
                level.setDayTime(6000); level.updateSkyBrightness();
                level.setWeatherParameters(0, 12000, true, false); level.setRainLevel(1); level.setThunderLevel(0);
                var y = ModEntities.MARISA.get().create(level); actor[0] = y;
                y.subspecies().set(SubspeciesLife.Kind.SHELL_MARISA, SubspeciesLife.Congenital.NORMAL);
                y.moveTo(pos.getX()+.5, pos.getY(), pos.getZ()+.5, 0, 0);
                WaddleGameTests.clearGoals(y); y.setPersistenceRequired(); level.addFreshEntity(y);
                var life = y.life().save(); life.putFloat("Nutrition", 90); life.putFloat("Energy", 90); y.life().load(life);
                initialAge[0] = y.life().save().getLong("AgeTicks");
                helper.assertTrue(y.shellRetraction().exposedRain(), "open plains receives actual rain");
                level.setBlockAndUpdate(roof, Blocks.STONE.defaultBlockState());
                helper.assertTrue(!y.shellRetraction().exposedRain(), "roof blocks the rain trigger");
                level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
                biome(level, "minecraft:desert");
                helper.assertTrue(level.getBiome(pos).is(net.minecraft.world.level.biome.Biomes.DESERT), "all local biome samples are desert");
                helper.assertTrue(!y.shellRetraction().exposedRain(), "desert weather is not local rain");
                biome(level, "minecraft:snowy_plains");
                helper.assertTrue(level.getBiome(pos).is(net.minecraft.world.level.biome.Biomes.SNOWY_PLAINS), "all local biome samples are snowy plains");
                helper.assertTrue(!y.shellRetraction().exposedRain(), "snowfall is not local rain");
                biome(level, "minecraft:plains");
            }).thenIdle(45).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(y.shellRetraction().frame() == 36 && !y.isPanicking(), "rain holds inside without creating fright");
                helper.assertTrue(y.shellRetraction().protects(level.damageSources().mobAttack(y))
                        && y.shellRetraction().protects(level.damageSources().fall()), "real withdrawn shell protects against attack and impact");
                helper.assertTrue(!y.shellRetraction().protects(level.damageSources().drown())
                        && !y.shellRetraction().protects(level.damageSources().inFire()), "shell does not halve water or fire damage");
                helper.assertTrue(!y.shellRetraction().protects(level.damageSources().freeze())
                        && !y.shellRetraction().protects(level.damageSources().starve())
                        && !y.shellRetraction().protects(level.damageSources().generic()), "shell does not protect from freezing, starvation, thirst or generic internal injury");
                var spice = new net.minecraft.world.damagesource.DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                        .getHolderOrThrow(io.github.longshou.mcyukkuri.life.Consumables.SPICE_DAMAGE));
                helper.assertTrue(!y.shellRetraction().protects(spice), "shell does not protect from swallowed irritants");
                var uninjuredBody = y.body().save();
                float health = y.getHealth(); y.invulnerableTime = 0; y.hurt(level.damageSources().freeze(), 1);
                helper.assertTrue(y.getHealth() == health-1, "withdrawn body takes full actual freezing damage");
                health = y.getHealth(); y.invulnerableTime = 0; y.hurt(level.damageSources().generic(), 1);
                helper.assertTrue(y.getHealth() == health-1, "withdrawn body takes full actual generic internal damage");
                health = y.getHealth(); y.invulnerableTime = 0; y.hurt(level.damageSources().fall(), 1);
                helper.assertTrue(y.getHealth() == health-.5f, "withdrawn real shell halves an actual physical impact");
                // Keep later weather assertions independent of periodic pain from these test impacts.
                y.body().load(uninjuredBody); y.syncBody();
            }).thenIdle(80).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(y.shellRetraction().frame() == 36, "longer rain holds instead of replaying the clip");
                helper.assertTrue(y.life().save().getLong("AgeTicks") >= initialAge[0]+120 && y.life().wetness() > 0,
                        "physiology continues during rain withdrawal");
                level.setBlockAndUpdate(roof, Blocks.STONE.defaultBlockState());
            }).thenIdle(2).thenExecute(() -> helper.assertTrue(actor[0].shellRetraction().frame() >= 64,
                    "new shelter begins the existing outward phase"))
            .thenIdle(48).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(!y.shellRetraction().active() && !y.getAnimationId().equals("shell_retract"), "shelter restores the ordinary pose");
                level.setBlockAndUpdate(roof, Blocks.AIR.defaultBlockState());
            }).thenIdle(40).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(y.shellRetraction().frame() == 36, "return to exposed rain withdraws again");
                helper.assertTrue(y.playAction("greet"), "player action is accepted during rain");
            }).thenIdle(3).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(!y.shellRetraction().active() && y.getAnimationId().equals("greet"), "player command owns its pose during rain");
                helper.assertTrue(y.playAction("shell_retract") && y.getAnimationId().equals("shell_retract"), "shell command is not treated as an organ retraction");
                y.life().takeAccessory(null);
                helper.assertTrue(!y.playAction("shell_retract") && !y.shellRetraction().protects(level.damageSources().mobAttack(y)), "missing shell rejects command and protection");
            }).thenIdle(2).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(!y.shellRetraction().active() && !y.getAnimationId().equals("shell_retract"), "losing the shell cancels an existing shell command");
                var uninjuredBody = y.body().save();
                y.hurt(level.damageSources().generic(), 1);
                helper.assertTrue(!y.getAnimationId().equals("shell_retract"), "fright without a real shell cannot withdraw");
                y.body().load(uninjuredBody); y.syncBody();
                // Restore the actual accessory state; no accessory item is spawned or duplicated by this fixture.
                var life = y.life().save(); life.putBoolean("Accessory", true); life.remove("AccessoryToken"); y.life().load(life);
                y.playAction("idle");
            }).thenIdle(40).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(y.shellRetraction().active(), "restored shell can withdraw");
                var life = y.life().save(); life.putFloat("Nutrition", 29); y.life().load(life);
            }).thenIdle(50).thenExecute(() -> {
                var y = actor[0];
                helper.assertTrue(!y.shellRetraction().active() && !y.isPanicking() && !y.shellRetraction().controlsMovement(), "hunger releases rain withdrawal instead of trapping the body");
                var life = y.life().save(); life.putFloat("Nutrition", 90); y.life().load(life);
            }).thenIdle(40).thenExecute(() -> {
                helper.assertTrue(actor[0].shellRetraction().frame() == 36, "fed body can withdraw again during the same rain");
                level.setWeatherParameters(12000, 0, false, false); level.setRainLevel(0);
            }).thenIdle(2).thenExecute(() -> helper.assertTrue(actor[0].shellRetraction().frame() >= 64,
                    "rain ending begins the outward phase"))
            .thenIdle(48).thenExecute(() -> {
                helper.assertTrue(!actor[0].shellRetraction().active(), "clear weather completes the release");
                var y = actor[0];
                y.setRemainingFireTicks(20);
                helper.assertTrue(!y.shellRetraction().available() && !y.playAction("shell_retract"), "burning body must escape instead of taking a shell command");
                y.clearFire();
                var saved = new net.minecraft.nbt.CompoundTag(); y.saveWithoutId(saved);
                var restored = ModEntities.MARISA.get().create(level); restored.load(saved);
                helper.assertTrue(!restored.shellRetraction().active() && !restored.isPanicking(), "reload discards transient rain and fright state");
                cleanup.run(); helper.succeed();
            });
    }

    private static void biome(net.minecraft.server.level.ServerLevel level, String id) {
        fillBiome(level, new BlockPos(3540,60,4), new BlockPos(3547,67,11), id);
    }
    private static void fillBiome(net.minecraft.server.level.ServerLevel level, BlockPos min, BlockPos max, String id) {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4),
                "fillbiome " + min.getX()+" "+min.getY()+" "+min.getZ()+" "+max.getX()+" "+max.getY()+" "+max.getZ()+" "+id);
    }
}
