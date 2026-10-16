package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real AI/physics regressions for the user's ledge and moving-recipient reports. */
@PrefixGameTestTemplate(false)
public final class CareTerrainGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="care_terrain")
    public static void feedAcrossLedge(GameTestHelper helper) { scenario(helper, 3200, true); }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="care_terrain")
    public static void feedMovingChild(GameTestHelper helper) { scenario(helper, 3232, false); }

    private static void scenario(GameTestHelper helper,int x,boolean ledge) {
        var level=helper.getLevel(); int chunk=x>>4;
        boolean forced=level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk,0));
        var terrain=new HashMap<BlockPos,BlockState>();
        YukkuriEntity[] actors=new YukkuriEntity[2];
        boolean[] finished={false},contact={false},waited={false},climbed={false};
        var area=new AABB(x,63,0,x+16,74,16);
        Runnable cleanup=()->{
            if(finished[0])return; finished[0]=true;
            var ids=new java.util.HashSet<java.util.UUID>();
            for(var actor:actors)if(actor!=null){ids.add(actor.getUUID());actor.discard();}
            FamilyData.get(level).rollbackEncounter(ids);YukkuriWorldData.get(level).rollbackEncounter(ids);
            level.getEntitiesOfClass(ItemEntity.class,area).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);GameTestChunks.setForced(level,chunk,0,forced);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){
                if(actors[0]!=null)P0Control.write("care-terrain-"+ledge,java.util.Map.of("parent",actors[0].saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString(),"child",actors[1].saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString()));
                cleanup.run();
            }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
        });
        GameTestChunks.setForced(level,chunk,0,true);
        helper.onEachTick(()->{
            if(finished[0]||actors[0]==null)return;
            contact[0]|=actors[0].family().careContactProgress()>0&&FamilyFeedingContact.mouthsMeet(actors[0],actors[1]);
            waited[0]|=actors[1].careApproach().waiting();
            climbed[0]|=actors[0].getY()>65.8;
        });
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,chunk,chunk,0,0)).thenExecute(()->{
            for(var pos:BlockPos.betweenClosed(new BlockPos(x,64,0),new BlockPos(x+15,70,15))){
                terrain.put(pos.immutable(),level.getBlockState(pos));
                boolean floor=pos.getY()==64 || ledge&&pos.getY()==65&&pos.getX()>=x+8;
                level.setBlockAndUpdate(pos,floor?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            actors[0]=spawn(level,x+6.8,65,8.5,90);actors[1]=spawn(level,x+8.5,ledge?66:65,8.5,40);
            actors[1].family().generatedChild(actors[0],null);
            var family=actors[1].family().save();family.putLong("Age",18000);family.putLong("Nourishment",16000);family.putString("Stage","JUVENILE");family.putFloat("Scale",.42f);actors[1].family().load(family);actors[1].syncFamily();actors[1].refreshDimensions();
            FamilyData.get(level).register(actors[0]);FamilyData.get(level).register(actors[1]);
            var food=new ItemEntity(level,actors[0].getX(),65,8.5,new ItemStack(ModItems.YUKKURI_STEM.get()));food.setNoPickUpDelay();level.addFreshEntity(food);
            if(!ledge)actors[1].getNavigation().moveTo(x+13.5,65,8.5,1.0);
        }).thenWaitUntil(()->helper.assertTrue(actors[1].life().nutrition()>=55&&contact[0]&&waited[0]&&(!ledge||climbed[0]),
                "normal AI must route onto the ledge / cooperate with a moving child, sustain real mouth contact, then swallow"))
          .thenExecute(cleanup).thenSucceed();
    }
    private static YukkuriEntity spawn(ServerLevel level,double x,double y,double z,float nutrition){
        var actor=ModEntities.REIMU.get().create(level);actor.moveTo(x,y,z,0,0);actor.setPersistenceRequired();actor.setInvulnerable(true);
        var life=actor.life().save();life.putFloat("Nutrition",nutrition);life.putFloat("Energy",95);life.putFloat("Bladder",0);life.putFloat("Dryness",0);life.putInt("RestWait",2000);actor.life().load(life);
        var daily=actor.dailyLife().save();daily.putInt("Cooldown",2000);daily.putInt("Boredom",0);daily.putInt("Affection",0);actor.dailyLife().load(daily);
        level.addFreshEntity(actor);return actor;
    }
}
