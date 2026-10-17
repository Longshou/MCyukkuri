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
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="care_terrain")
    public static void feedAroundWall(GameTestHelper helper) { scenario(helper, 3264, false, true); }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="care_terrain")
    public static void blockedCareReleasesBothActors(GameTestHelper helper) { scenario(helper, 3296, false, false, true); }

    private static void scenario(GameTestHelper helper,int x,boolean ledge) {
        scenario(helper,x,ledge,false);
    }
    private static void scenario(GameTestHelper helper,int x,boolean ledge,boolean wall) {
        scenario(helper,x,ledge,wall,false);
    }
    private static void scenario(GameTestHelper helper,int x,boolean ledge,boolean wall,boolean blocked) {
        var level=helper.getLevel(); int chunk=x>>4;
        boolean forced=level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk,0));
        var terrain=new HashMap<BlockPos,BlockState>();
        YukkuriEntity[] actors=new YukkuriEntity[2];
        boolean[] finished={false},contact={false},waited={false},climbed={false};
        var trace=new java.util.ArrayList<java.util.Map<String,Object>>();
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
                if(actors[0]!=null)P0Control.write("care-terrain-"+x,java.util.Map.of("parent",actors[0].saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString(),"child",actors[1].saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString(),"approach",actors[0].careApproach().debug().toString(),"trace",trace));
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
            if(helper.getTick()%20==0) {
                trace.add(java.util.Map.of("tick",helper.getTick(),"parent",actors[0].position().toString(),
                        "child",actors[1].position().toString(),"overlap",actors[0].getBoundingBox().intersects(actors[1].getBoundingBox()),
                        "approach",actors[0].careApproach().debug().toString(),"contact",actors[0].family().careContactProgress(),
                        "mouthsMeet",FamilyFeedingContact.mouthsMeet(actors[0],actors[1]),"meal",actors[1].life().mealTicks(),"nutrition",actors[1].life().nutrition(),
                        "supported",java.util.List.of(io.github.longshou.mcyukkuri.life.SurfaceApproach.supported(actors[0],actors[0].position()),
                                io.github.longshou.mcyukkuri.life.SurfaceApproach.supported(actors[1],actors[1].position()))));
                if(trace.size()>40)trace.remove(0);
            }
        });
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,chunk,chunk,0,0)).thenExecute(()->{
            for(var pos:BlockPos.betweenClosed(new BlockPos(x,64,0),new BlockPos(x+15,70,15))){
                terrain.put(pos.immutable(),level.getBlockState(pos));
                boolean floor=pos.getY()==64 || ledge&&pos.getY()==65&&pos.getX()>=x+8
                        ||wall&&pos.getX()==x+7&&pos.getZ()<=10&&pos.getY()<=67;
                level.setBlockAndUpdate(pos,floor?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            actors[0]=spawn(level,x+(wall||blocked?6.5:6.8),65,8.5,90);actors[1]=spawn(level,x+8.5,ledge?66:65,8.5,40);
            actors[1].family().generatedChild(actors[0],null);
            var family=actors[1].family().save();family.putLong("Age",18000);family.putLong("Nourishment",16000);family.putString("Stage","JUVENILE");family.putFloat("Scale",.42f);actors[1].family().load(family);actors[1].syncFamily();actors[1].refreshDimensions();
            FamilyData.get(level).register(actors[0]);FamilyData.get(level).register(actors[1]);
            var parentState=actors[0].family().save();parentState.put("CareFood",new ItemStack(ModItems.YUKKURI_STEM.get()).save(level.registryAccess()));actors[0].family().load(parentState);
            for(int i=0;i<40;i++)actors[0].family().tick();
            helper.assertTrue(actors[0].family().canUse(),"parent starts with one real carried serving");
            actors[0].family().tickGoal();
            if(blocked){
                // Close a real three-high cell after the live agreement was made. Existing
                // path feasibility must not hold the child forever when terrain changes.
                var center=actors[0].blockPosition();
                for(var pos:BlockPos.betweenClosed(center.offset(-1,0,-1),center.offset(1,2,1))){
                    if(pos.getX()==center.getX()&&pos.getZ()==center.getZ())continue;
                    terrain.putIfAbsent(pos.immutable(),level.getBlockState(pos));
                    level.setBlockAndUpdate(pos,Blocks.STONE.defaultBlockState());
                }
            }else if(!ledge&&!wall)actors[1].getNavigation().moveTo(x+13.5,65,8.5,1.0);
        }).thenWaitUntil(()->{
            if(blocked){
                helper.assertTrue(!actors[0].family().active()&&!actors[1].careApproach().waiting(),
                        "an unreachable live handoff releases its receiver and backs off");
                helper.assertTrue(actors[0].family().carrying().is(ModItems.YUKKURI_STEM.get())
                        &&actors[1].life().mealTicks()==0&&!contact[0],"blocked mouths never transfer or duplicate the carried serving");
            }else helper.assertTrue(actors[1].life().nutrition()>=55&&contact[0]&&waited[0]&&(!ledge||climbed[0]),
                "normal AI must route onto the ledge / around the wall / cooperate with a moving child, sustain real mouth contact, then swallow");
        }).thenExecute(()->{
            if(!blocked)return;
            var center=actors[0].blockPosition();
            for(var pos:BlockPos.betweenClosed(center.offset(-1,0,-1),center.offset(1,2,1)))
                if(pos.getX()!=center.getX()||pos.getZ()!=center.getZ())level.setBlockAndUpdate(pos,terrain.get(pos));
        }).thenWaitUntil(()->helper.assertTrue(!blocked||actors[1].life().nutrition()>=55&&contact[0],
                "after the route reopens the parent retries and delivers the same saved serving"))
          .thenExecute(cleanup).thenSucceed();
    }
    private static YukkuriEntity spawn(ServerLevel level,double x,double y,double z,float nutrition){
        var actor=ModEntities.REIMU.get().create(level);actor.moveTo(x,y,z,0,0);actor.setOnGround(true);actor.setPersistenceRequired();actor.setInvulnerable(true);
        var life=actor.life().save();life.putFloat("Nutrition",nutrition);life.putFloat("Energy",95);life.putFloat("Bladder",0);life.putFloat("Dryness",0);life.putInt("RestWait",2000);actor.life().load(life);
        var daily=actor.dailyLife().save();daily.putInt("Cooldown",2000);daily.putInt("Boredom",0);daily.putInt("Affection",0);actor.dailyLife().load(daily);
        level.addFreshEntity(actor);return actor;
    }
}
