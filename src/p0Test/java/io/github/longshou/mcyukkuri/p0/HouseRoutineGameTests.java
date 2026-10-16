package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote engine check: unmodified AI must perform the route, contact and resource transfer itself. */
@PrefixGameTestTemplate(false)
public final class HouseRoutineGameTests {
    private static final BlockPos HOME = new BlockPos(1528,65,8);
    private static final BlockPos SCRAPS = HOME.offset(-1,0,-2), LINT = HOME.offset(-2,0,-2);

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=2000,batch="house_ecology")
    public static void dayRestAndEggCareLeadToRealNightForaging(GameTestHelper helper) {
        var level = helper.getLevel();
        HouseholdEcologyGameTests.acquireEnvironment(level);
        long time = level.getDayTime();
        boolean daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean forced = level.getForcedChunks().contains(ChunkPos.asLong(HOME.getX() >> 4,HOME.getZ() >> 4));
        var actors = new ArrayList<YukkuriEntity>();
        var heldEggs = new ArrayList<ItemStack>();
        var blocks = new LinkedHashMap<BlockPos,BlockState>();
        var trace = new ArrayList<Map<String,Object>>();
        HouseNestBlockEntity[] nest = {null}; FamilyData.Brood[] brood = {null};
        boolean[] cleaned = {false}, slept = {false,false}, tended = {false}, picked = {false}, carried = {false}, releasedCare = {false};
        int[] stage = {0}, nightStock = {0}, outsideTicks = {0};
        Runnable cleanup = () -> {
            if (cleaned[0]) return; cleaned[0] = true;
            try {
            actors.forEach(Entity::discard);
            if (brood[0] != null) FamilyData.get(level).cancel(brood[0]);
            if (nest[0] != null) nest[0].clean(true);
            blocks.forEach(level::setBlockAndUpdate);
            level.getEntitiesOfClass(ItemEntity.class,new AABB(HOME).inflate(8)).forEach(Entity::discard);
            level.setDayTime(time); level.updateSkyBrightness();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());
            level.setChunkForced(HOME.getX() >> 4,HOME.getZ() >> 4,forced);
            P0Control.write("house-routine-trace",trace);
            } finally {HouseholdEcologyGameTests.releaseEnvironment(level);}
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        GameTestChunks.setForced(level,HOME.getX() >> 4,HOME.getZ() >> 4,true);
        helper.onEachTick(() -> {
            if (cleaned[0] || stage[0] == 0) return;
            for (int i=0;i<actors.size();i++) {
                var actor = actors.get(i); String activity = actor.getBehaviorId();
                var box = actor.getBoundingBox();
                var feet = new AABB(box.minX+.01,box.minY-.02,box.minZ+.01,box.maxX-.01,box.minY+.005,box.maxZ-.01);
                if (stage[0] == 1) {
                    slept[i] |= activity.equals("house_sleep") && actor.house().sleeping() && actor.onGround()
                            && actor.blockPosition().equals(HOME) && actor.getX() < HOME.getX()+.57
                            && actor.getY() < HOME.getY()+.1 && level.noCollision(actor)
                            && level.getBlockCollisions(actor,feet).iterator().hasNext();
                    tended[0] |= activity.equals("house_tend");
                } else {
                    picked[0] |= activity.equals("house_pickup");
                    carried[0] |= actor.house().carrying().is(ModItems.FOOD_SCRAP.get());
                }
            }
            if (stage[0] == 2) {
                outsideTicks[0] = actors.stream().noneMatch(e -> e.blockPosition().equals(HOME)) ? outsideTicks[0]+1 : 0;
                if (outsideTicks[0] >= 2) {
                    helper.assertTrue(nest[0].careTicks()==0,"care credit must clear after both actual carers leave the nest: "+state(actors));
                    releasedCare[0] = true;
                }
            }
            if (helper.getTick()%20 == 0) trace.add(Map.of("tick",helper.getTick(),"phase",stage[0],
                    "actors",state(actors),
                    "food",nest[0].food(),"care",nest[0].careTicks(),"progress",nest[0].progress(),
                    "source",remaining(level,SCRAPS),"carried",carriedFood(actors)));
        });
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper,95,95,0,0)).thenExecute(() -> {
            for (var p : BlockPos.betweenClosed(HOME.offset(-5,-1,-5),HOME.offset(5,4,5))) {
                var pos = p.immutable(); blocks.put(pos,level.getBlockState(pos));
                level.setBlockAndUpdate(pos,pos.getY()==HOME.getY()-1 || pos.getY()==HOME.getY()+3
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
            level.setDayTime(6000); level.updateSkyBrightness();
            level.setBlockAndUpdate(HOME,ModBlocks.HOUSE_NEST.get().defaultBlockState());
            nest[0] = (HouseNestBlockEntity) level.getBlockEntity(HOME);
            for (int i=0;i<HouseNestBlockEntity.MATERIAL_COST;i++) helper.assertTrue(nest[0].deposit(new ItemStack(ModItems.NEST_LINT.get())),"real lint fills the lining");
            for (int i=0;i<12;i++) nest[0].deposit(new ItemStack(ModItems.FOOD_SCRAP.get()));
            var mother = parent(level,false,HOME.getX()+.2,HOME.getZ()-1.8); actors.add(mother);
            var father = parent(level,true,HOME.getX()+.8,HOME.getZ()-1.8); actors.add(father);
            helper.assertTrue(mother.house().choose(nest[0]) && father.house().choose(nest[0]),"both real parents can reach the roofed entrance from outside");
            YukkuriWorldData.get(level).relate(mother.getUUID(),father.getUUID(),30);
            YukkuriWorldData.get(level).relate(father.getUUID(),mother.getUUID(),30);
            brood[0] = FamilyData.get(level).conceive(mother,father,FamilyData.BirthKind.EGG,GlobalPos.of(level.dimension(),HOME));
            helper.assertTrue(brood[0] != null,"the setup reserves real eggs from two eligible, compatible parents");
            // Install this valid existing clutch through the supported old-save migration path.
            // No progress, care credit, navigation, goal, or actor position is changed after setup.
            var saved = nest[0].saveWithFullMetadata(level.registryAccess());
            saved.remove("EggSlots"); saved.putUUID("Brood",brood[0].id); saved.putInt("Arrivals",2);
            nest[0].loadWithComponents(saved,level.registryAccess());
            mother.setNoAi(false); father.setNoAi(false); stage[0] = 1;
        }).thenWaitUntil(() -> {
            helper.assertTrue(slept[0] && slept[1],"both maximum-size parents must really enter the left sleep area and rest on its floor: "+state(actors));
            helper.assertTrue(tended[0] && nest[0].eggs()>0 && brood[0].children.stream().anyMatch(child -> child.eggElapsed>=30),
                    "the untouched house AI must complete an actual care contact and advance its own nest eggs: care="+nest[0].careTicks()+" / "+state(actors));
        }).thenExecute(() -> {
            // The player may move a clutch into inventory; the same adults are now free to forage.
            for (int i=0;i<HouseNestBlockEntity.EGG_CAP;i++) { var egg=nest[0].extractEgg(); if(!egg.isEmpty())heldEggs.add(egg); }
            helper.assertTrue(heldEggs.size()==brood[0].children.size() && nest[0].eggs()==0,"withdrawal moves each actual egg once without deleting its family reservation");
            level.setDayTime(18000); level.updateSkyBrightness();
            nightStock[0] = nest[0].food();
            placeSupplies(helper,SCRAPS,new ItemStack(ModItems.FOOD_SCRAP.get(),3));
            placeSupplies(helper,LINT,new ItemStack(ModItems.NEST_LINT.get()));
            stage[0] = 2;
        }).thenWaitUntil(() -> {
            helper.assertTrue(picked[0] && carried[0] && releasedCare[0] && nest[0].food()>nightStock[0] && remaining(level,SCRAPS)<3,
                    "dusk must lead to real pickup from the player's pile, visible carriage and a food-stock increase: pickup="+picked[0]+", carried="+carried[0]+", releasedCare="+releasedCare[0]+", source="+remaining(level,SCRAPS)+", food="+nest[0].food()+", care="+nest[0].careTicks()+", progress="+nest[0].progress()+" / "+state(actors));
            helper.assertTrue(nest[0].food()+remaining(level,SCRAPS)+carriedFood(actors)==nightStock[0]+3,
                    "each supplied scrap must remain in exactly one of the pile, an actual mouth, or nest stock");
            helper.assertTrue(level.getBlockEntity(LINT) instanceof SupplyPileBlockEntity pile && pile.contents().is(ModItems.NEST_LINT.get()) && pile.contents().getCount()==1,
                    "a built nest leaves surplus lining in its ground pile instead of eating it as food");
        }).thenSucceed();
    }

    private static YukkuriEntity parent(ServerLevel level,boolean marisa,double x,double z) {
        var actor = (marisa?ModEntities.HOUSE_MARISA.get():ModEntities.HOUSE_REIMU.get()).create(level);
        if (actor == null) throw new IllegalStateException("House parent allocation failed");
        actor.setPersistenceRequired(); actor.setNoAi(true); actor.moveTo(x,HOME.getY(),z,0,0); actor.setOnGround(true);
        var family = actor.family().save(); family.putBoolean("Initialized",true); family.putFloat("AdultScale",1.2f); family.putFloat("Scale",1.2f); actor.family().load(family);
        var life = actor.life().save(); life.putFloat("Nutrition",100); life.putFloat("Energy",90); life.putFloat("Hygiene",100); actor.life().load(life);
        if (!level.addFreshEntity(actor)) throw new IllegalStateException("House parent insertion failed");
        return actor;
    }
    private static void placeSupplies(GameTestHelper helper,BlockPos position,ItemStack stack) {
        var level = helper.getLevel(); int count = stack.getCount(); var item = stack.getItem();
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new com.mojang.authlib.GameProfile(UUID.fromString("a1bd6324-ae96-4ec5-9dc3-c399234c02a4"),"HouseSupplies"));
        player.setGameMode(GameType.SURVIVAL); player.setShiftKeyDown(false);
        player.moveTo(position.getX()+.5,position.getY(),position.getZ()-1,0,0);
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        var hit = new BlockHitResult(Vec3.atBottomCenterOf(position),Direction.UP,position.below(),false);
        for (int i=0;i<count;i++) {
            var held = player.getMainHandItem(); int before = held.getCount();
            var result = held.useOn(new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
            helper.assertTrue(result.consumesAction() && player.getMainHandItem().getCount()==before-1,
                    "ordinary survival use-on must consume exactly one real supply per ground placement");
        }
        helper.assertTrue(level.getBlockEntity(position) instanceof SupplyPileBlockEntity pile
                        && pile.contents().is(item) && pile.contents().getCount()==count,
                "each user-placed resource must appear in the actual persistent pile before AI can take it");
    }
    private static int remaining(ServerLevel level,BlockPos position) {
        return level.getBlockEntity(position) instanceof SupplyPileBlockEntity pile ? pile.contents().getCount() : 0;
    }
    private static int carriedFood(List<YukkuriEntity> actors) {
        return actors.stream().map(YukkuriEntity::house).map(h -> h.carrying())
                .filter(stack -> stack.is(ModItems.FOOD_SCRAP.get())).mapToInt(ItemStack::getCount).sum();
    }
    private static String state(List<YukkuriEntity> actors) {
        return actors.stream().map(e -> e.position()+" behavior="+e.getBehaviorId()+" phase="+e.house().activity()
                +" grounded="+e.onGround()+" nutrition="+e.life().nutrition()+" energy="+e.life().energy()
                +" active="+e.house().active()+" house="+e.house().save()).toList().toString();
    }
    private HouseRoutineGameTests() {}
}
