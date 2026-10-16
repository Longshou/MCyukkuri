package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.season.AutumnStock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.HashMap;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only: real slots/components, local-biome autumn stores and a full-store race. */
@PrefixGameTestTemplate(false)
public final class AutumnStockGameTests {
    private static final BlockPos ROOT = new BlockPos(2888, 65, 8);
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=1100, batch="autumn_stock")
    public static void capacityAndCarrying(GameTestHelper helper) {
        var level = helper.getLevel(); var server = level.getServer();
        long time = level.getDayTime();
        boolean daylight = level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean grief = level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        boolean rain = level.isRaining(), thunder = level.isThundering();
        float rainLevel = level.getRainLevel(1), thunderLevel = level.getThunderLevel(1);
        int days = YukkuriConfig.SEASON_DAYS.get(), buffer = YukkuriConfig.AUTUMN_STOCK_BUFFER.get(), hunger = YukkuriConfig.HUNGER_INTERVAL.get(); boolean biomeSeasons = YukkuriConfig.BIOME_SEASONS.get();
        var forced = new HashSet<>(level.getForcedChunks()); var blocks = new HashMap<BlockPos,BlockState>(); var biomes = new HashMap<BlockPos,String>();
        YukkuriEntity[] actor = {null}; ShelterBlockEntity[] home = {null}; ItemEntity[] source = {null};
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if(cleaned[0])return;cleaned[0]=true;
            if(actor[0]!=null)actor[0].discard();
            if(home[0]!=null)home[0].rollback();
            level.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(12)).forEach(ItemEntity::discard);
            blocks.forEach(level::setBlockAndUpdate);
            level.setDayTime(time);level.updateSkyBrightness();
            level.setWeatherParameters(12000,12000,rain,thunder);level.setRainLevel(rainLevel);level.setThunderLevel(thunderLevel);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,server);
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief,server);
            biomes.forEach((cell,id)->fillBiome(level,cell,cell.offset(3,3,3),id));
            YukkuriConfig.SEASON_DAYS.set(days);YukkuriConfig.AUTUMN_STOCK_BUFFER.set(buffer);YukkuriConfig.HUNGER_INTERVAL.set(hunger);YukkuriConfig.BIOME_SEASONS.set(biomeSeasons);
            for(int x=179;x<=181;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,forced.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        for(int x=179;x<=181;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,true);
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,179,181,-1,1)).thenExecute(()->{
            rememberBiomes(level,biomes,ROOT.offset(-12,-5,-12),ROOT.offset(12,8,12));
            fillBiome(level,ROOT.offset(-12,-5,-12),ROOT.offset(12,8,12),"minecraft:plains");
            for(var pos:BlockPos.betweenClosed(ROOT.offset(-9,-1,-9),ROOT.offset(9,4,9))) {
                blocks.put(pos.immutable(),level.getBlockState(pos));
                level.setBlockAndUpdate(pos,pos.getY()==64?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            level.setDayTime(6000);level.updateSkyBrightness();level.setWeatherParameters(12000,0,false,false);level.setRainLevel(0);level.setThunderLevel(0);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,server);
            YukkuriConfig.SEASON_DAYS.set(7);YukkuriConfig.AUTUMN_STOCK_BUFFER.set(25);YukkuriConfig.HUNGER_INTERVAL.set(240);YukkuriConfig.BIOME_SEASONS.set(true);
            var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(ROOT,ShelterBlock.Kind.CARDBOARD,Direction.SOUTH));
            helper.assertTrue(placed!=null,"Physical cardboard shelter placement succeeds");home[0]=placed.shelter();
            actor[0]=ModEntities.REIMU.get().create(level);
            actor[0].moveTo(Vec3.atBottomCenterOf(home[0].entrance().south()));actor[0].setOnGround(true);level.addFreshEntity(actor[0]);
            var family=actor[0].family().save();family.putBoolean("Initialized",true);family.putBoolean("Sterile",true);family.putString("Stage","ADULT");actor[0].family().load(family);
            var life=actor[0].life().save();life.putFloat("Nutrition",100);life.putFloat("Energy",100);actor[0].life().load(life);
            helper.assertTrue(actor[0].habitat().choose(home[0]),"Real adult can reach and claim the bed through the nearby front entrance");
            for(int i=0;i<4;i++)helper.assertTrue(home[0].deposit(new ItemStack(Items.PAPER)),"Bedding uses real supplied material");
            inventoryChecks(helper,home[0],actor[0]);
            setStock(level,home[0],new ItemStack(Items.APPLE,3));
            level.setDayTime(level.getDayTime()+96_000);level.updateSkyBrightness();
            helper.assertTrue(AutumnStock.target(level,home[0])==3,"Outside autumn, household gathering keeps its routine target");
            source[0]=new ItemEntity(level,ROOT.getX()+.5,65,ROOT.getZ()+6.5,new ItemStack(Items.APPLE,3));
            source[0].setNoPickUpDelay();level.addFreshEntity(source[0]);
            var habitat=actor[0].habitat().save();actor[0].habitat().load(habitat);
            helper.assertTrue(!actor[0].habitat().canUse(),"A stocked spring household does not collect the nearby surplus");
            fillBiome(level,ROOT.offset(-12,-5,-12),ROOT.offset(12,8,12),"minecraft:forest");actor[0].habitat().load(habitat);
            helper.assertTrue(AutumnStock.target(level,home[0])==72,"Default one-resident autumn estimate includes winter meals and dry spoilage");
            var baby=actor[0].family().save();baby.putString("Stage","BABY");actor[0].family().load(baby);
            helper.assertTrue(!AutumnStock.fitCollector(actor[0])&&!actor[0].habitat().canUse(),"A true baby stage is not assigned extra autumn gathering");
            actor[0].family().load(family);actor[0].habitat().load(habitat);
            var weak=actor[0].life().save();weak.putFloat("Energy",40);actor[0].life().load(weak);
            helper.assertTrue(!actor[0].habitat().canUse(),"An exhausted adult does not gather autumn reserves");
            actor[0].life().load(life);actor[0].habitat().load(habitat);
        }).thenWaitUntil(()->{
            helper.assertTrue(!actor[0].habitat().carrying().isEmpty(),"Waiting for actual server navigation and a real source pickup");
            helper.assertTrue(source[0].getItem().getCount()==2&&actor[0].habitat().carrying().getCount()==1,"Pickup transfers exactly one actual item");
        }).thenExecute(()->{
            // Another real deposit can fill every slot after the worker has left the source.
            for(int i=0;i<61;i++)helper.assertTrue(home[0].deposit(new ItemStack(Items.APPLE)),"Actual deposits fill the existing three-apple stack to 64");
            var extra=fragmented(false);
            for(int i=0;i<8;i++)helper.assertTrue(home[0].deposit(extra[i]),"Actual distinct component stacks occupy the other eight slots");
            helper.assertTrue(home[0].food()==72&&home[0].foodSpace(new ItemStack(Items.APPLE))==0,"Every original stored item remains while the slots become full");
            var carrying=actor[0].habitat().save();actor[0].habitat().load(carrying);
            helper.assertTrue(actor[0].habitat().carrying().getCount()==1,"Reload preserves the in-flight resource rather than creating a second one");
        }).thenWaitUntil(()->{
            helper.assertTrue(actor[0].habitat().carrying().isEmpty(),"Waiting for the worker to reach its actual bed and put down the refused item");
            helper.assertTrue(actor[0].distanceToSqr(Vec3.atBottomCenterOf(home[0].bed(actor[0].getUUID())))<2,"Rejected cargo was returned physically, never remotely deposited");
            int ordinary=level.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(12)).stream()
                    .filter(item->ItemStack.isSameItemSameComponents(item.getItem(),new ItemStack(Items.APPLE)))
                    .mapToInt(item->item.getItem().getCount()).sum();
            helper.assertTrue(ordinary==3&&home[0].food()==72,"All three source apples and the 72 actually stored portions survive the full-store race");
            // A separate low-count fixture distinguishes capacity rejection from reaching the quota.
            setStock(level,home[0],fragmented(false));
            var grass=ROOT.south(5).east(2);level.setBlockAndUpdate(grass,Blocks.SHORT_GRASS.defaultBlockState());
            actor[0].habitat().load(actor[0].habitat().save());
            helper.assertTrue(!actor[0].habitat().canUse()&&level.getBlockState(grass).is(Blocks.SHORT_GRASS),"Full incompatible slots prevent both repeated pickup and destructive grass harvesting");
        }).thenExecute(cleanup).thenSucceed();
    }
    private static void inventoryChecks(GameTestHelper helper,ShelterBlockEntity home,YukkuriEntity resident) {
        var level=helper.getLevel();
        setStock(level,home,new ItemStack(Items.APPLE,16));
        var old=home.saveWithFullMetadata(level.registryAccess());old.putInt("Version",2);home.loadWithComponents(old,level.registryAccess());
        helper.assertTrue(home.food()==16,"Old 16-item slot is preserved exactly, not expanded by the new capacity");
        helper.assertTrue(home.deposit(new ItemStack(Items.APPLE))&&home.food()==17,"Compatible real items can grow an old slot past the former cap");
        setStock(level,home);
        var rest=home.returnFood(new ItemStack(Items.APPLE,100));
        helper.assertTrue(rest.isEmpty()&&home.food()==100,"Returning a large real stack distributes it across physical slots");
        var stored=NonNullList.withSize(9,ItemStack.EMPTY);ContainerHelper.loadAllItems(home.saveWithFullMetadata(level.registryAccess()),stored,level.registryAccess());
        helper.assertTrue(stored.get(0).getCount()==64&&stored.get(1).getCount()==36,"Normal apple stacks respect their real 64-item maximum");
        setStock(level,home,fragmented(true));
        helper.assertTrue(home.foodCapacity()==9&&!home.accepts(fragmented(true)[0]),"Actual component max-stack-size of one is respected");
        setStock(level,home,fragmented(false));
        var ordinary=new ItemStack(Items.APPLE);
        helper.assertTrue(home.food()<32&&!home.accepts(ordinary)&&!home.deposit(ordinary),"The old low-count/full-slot acceptance loop is rejected");
        helper.assertTrue(home.returnFood(ordinary).getCount()==1&&home.food()==9,"A failed return hands the exact remainder to its caller");
        var taken=home.takeFood(resident);helper.assertTrue(!taken.isEmpty()&&home.food()==8,"Only the real resident can withdraw a real portion");
        helper.assertTrue(home.returnFood(taken).isEmpty()&&home.food()==9,"Take/return conserves count and components");
        var roundTrip=home.saveWithFullMetadata(level.registryAccess());home.loadWithComponents(roundTrip,level.registryAccess());
        helper.assertTrue(home.food()==9&&!home.accepts(ordinary),"Storage components survive save and reload");
    }
    private static ItemStack[] fragmented(boolean single) {
        var items=new ItemStack[9];
        for(int i=0;i<9;i++) {
            items[i]=new ItemStack(Items.APPLE);items[i].set(DataComponents.CUSTOM_NAME,Component.literal("Stored portion "+i));
            if(single)items[i].set(DataComponents.MAX_STACK_SIZE,1);
        }
        return items;
    }
    private static void setStock(ServerLevel level,ShelterBlockEntity home,ItemStack... items) {
        var stock=NonNullList.withSize(9,ItemStack.EMPTY);
        for(int i=0;i<items.length;i++)stock.set(i,items[i].copy());
        var tag=home.saveWithFullMetadata(level.registryAccess());tag.remove("Items");ContainerHelper.saveAllItems(tag,stock,level.registryAccess());
        home.loadWithComponents(tag,level.registryAccess());
    }
    private static void rememberBiomes(ServerLevel level,HashMap<BlockPos,String> before,BlockPos min,BlockPos max) {
        var registry=level.registryAccess().registryOrThrow(Registries.BIOME);
        int minX=Math.floorDiv(min.getX(),4)*4,minY=Math.floorDiv(min.getY(),4)*4,minZ=Math.floorDiv(min.getZ(),4)*4;
        for(int x=minX;x<=max.getX();x+=4)for(int y=minY;y<=max.getY();y+=4)for(int z=minZ;z<=max.getZ();z+=4) {
            var cell=new BlockPos(x,y,z);
            before.put(cell,registry.getKey(level.getChunkAt(cell).getNoiseBiome(x>>2,y>>2,z>>2).value()).toString());
        }
    }
    private static void fillBiome(ServerLevel level,BlockPos min,BlockPos max,String id) {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4),
                "fillbiome "+min.getX()+" "+min.getY()+" "+min.getZ()+" "+max.getX()+" "+max.getY()+" "+max.getZ()+" "+id);
    }
}
