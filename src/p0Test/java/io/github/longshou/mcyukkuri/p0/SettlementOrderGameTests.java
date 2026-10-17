package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.SettlementOrders;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only integration checks: ordinary goals must pick up, carry and deliver real food. */
@PrefixGameTestTemplate(false)
public final class SettlementOrderGameTests {
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=1100, batch="settlement_order_delivery")
    public static void orderedFoodReachesResidentStock(GameTestHelper helper) {
        run(helper, false);
    }

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=1100, batch="settlement_order_reload")
    public static void interruptedOrderPreservesActualCargo(GameTestHelper helper) {
        run(helper, true);
    }

    private static void run(GameTestHelper helper, boolean reload) {
        var level = helper.getLevel();
        var offered = reload ? Items.APPLE : Items.SHORT_GRASS;
        var origin = new BlockPos(reload ? 5672 : 5608, 65, 8);
        int minChunk = (origin.getX() - 16) >> 4, maxChunk = (origin.getX() + 16) >> 4;
        var area = new AABB(origin).inflate(20);
        long oldDay = level.getDayTime();
        boolean oldDaylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean oldGrief = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        var oldForced = new HashSet<Long>(level.getForcedChunks());
        var actors = new ArrayList<YukkuriEntity>();
        ShelterBlockEntity[] home = new ShelterBlockEntity[1];
        SettlementData.Group[] group = new SettlementData.Group[1];
        ItemEntity[] food = new ItemEntity[1];
        YukkuriEntity[] worker = new YukkuriEntity[1];
        Vec3[] departure = new Vec3[1];
        int[] stock = new int[1];
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            actors.forEach(Entity::discard);
            if (home[0] != null) home[0].rollback();
            level.getEntitiesOfClass(ItemEntity.class, area).forEach(Entity::discard);
            if (group[0] != null) SettlementData.get(level).remove(group[0].id);
            level.setDayTime(oldDay);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(oldDaylight, level.getServer());
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(oldGrief, level.getServer());
            for (int x = minChunk; x <= maxChunk; x++) for (int z = -1; z <= 1; z++)
                GameTestChunks.setForced(level, x, z, oldForced.contains(ChunkPos.asLong(x, z)));
        };
        helper.testInfo.addListener(new GameTestListener() {
            public void testStructureLoaded(GameTestInfo info) {}
            public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) {}
        });
        ShelterGameTests.daylightFixture(level);
        for (int x = minChunk; x <= maxChunk; x++) for (int z = -1; z <= 1; z++)
            GameTestChunks.setForced(level, x, z, true);
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper, minChunk, maxChunk, -1, 1))
            .thenExecute(() -> {
                for (var pos : BlockPos.betweenClosed(origin.offset(-15, -1, -15), origin.offset(15, 4, 15)))
                    level.setBlockAndUpdate(pos, pos.getY() == 64 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
                // A real roofed cave provides a level entrance and a visible six-block delivery route.
                NaturalShelterFixture.cave(level, origin, Direction.SOUTH);
                var placed = ShelterPlacement.place(level, new ShelterPlacement.Plan(origin, ShelterBlock.Kind.BURROW, Direction.SOUTH));
                helper.assertTrue(placed != null, "Complete natural cave provides a physical home");
                home[0] = placed.shelter();
                home[0].initializeEncounter(1);
                var homeTag = home[0].saveWithFullMetadata(level.registryAccess());
                homeTag.putInt("Lining", 3);
                home[0].loadWithComponents(homeTag, level.registryAccess());
                worker[0] = spawn(helper, origin.south(2), actors);
                var leader = spawn(helper, origin.south(3).east(2), actors);
                group[0] = new SettlementData.Group(UUID.randomUUID(), SettlementData.Kind.FOREST, origin.south(3), leader.getUUID());
                SettlementData.get(level).add(group[0]);
                leader.settlement().join(group[0]);
                worker[0].settlement().join(group[0]);
                helper.assertTrue(worker[0].habitat().choose(home[0]), "Member owns an actual reachable bed");
                helper.assertTrue(!worker[0].habitat().active(), "Fixture has not started household chores before issue");
                stock[0] = home[0].food();
                departure[0] = worker[0].position();
                var source = origin.south(8);
                food[0] = new ItemEntity(level, source.getX() + .5, source.getY(), source.getZ() + .5, new ItemStack(offered));
                food[0].setNoPickUpDelay();
                helper.assertTrue(level.addFreshEntity(food[0]), "One real edible item is on the ground");
                helper.assertTrue(leader.settlement().orders().issue(worker[0], SettlementOrders.Kind.FORAGE), "Recognized active leader issues the real forage order");
                helper.assertTrue(!worker[0].habitat().canUse(), "Household chores cannot preempt assigned collection");
                // The worker remains under ordinary AI; its already-issued job is independent
                // of later leader movement, leisure and unrelated social event discovery.
                leader.setNoAi(true);
            }).thenWaitUntil(() -> helper.assertTrue(worker[0] != null && worker[0].settlement().orders().carrying().is(offered),
                    "Waiting for ordinary AI to contact and pick up the actual food"))
            .thenExecute(() -> {
                helper.assertTrue(!food[0].isAlive(), "Pickup consumes the original item entity");
                helper.assertTrue(worker[0].position().distanceToSqr(departure[0]) > 9, "Worker actually traversed the collection route");
                helper.assertTrue(home[0].food() == stock[0], "Remote pickup has not credited home stock");
                helper.assertTrue(worker[0].settlement().orders().carrying().getCount() == 1, "Exactly one physical portion is in transit");
                if (reload) {
                    // Simulate the component's unload/load boundary after the real pickup, before delivery.
                    var saved = worker[0].settlement().orders().save();
                    worker[0].settlement().orders().load(saved);
                    helper.assertTrue(!worker[0].settlement().orders().active()
                            && worker[0].settlement().orders().carrying().is(offered), "Reload cancels transient orders but preserves their cargo");
                    worker[0].settlement().orders().clock();
                    worker[0].settlement().orders().clock();
                    int apples = level.getEntitiesOfClass(ItemEntity.class, area, item -> item.getItem().is(offered))
                            .stream().mapToInt(item -> item.getItem().getCount()).sum();
                    helper.assertTrue(apples == 1 && worker[0].settlement().orders().carrying().isEmpty(), "Repeated interruption recovery drops exactly one real portion");
                    helper.assertTrue(home[0].food() == stock[0], "Interrupted delivery awards no stock");
                }
            }).thenWaitUntil(() -> helper.assertTrue(reload || home[0].food() == stock[0] + 1
                    && worker[0].settlement().orders().carrying().isEmpty() && !worker[0].settlement().orders().active(),
                    "Waiting for ordinary AI to walk home and deposit one food portion"))
            .thenExecute(() -> {
                if (!reload) {
                    helper.assertTrue(worker[0].distanceToSqr(Vec3.atBottomCenterOf(home[0].bed(worker[0].getUUID()))) < 1,
                            "Food arrives only at the resident's physical bed");
                    helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, area, item -> item.getItem().is(offered)).isEmpty(),
                            "Successful delivery leaves no duplicate dropped food");
                    helper.assertTrue(home[0].lining() == 3, "Food delivery does not silently become bedding");
                }
                P0Control.write(reload ? "settlement-order-reload-checks" : "settlement-order-delivery-checks",
                        List.of(java.util.Map.of("pass", true, "result", reload ? "real collected cargo survives cancellation without duplication" : "real AI pickup and return increased resident stock by exactly one")));
                cleanup.run();
            }).thenSucceed();
    }

    private static YukkuriEntity spawn(GameTestHelper helper, BlockPos pos, List<YukkuriEntity> actors) {
        var entity = ModEntities.REIMU.get().create(helper.getLevel());
        helper.assertTrue(entity != null, "Test adult was created");
        actors.add(entity);
        var growth = entity.family().save();
        growth.putBoolean("Initialized", true); growth.putBoolean("Sterile", true); growth.putString("Stage", "ADULT");
        entity.family().load(growth);
        var life = entity.life().save();
        life.putFloat("Nutrition", 100); life.putFloat("Energy", 100); life.putFloat("Hygiene", 100);
        life.putFloat("Bladder", 0); life.putFloat("Dryness", 0); life.putFloat("Wetness", 0); life.putBoolean("Accessory", true);
        life.putInt("RestWait", 2400); life.putInt("SocialWait", 2400); life.put("Wintering", new CompoundTag());
        entity.life().load(life);
        entity.moveTo(Vec3.atBottomCenterOf(pos));
        helper.assertTrue(helper.getLevel().addFreshEntity(entity), "Test adult was added to the live level");
        entity.move(MoverType.SELF, new Vec3(0, -.125, 0));
        helper.assertTrue(entity.onGround() && helper.getLevel().noCollision(entity), "Real collision grounds the adult before issuing an order");
        return entity;
    }

    private SettlementOrderGameTests() {}
}
