package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.house.HouseholdEcology;
import io.github.longshou.mcyukkuri.house.HouseholdEcologyData;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPileView;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only transaction checks: automatic budgets count physical stock, never evictable activity metadata. */
@PrefixGameTestTemplate(false)
public final class HouseholdBudgetGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="house_ecology")
    public static void columnPortionBudgetSurvivesReloadAndActivityEviction(GameTestHelper h) {
        var f=new Fixture(h,120);
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            f.put(f.floor(0),batch(0,24));f.put(f.floor(1),batch(1,24));f.put(f.floor(2),batch(2,16));
            h.assertTrue(new SupplyPileView(f.level).column(f.pos).supplies()==64,"three separate height bands share one 64-portion automatic column budget");
            h.assertTrue(HouseholdEcology.residual(f.level,f.floor(3),batch(3,1))==0,
                    "an empty fourth room cannot evade the full column budget");
            f.reloadPiles();
            var previous=HouseholdEcologyData.get(f.level);
            try {
                // Only this synchronous callback uses isolated activity data; no parallel fixture or tick sees it.
                var saved=new CompoundTag();var entries=new ListTag();var old=new CompoundTag();
                old.putInt("X",f.chunk);old.putInt("Y",Math.floorDiv(f.pos.getY(),8));old.putInt("Z",0);
                old.putLong("LastActive",0);old.putLong("LastSample",0);entries.add(old);saved.put("Areas",entries);
                f.level.getDataStorage().set("mcyukkuri_household_ecology",HouseholdEcologyData.load(saved,f.level.registryAccess()));
                h.assertTrue(f.level.getGameTime()>0,"activity eviction fixture uses a strictly older saved region");
                for(int i=0;i<HouseholdEcologyData.MAX_REGIONS;i++)HouseholdEcology.cleaned(f.level,new BlockPos((2000+i)*16,65,0));
                var data=HouseholdEcologyData.get(f.level);
                h.assertTrue(data.size()==HouseholdEcologyData.MAX_REGIONS&&data.state(f.pos).lastSample()==-1,
                        "the original region was genuinely evicted from bounded activity history");
                var reloaded=HouseholdEcologyData.load(data.save(new CompoundTag(),f.level.registryAccess()),f.level.registryAccess());
                f.level.getDataStorage().set("mcyukkuri_household_ecology",reloaded);
                h.assertTrue(HouseholdEcology.residual(f.level,f.floor(3),batch(3,1))==0,
                        "evicted and reloaded metadata cannot reset the physical column's supply limit");
            } finally { f.level.getDataStorage().set("mcyukkuri_household_ecology",previous); }
            var removed=f.pile(f.floor(2)).takeOne();var input=batch(3,64);
            h.assertTrue(removed.getCount()==1&&HouseholdEcology.residual(f.level,f.floor(3),input)==1&&input.getCount()==64,
                    "withdrawing one real portion frees exactly one place and the automatic API leaves caller accounting intact");
            h.assertTrue(new SupplyPileView(f.level).column(f.pos).supplies()==64
                    &&HouseholdEcology.residual(f.level,f.floor(3),input)==0&&f.loose()==0,
                    "the limit holds across floor bands with no loose-item overflow");
        }).thenSucceed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="house_ecology")
    public static void columnCellBudgetPreservesManualStockAndFillsOnlyExistingCells(GameTestHelper h) {
        var f=new Fixture(h,124);
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            for(int floor=0;floor<2;floor++)for(int i=0;i<8;i++)f.put(f.floor(floor).offset(i%3-1,0,i/3-1),batch(floor*8+i,1));
            var stock=new SupplyPileView(f.level).column(f.pos);
            h.assertTrue(stock.piles()==16&&stock.supplies()==16&&HouseholdEcology.residual(f.level,f.floor(2),batch(20,1))==0,
                    "sixteen distinct real piles forbid an automatic seventeenth cell in another height band");
            h.assertTrue(HouseholdEcology.residual(f.level,f.floor(0),batch(0,64))==16,
                    "a matching existing cell can still fill its region's remaining portion budget without creating another block entity");
            f.put(f.floor(2),batch(20,64));f.reloadPiles();
            stock=new SupplyPileView(f.level).column(f.pos);
            h.assertTrue(stock.piles()==17&&stock.supplies()==96,
                    "deliberate player-style deposits and reload retain all stock above automatic budgets");
            h.assertTrue(HouseholdEcology.residual(f.level,f.floor(1),batch(8,1))==0
                    &&HouseholdEcology.residual(f.level,f.floor(3),batch(21,1))==0
                    &&new SupplyPileView(f.level).column(f.pos).supplies()==96&&f.loose()==0,
                    "manual excess pauses automatic output without trimming inventory or emitting overflow entities");
        }).thenSucceed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="house_ecology")
    public static void operationViewCountsOnceAndTracksRealWithdrawalsAndReplacement(GameTestHelper h) {
        var f=new Fixture(h,128);
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            f.put(f.floor(0),batch(0,7));f.put(f.floor(1),batch(1,5));var view=new SupplyPileView(f.level);
            for(int i=0;i<12;i++) {
                h.assertTrue(view.region(f.pos).supplies()==7&&view.column(f.floor(1)).supplies()==12
                        &&view.nearby(f.pos,2,1).size()==1,"repeated regional, vertical and pickup queries see the same real stock");
            }
            h.assertTrue(view.scannedChunks()==1,"one operation traverses the loaded chunk block-entity map only once");
            var old=f.pile(f.pos);h.assertTrue(old.takeOne().getCount()==1,"real one-portion withdrawal");view.changed(f.pos);
            h.assertTrue(view.region(f.pos).supplies()==6&&view.column(f.pos).supplies()==11,"local withdrawal updates both budgets without a rescan");
            h.assertTrue(old.takeAll().getCount()==6,"withdraw the final real contents");view.changed(f.pos);
            h.assertTrue(view.nearby(f.pos,2,1).isEmpty()&&view.column(f.pos).piles()==1,"empty-pile removal leaves no stale pickup target");
            f.put(f.pos,batch(2,2));view.changed(f.pos);
            h.assertTrue(view.nearby(f.pos,2,1).getFirst()!=old&&view.column(f.pos).supplies()==7&&view.scannedChunks()==1,
                    "a new pile at the same location replaces the old reference without another chunk scan");
            var unloaded=f.pos.offset(16000,0,0);
            h.assertTrue(f.level.getChunkSource().getChunkNow(unloaded.getX()>>4,unloaded.getZ()>>4)==null,"remote query fixture is actually unloaded");
            h.assertTrue(view.nearby(unloaded,2,1).isEmpty()&&view.scannedChunks()==1
                    &&f.level.getChunkSource().getChunkNow(unloaded.getX()>>4,unloaded.getZ()>>4)==null,
                    "nearby searches do not load remote chunks merely to look for microscopic supplies");
        }).thenSucceed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="house_ecology")
    public static void outdoorSourceDoesNotHideIndoorSourceInTheSameRegion(GameTestHelper h) {
        var f=new Fixture(h,132);
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            var outdoor=f.floor(3).west(3);var indoor=f.floor(3).east(3);var roof=indoor.above(3);
            f.blocks.put(roof,f.level.getBlockState(roof));f.level.setBlockAndUpdate(roof,Blocks.STONE.defaultBlockState());
            h.assertTrue(!HouseholdEcology.covered(f.level,outdoor)&&HouseholdEcology.covered(f.level,indoor),
                    "same-region sources genuinely stand under open sky and a roof, farther apart than one local search");
            var previous=HouseholdEcologyData.get(f.level);
            try {
                f.level.getDataStorage().set("mcyukkuri_household_ecology",new HouseholdEcologyData());
                HouseholdEcology.sample(f.level,java.util.List.of(outdoor,indoor));
                var state=HouseholdEcologyData.get(f.level).state(indoor);
                h.assertTrue(state.lastSample()==Math.floorDiv(f.level.getGameTime(),HouseholdEcology.SAMPLE_TICKS)
                        &&state.dustTicks()==HouseholdEcology.SAMPLE_TICKS,
                        "an unroofed source sampled first cannot consume another person's sheltered activity in the same region");
            } finally {f.level.getDataStorage().set("mcyukkuri_household_ecology",previous);}
        }).thenSucceed();
    }

    private static ItemStack batch(int id,int count) {
        var stack=new ItemStack(ModItems.FOOD_SCRAP.get(),count);CustomData.update(DataComponents.CUSTOM_DATA,stack,tag->tag.putInt("BudgetBatch",id));return stack;
    }
    private static final class Fixture {
        final GameTestHelper helper;final ServerLevel level;final BlockPos pos;final int chunk;final boolean forced;
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();boolean closed;
        Fixture(GameTestHelper h,int chunk) {
            helper=h;level=h.getLevel();this.chunk=chunk;pos=new BlockPos(chunk*16+8,65,8);
            forced=level.getForcedChunks().contains(ChunkPos.asLong(chunk,0));HouseholdEcologyGameTests.acquireEnvironment(level);
            h.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
                public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
                public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){close();}
                public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){close();}
                public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
            });
            GameTestChunks.setForced(level,chunk,0,true);
            for(int floor=0;floor<4;floor++)for(var cursor:BlockPos.betweenClosed(floor(floor).offset(-3,-1,-3),floor(floor).offset(3,2,3))) {
                var p=cursor.immutable();blocks.put(p,level.getBlockState(p));
                level.setBlockAndUpdate(p,p.getY()==floor(floor).getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            h.assertTrue(HouseholdEcology.enabled(level),"automatic generation gates are enabled for budget checks");
        }
        BlockPos floor(int index){return pos.above(index*16);}
        void ready(){GameTestChunks.assertTicking(helper,chunk,chunk,0,0);}
        void put(BlockPos p,ItemStack stack){helper.assertTrue(SupplyPiles.offer(level,p,stack,stack.getCount())==stack.getCount(),"physical stock setup at "+p);}
        SupplyPileBlockEntity pile(BlockPos p){if(level.getBlockEntity(p) instanceof SupplyPileBlockEntity pile)return pile;throw new IllegalStateException("Missing physical pile at "+p);}
        void reloadPiles(){for(var p:blocks.keySet())if(level.getBlockEntity(p) instanceof SupplyPileBlockEntity old){
            var saved=old.saveWithFullMetadata(level.registryAccess());level.removeBlockEntity(p);
            var restored=new SupplyPileBlockEntity(p,level.getBlockState(p));restored.loadWithComponents(saved,level.registryAccess());level.setBlockEntity(restored);
        }}
        AABB area(){return new AABB(pos.getX()-4,pos.getY()-2,pos.getZ()-4,pos.getX()+5,floor(3).getY()+4,pos.getZ()+5);}
        int loose(){return level.getEntitiesOfClass(ItemEntity.class,area()).size();}
        void close(){
            if(closed)return;closed=true;
            try {
                for(var p:blocks.keySet())if(level.getBlockEntity(p) instanceof SupplyPileBlockEntity pile)pile.drain();
                blocks.forEach(level::setBlockAndUpdate);
                level.getEntitiesOfClass(ItemEntity.class,area()).forEach(ItemEntity::discard);
                level.setChunkForced(chunk,0,forced);
            } finally {HouseholdEcologyGameTests.releaseEnvironment(level);}
        }
    }
    private HouseholdBudgetGameTests(){}
}
