package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatShoreForage;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote engine fixture: no remote harvest, interruption/occlusion, finite crop yield and real cargo capacity. */
@PrefixGameTestTemplate(false)
public final class HatShoreForageGameTests {
    private static final int X = 5184;
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=1000, batch="aquatic_shore")
    public static void shore_food_requires_real_arrival_and_uninterrupted_contact(GameTestHelper helper) {
        var level = helper.getLevel(); level.setChunkForced(X >> 4, 0, true); level.setChunkForced((X+15) >> 4, 0, true);
        for (int x=0; x<=15; x++) for (int z=0; z<=15; z++) {
            level.setBlock(new BlockPos(X+x,64,z), Blocks.STONE.defaultBlockState(),3);
            level.setBlock(new BlockPos(X+x,65,z), x<5 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.WATER.defaultBlockState(),3);
            for (int y=66; y<=70; y++) level.setBlock(new BlockPos(X+x,y,z), Blocks.AIR.defaultBlockState(),3);
        }
        var bush = new BlockPos(X+4,66,7); var obstacle = new BlockPos(X+5,66,7);
        var ripe = Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE,3);
        level.setBlock(bush,ripe,3);
        var parent = ModEntities.MARISA.get().create(level);
        helper.assertTrue(parent!=null,"a real aquatic parent can be allocated");
        WaddleGameTests.clearGoals(parent); parent.setPersistenceRequired();
        parent.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        parent.moveTo(X+4.5,66,8.5,0,0); parent.setOnGround(true); level.addFreshEntity(parent);
        var boat = HatBoatEntity.createFor(parent,new BlockPos(X+5,65,8));
        helper.assertTrue(boat!=null,"parent boards its own actual hat at the bank");
        boat.cargo().givePaddle(new ItemStack(Items.STICK),false);
        // Initial arrangement is deliberately several real water blocks away. The test never teleports it to the plant.
        boat.setPos(X+10.5,66.025,8.5);
        var forage = new HatShoreForage(parent);
        int[] stage={0}, ticks={0}, contacts={0}; boolean[] cleaned={false}, travelled={false};
        Runnable cleanup = () -> {
            if(cleaned[0])return; cleaned[0]=true; forage.stop(); parent.discard(); boat.discard();
            level.setChunkForced(X >> 4,0,false); level.setChunkForced((X+15) >> 4,0,false);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
        });
        helper.onEachTick(() -> {
            if(cleaned[0])return; ticks[0]++;
            forage.tick(boat);
            if(boat.getX()<X+9)travelled[0]=true;
            if(stage[0]==0) {
                if(ticks[0]<20)helper.assertTrue(boat.cargo().count()==0&&level.getBlockState(bush).equals(ripe),"a plant is never harvested remotely while sailing toward it");
                if(forage.harvesting()&&++contacts[0]>=8) {
                    helper.assertTrue(boat.cargo().count()==0&&level.getBlockState(bush).equals(ripe),"partial mouth contact has reserved no berries");
                    forage.stop(); level.setBlock(obstacle,Blocks.STONE.defaultBlockState(),3); stage[0]=1; ticks[0]=0;
                }
            } else if(stage[0]==1) {
                helper.assertTrue(boat.cargo().count()==0&&level.getBlockState(bush).equals(ripe),"a blocked water berth cannot harvest through the bank");
                if(ticks[0]>=25){level.setBlock(obstacle,Blocks.AIR.defaultBlockState(),3);stage[0]=2;ticks[0]=0;}
            } else if(stage[0]==2&&boat.cargo().count()>0) {
                helper.assertTrue(travelled[0]&&parent.getVehicle()==boat,"the same parent physically travelled to shore and remained aboard");
                helper.assertTrue(boat.cargo().count()==2&&level.getBlockState(bush).getValue(SweetBerryBushBlock.AGE)==1,"one ripe bush becomes exactly two real stored berries and a harvested bush");
                helper.assertTrue(boat.cargo().items().stream().allMatch(s->s.is(Items.SWEET_BERRIES)),"the source fruit identity is preserved");
                boat.cargo().store(new ItemStack(Items.APPLE,5),5,false); level.setBlock(bush,ripe,3); forage.stop(); stage[0]=3;ticks[0]=0;
            } else if(stage[0]==3) {
                helper.assertTrue(boat.cargo().count()==7&&level.getBlockState(bush).equals(ripe),"one empty cargo place cannot consume a two-berry harvest");
                if(ticks[0]>=60) {
                    level.setBlock(bush,ripe.setValue(SweetBerryBushBlock.AGE,1),3); forage.stop(); forage.tick(boat);
                    helper.assertTrue(boat.cargo().count()==7&&level.getBlockState(bush).getValue(SweetBerryBushBlock.AGE)==1,"an immature bush is never a food source");
                    helper.succeed();
                }
            }
        });
    }
    private HatShoreForageGameTests() {}
}
