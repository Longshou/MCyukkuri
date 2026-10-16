package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.Season;
import io.github.longshou.mcyukkuri.settlement.Seasons;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote server fixture: real return/transfer, ordinary metabolism, care, and bounded persisted yielding. */
@PrefixGameTestTemplate(false)
public final class WinterGameTests {
    private static final BlockPos ROOT = new BlockPos(3128, 65, 8);
    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=1600, batch="winter")
    public static void realHomeAndFiniteMeals(GameTestHelper helper) {
        var level=helper.getLevel();var server=level.getServer();
        var blocks=new HashMap<BlockPos,BlockState>();var beforeBiomes=new HashMap<BlockPos,String>();var actors=new ArrayList<YukkuriEntity>();
        var forced=new HashSet<>(level.getForcedChunks());
        long time=level.getDayTime();boolean daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean rain=level.isRaining(),thunder=level.isThundering();float rainLevel=level.getRainLevel(1),thunderLevel=level.getThunderLevel(1);
        boolean biomeSeasons=YukkuriConfig.BIOME_SEASONS.get();
        var winterGroupId=java.util.UUID.randomUUID();
        var config=List.of(YukkuriConfig.WINTER_TEMPERATURE,YukkuriConfig.COLD_THRESHOLD,YukkuriConfig.FREEZE_THRESHOLD,YukkuriConfig.HOT_THRESHOLD,YukkuriConfig.NEST_INSULATION);
        var values=config.stream().map(v->v.get()).toList();int hunger=YukkuriConfig.HUNGER_INTERVAL.get();
        ShelterBlockEntity[] home={null};YukkuriEntity[] parent={null};boolean[] cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            actors.forEach(YukkuriEntity::discard);if(home[0]!=null)home[0].rollback();
            level.getEntitiesOfClass(ItemEntity.class,new AABB(ROOT).inflate(12)).forEach(ItemEntity::discard);
            blocks.forEach(level::setBlockAndUpdate);
            beforeBiomes.forEach((cell,id)->fillBiome(level,cell,cell.offset(3,3,3),id));
            level.setDayTime(time);level.updateSkyBrightness();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,server);
            level.setWeatherParameters(12000,12000,rain,thunder);level.setRainLevel(rainLevel);level.setThunderLevel(thunderLevel);
            YukkuriConfig.BIOME_SEASONS.set(biomeSeasons);SettlementData.get(level).remove(winterGroupId);
            for(int i=0;i<config.size();i++)config.get(i).set(values.get(i));YukkuriConfig.HUNGER_INTERVAL.set(hunger);
            for(int x=194;x<=196;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,forced.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
        });
        for(int x=194;x<=196;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,true);
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,194,196,-1,1)).thenExecute(()->{
            helper.assertTrue(level==server.overworld(),"Winter fixture uses real overworld biomes");
            YukkuriConfig.BIOME_SEASONS.set(true);
            var registry=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            for(int x=3116;x<=3140;x+=4)for(int y=60;y<=72;y+=4)for(int z=-4;z<=20;z+=4) {
                var cell=new BlockPos(x,y,z);
                beforeBiomes.put(cell,registry.getKey(level.getChunkAt(cell).getNoiseBiome(x>>2,y>>2,z>>2).value()).toString());
            }
            fillBiome(level,new BlockPos(3116,60,-4),new BlockPos(3143,75,23),"minecraft:snowy_plains");
            for(var pos:BlockPos.betweenClosed(ROOT.offset(-10,-1,-10),ROOT.offset(10,4,10))) {
                blocks.put(pos.immutable(),level.getBlockState(pos));level.setBlockAndUpdate(pos,pos.getY()==64?Blocks.DIRT.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            NaturalShelterFixture.cave(level,ROOT,Direction.SOUTH);
            var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(ROOT,ShelterBlock.Kind.BURROW,Direction.SOUTH));
            helper.assertTrue(placed!=null,"Real enclosed terrain accepts a natural shelter marker");home[0]=placed.shelter();
            level.setDayTime(6000);level.updateSkyBrightness();level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
            level.setWeatherParameters(12000,0,false,false);level.setRainLevel(0);level.setThunderLevel(0);
            double[] defaults={-.25,.2,0,1.5,1};for(int i=0;i<config.size();i++)config.get(i).set(defaults[i]);YukkuriConfig.HUNGER_INTERVAL.set(240);
            helper.assertTrue(Seasons.at(level,ROOT)==Season.WINTER,"Snowy biome selects winter without calendar state");
            parent[0]=spawn(helper,ROOT.south(4),actors);vitals(parent[0],15);
            helper.assertTrue(parent[0].habitat().choose(home[0]),"Grounded adult reaches its real bed from the front entrance");
            var needs=parent[0].life().save();needs.putFloat("Dryness",80);needs.putFloat("Bladder",90);parent[0].life().load(needs);
            for(int i=0;i<60;i++)parent[0].life().temperature().tick();
            helper.assertTrue(parent[0].life().temperature().cold()&&!parent[0].life().temperature().comfortableAt(home[0].bed(parent[0].getUUID()))
                    &&parent[0].life().constipated(),"The actual unlined winter cave is cold; no heater/water exists, and urgent hunger coexists with constipation");
            var food=new ItemStack(Items.SHORT_GRASS,8);food.set(DataComponents.CUSTOM_NAME,Component.literal("Actual winter stores"));
            helper.assertTrue(home[0].returnFood(food).isEmpty(),"Eight supplied portions enter physical stock");
            helper.assertTrue(!home[0].beginStoredMeal(parent[0])&&home[0].food()==8,"A distant owner cannot withdraw remotely");
            helper.assertTrue(parent[0].life().plan()&&parent[0].life().activity().equals("shelter_stock"),"Unavailable warming, water and toilet plans do not forbid real stock food");
        }).thenWaitUntil(()->{
            helper.assertTrue(!parent[0].life().mouth().isEmpty(),"Waiting for normal server goals to walk home and take food");
            helper.assertTrue(parent[0].distanceToSqr(Vec3.atBottomCenterOf(home[0].bed(parent[0].getUUID())))<.64,"Food is obtained only after actually returning");
            helper.assertTrue(home[0].food()==7&&parent[0].life().mouth().getCount()==1,"Inventory loses exactly the one real mouth item");
            helper.assertTrue(parent[0].life().mouth().has(DataComponents.CUSTOM_NAME),"Item components survive the ownership transfer");
            var saved=parent[0].life().save();parent[0].life().load(saved);
            helper.assertTrue(parent[0].life().mouth().getCount()==1&&home[0].food()==7,"Reload preserves that mouth item without another withdrawal");
            YukkuriConfig.WINTER_TEMPERATURE.set(0.0);
            for(int i=0;i<4;i++)helper.assertTrue(home[0].deposit(new ItemStack(Items.PAPER)),"Supplied bedding restores a genuinely warm nest for the remaining checks");
            saved=parent[0].life().save();saved.putFloat("Dryness",0);saved.putFloat("Bladder",0);parent[0].life().load(saved);
        }).thenWaitUntil(()->helper.assertTrue(parent[0].life().mealTicks()==0,"Waiting for the ordinary chewing sequence to finish"))
        .thenExecute(()->{
            vitals(parent[0],100);
            var state=parent[0].life().save();state.putInt("HungerProgress",239);state.putInt("EnergyProgress",YukkuriConfig.ENERGY_INTERVAL.get()-1);parent[0].life().load(state);
            helper.assertTrue(parent[0].wintering().canUse(),"A fed, comfortable resident can choose a stocked warm nest");parent[0].wintering().start();
            parent[0].life().tick();
            helper.assertTrue(parent[0].life().nutrition()==99&&parent[0].life().energy()==99&&!parent[0].life().isSleeping(),"Winter staying retains normal hunger and energy costs, without free sleep");
            executableNeeds(helper,parent[0],home[0]);
            helper.assertTrue(parent[0].wintering().canUse(),"Warm fed resident resumes optional staying after priority regression checks");parent[0].wintering().start();
            exitsAndCare(helper,parent[0],home[0],actors);
            localYield(helper,parent[0],home[0],actors);
        }).thenWaitUntil(()->helper.assertTrue(parent[0].life().mealTicks()==0,"Prior real meal finishes before rainy-home checks"))
        .thenExecute(()->{
            actors.stream().filter(a->a!=parent[0]).forEach(YukkuriEntity::discard);
            // A winter household can have its real home just over the biome boundary.
            // Preserve the winter center while the cave and route receive actual plains rain.
            fillBiome(level,new BlockPos(3116,60,-4),new BlockPos(3143,75,23),"minecraft:plains");
            var winterCenter=ROOT.east(10);
            fillBiome(level,new BlockPos(3136,60,4),new BlockPos(3143,75,15),"minecraft:snowy_plains");
            var winterGroup=new SettlementData.Group(winterGroupId,SettlementData.Kind.FAMILY,winterCenter,null);
            SettlementData.get(level).add(winterGroup);parent[0].settlement().join(winterGroup);
            helper.assertTrue(Seasons.of(parent[0])==Season.WINTER&&Seasons.at(level,ROOT)==Season.SPRING,
                    "Winter household keeps its community season at a rainy spring-biome home");
            level.setWeatherParameters(0,12000,true,false);level.setRainLevel(1);level.setThunderLevel(0);
            YukkuriConfig.WINTER_TEMPERATURE.set(-.2);
            parent[0].life().stopGoal();parent[0].wintering().stop();parent[0].family().stop();
            place(helper,parent[0],ROOT.south(4));vitals(parent[0],100);
            helper.assertTrue(level.isRainingAt(parent[0].blockPosition())&&!parent[0].life().covered(parent[0].blockPosition()),"The grounded departure position receives actual local rain");
            helper.assertTrue(parent[0].life().temperature().comfortableAt(home[0].bed(parent[0].getUUID())),"Rain regression uses a physically covered comfortable home");
            helper.assertTrue(parent[0].life().plan()&&parent[0].life().activity().equals("return_home"),"An exposed awake resident starts the real rain-home route");
        }).thenWaitUntil(()->{
            var actor=parent[0];
            helper.assertTrue(actor.distanceToSqr(Vec3.atBottomCenterOf(home[0].bed(actor.getUUID())))<.16,"Normal server navigation actually reaches the own bed from rain");
            helper.assertTrue(!actor.life().activity().equals("return_home")&&!actor.life().isSleeping(),"Awake arrival releases HOME rather than resetting its pursuit forever");
            helper.assertTrue(level.isRainingAt(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,actor.blockPosition()))
                    &&actor.life().covered(actor.blockPosition())&&!level.isRainingAt(actor.blockPosition()),"Rain still falls above a real roof when HOME releases");
        }).thenExecute(()->rainHomeNeeds(helper,parent[0],home[0],actors)).thenExecute(cleanup).thenSucceed();
    }
    private static void rainHomeNeeds(GameTestHelper h,YukkuriEntity actor,ShelterBlockEntity home,List<YukkuriEntity> actors) {
        var level=h.getLevel();vitals(actor,100);
        h.assertTrue(!actor.life().plan()&&!actor.life().activity().equals("return_home"),"Covered awake resident does not reacquire HOME while external rain persists");
        var bed=home.bed(actor.getUUID());var toilet=bed.south();var oldToilet=level.getBlockState(toilet);
        h.assertTrue(!home.contains(toilet)&&oldToilet.isAir(),"The supplied toilet is beside the bed, outside every actual sleeping cell");
        level.setBlockAndUpdate(toilet,io.github.longshou.mcyukkuri.registry.ModBlocks.TOILET.get().defaultBlockState());
        vitals(actor,80);var needs=actor.life().save();needs.putFloat("Bladder",90);actor.life().load(needs);
        h.assertTrue(actor.life().plan()&&actor.life().activity().equals("seek_toilet"),"A newly full bowel gets the actual nearby toilet under continuing rain");
        for(int i=0;i<40;i++){actor.life().tickGoal();actor.life().tick();}
        h.assertTrue(actor.life().bladder()<70&&level.getBlockEntity(toilet) instanceof io.github.longshou.mcyukkuri.life.CareBlockEntity care&&care.waste()>0,"The toilet receives actual waste instead of HOME holding the life goal");
        level.setBlockAndUpdate(toilet,oldToilet);actor.life().stopGoal();

        vitals(actor,80);needs=actor.life().save();needs.putFloat("Dryness",80);actor.life().load(needs);
        h.assertTrue(!actor.life().plan(),"Unresolved thirst under cover does not restart HOME");
        var water=bed.south().below();var oldWater=level.getBlockState(water);level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
        for(int i=0;i<21;i++)actor.life().tick();
        h.assertTrue(actor.life().plan()&&actor.life().activity().equals("seek_water"),"New reachable water can acquire the free life slot while rain continues");
        for(int i=0;i<40;i++){actor.life().tickGoal();actor.life().tick();}
        h.assertTrue(actor.life().dryness()<80,"Ordinary drinking actually reduces dryness");
        level.setBlockAndUpdate(water,oldWater);actor.life().stopGoal();vitals(actor,60);
        int stock=home.food();
        h.assertTrue(actor.life().plan()&&actor.life().activity().equals("shelter_stock"),"Moderate winter hunger at a covered own bed selects stored food before starvation");
        actor.life().tickGoal();
        h.assertTrue(home.food()==stock-1&&actor.life().mouth().getCount()==1,"Rainy-home meal transfers exactly one real portion into the mouth");
        for(int i=0;i<64;i++)actor.life().tick();
        h.assertTrue(actor.life().mouth().isEmpty(),"The portion completes ordinary chewing before the care check");

        var child=spawn(h,ROOT.south(5),actors);child.family().generatedChild(actor,null);
        FamilyData.get(level).register(actor);FamilyData.get(level).register(child);
        actor.family().stop();for(int i=0;i<40;i++)actor.family().tick();vitals(actor,60);
        h.assertTrue(!actor.life().plan()&&actor.family().active(),"A covered awake parent leaves the life slot for actual child care during continuing rain");
        actor.family().stop();actor.frightenFrom(child);
        h.assertTrue(actor.isPanicking()&&!actor.life().plan(),"A real fright still takes precedence over the rain-home planner");
        h.assertTrue(level.isRainingAt(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,actor.blockPosition())),"All rainy-home demand checks ran without clearing the weather");
    }
    private static void fillBiome(net.minecraft.server.level.ServerLevel level,BlockPos min,BlockPos max,String id) {
        level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4),
                "fillbiome "+min.getX()+" "+min.getY()+" "+min.getZ()+" "+max.getX()+" "+max.getY()+" "+max.getZ()+" "+id);
    }
    private static void executableNeeds(GameTestHelper h,YukkuriEntity actor,ShelterBlockEntity home) {
        var level=h.getLevel();var savedHome=home.saveWithFullMetadata(level.registryAccess());int stock=home.food();
        var bare=savedHome.copy();bare.putInt("Lining",0);home.loadWithComponents(bare,level.registryAccess());YukkuriConfig.WINTER_TEMPERATURE.set(-.25);
        place(h,actor,ROOT.south(4));mealNeeds(actor,15,80,90);
        h.assertTrue(actor.life().plan()&&actor.life().activity().equals("shelter_stock"),"Unsolvable cold/thirst/constipation allows planning");
        // Run the actual planners/goal counters without a physics step: the body has not reached the bed.
        for(int i=0;i<220;i++) { actor.life().tickGoal();actor.life().tick();h.assertTrue(actor.life().plan()&&actor.life().activity().equals("shelter_stock"),"Unsolvable needs do not reset or cancel an in-flight route"); }
        for(int i=0;i<25;i++) { actor.life().tickGoal();actor.life().tick();actor.life().plan(); }
        h.assertTrue(!actor.life().activity().equals("shelter_stock")&&home.food()==stock&&actor.life().mouth().isEmpty(),"Repeated reevaluation retains the original 240-tick limit and cannot feed an unarrived body");

        mealNeeds(actor,40,80,0);h.assertTrue(actor.life().plan()&&actor.life().activity().equals("shelter_stock"),"Thirst without a reachable drink permits returning for food");
        for(int i=0;i<10;i++) { actor.life().tickGoal();actor.life().tick();actor.life().plan(); }
        var water=actor.blockPosition().east().below();var waterBefore=level.getBlockState(water);level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
        for(int i=0;i<21&&actor.life().activity().equals("shelter_stock");i++) { actor.life().tickGoal();actor.life().tick();actor.life().plan(); }
        h.assertTrue(actor.life().activity().equals("seek_water")||actor.life().activity().equals("drink"),"Newly reachable water preempts an in-flight food route within one reevaluation window");
        h.assertTrue(!actor.wintering().continueMeal()&&home.food()==stock,"Drink priority cancels only the route, without withdrawing food");
        level.setBlockAndUpdate(water,waterBefore);

        mealNeeds(actor,40,80,90);h.assertTrue(actor.life().plan()&&actor.life().activity().equals("shelter_stock"),"A constipated resident can initially return for food");
        // Apply the ordinary hydration event while a food route is active; it makes bowel relief possible.
        actor.life().drink(50);
        for(int i=0;i<21&&actor.life().activity().equals("shelter_stock");i++) { actor.life().tickGoal();actor.life().tick();actor.life().plan(); }
        h.assertTrue(actor.life().activity().equals("seek_toilet")||actor.life().activity().equals("ground_toilet"),"Once constipation is relieved, an executable toilet task preempts stock food");

        mealNeeds(actor,40,0,0);h.assertTrue(actor.life().plan()&&actor.life().activity().equals("shelter_stock"),"Cold without a refuge does not lock stock access");
        for(int i=0;i<10;i++) { actor.life().tickGoal();actor.life().tick();actor.life().plan(); }
        var torch=actor.blockPosition().east(4);var torchBefore=level.getBlockState(torch);level.setBlockAndUpdate(torch,Blocks.TORCH.defaultBlockState());
        for(int i=0;i<130&&actor.life().activity().equals("shelter_stock");i++) { actor.life().tickGoal();actor.life().tick();actor.life().plan(); }
        h.assertTrue(actor.life().temperature().active()&&(actor.life().activity().equals("seek_warmth")||actor.life().activity().equals("warm_up")),
                "A newly reachable real warm place takes priority after the existing thermal cooldown");
        h.assertTrue(home.food()==stock&&actor.life().mouth().isEmpty(),"All intercepted routes conserve stock");
        level.setBlockAndUpdate(torch,torchBefore);home.loadWithComponents(savedHome,level.registryAccess());YukkuriConfig.WINTER_TEMPERATURE.set(0.0);
        place(h,actor,home.bed(actor.getUUID()));vitals(actor,100);for(int i=0;i<20;i++)actor.life().temperature().tick();
    }
    private static void mealNeeds(YukkuriEntity actor,float nutrition,float dryness,float bladder) {
        actor.life().stopGoal();vitals(actor,nutrition);
        var tag=actor.life().save();tag.putFloat("Dryness",dryness);tag.putFloat("Bladder",bladder);actor.life().load(tag);
        for(int i=0;i<60;i++)actor.life().temperature().tick();
    }
    private static void exitsAndCare(GameTestHelper h,YukkuriEntity actor,ShelterBlockEntity home,List<YukkuriEntity> actors) {
        var level=h.getLevel();var saved=home.saveWithFullMetadata(level.registryAccess());
        var empty=saved.copy();empty.remove("Items");home.loadWithComponents(empty,level.registryAccess());
        h.assertTrue(!actor.wintering().canContinueToUse(),"Depleted stores immediately end optional staying");
        home.loadWithComponents(saved,level.registryAccess());
        var wet=saved.copy();wet.putInt("Moisture",90);home.loadWithComponents(wet,level.registryAccess());
        h.assertTrue(!actor.wintering().canContinueToUse(),"A wet unusable home immediately ends staying");
        home.loadWithComponents(saved,level.registryAccess());
        var bare=saved.copy();bare.putInt("Lining",0);home.loadWithComponents(bare,level.registryAccess());
        h.assertTrue(!actor.wintering().canContinueToUse(),"Stored food cannot substitute for real insulation");home.loadWithComponents(saved,level.registryAccess());
        actor.wintering().stop();vitals(actor,40);
        h.assertTrue(actor.wintering().planMeal(),"A real hungry resident can plan a stock meal");home.loadWithComponents(empty,level.registryAccess());
        h.assertTrue(!actor.wintering().continueMeal(),"A meal route stops as soon as its actual stock runs out");home.loadWithComponents(saved,level.registryAccess());
        vitals(actor,40);h.assertTrue(actor.wintering().planMeal(),"Restored real stock permits another attempt");home.loadWithComponents(wet,level.registryAccess());
        h.assertTrue(!actor.wintering().continueMeal(),"A spoiled home interrupts a meal route before any withdrawal");home.loadWithComponents(saved,level.registryAccess());
        actor.wintering().stop();vitals(actor,100);
        var urgent=actor.life().save();urgent.putFloat("Bladder",90);actor.life().load(urgent);
        h.assertTrue(!actor.wintering().canUse(),"A full bowel takes priority over staying");vitals(actor,100);
        h.assertTrue(actor.wintering().canUse(),"Restored needs permit a new independent stay");actor.wintering().start();
        var child=spawn(h,ROOT.south(5),actors);child.family().generatedChild(actor,null);FamilyData.get(level).register(actor);FamilyData.get(level).register(child);
        actor.family().stop();for(int i=0;i<40;i++)actor.family().tick();
        h.assertTrue(actor.family().canUse(),"An actual visible distant child activates existing family care");
        h.assertTrue(!actor.wintering().canContinueToUse(),"Winter staying yields to active child care");
        actor.wintering().stop();actor.family().stop();for(int i=0;i<40;i++)actor.family().tick();vitals(actor,60);
        h.assertTrue(!actor.life().plan()&&actor.family().active(),"A non-urgent stock meal also yields the higher-priority life slot to pending child care");
        actor.family().stop();child.discard();
    }
    private static void localYield(GameTestHelper h,YukkuriEntity actor,ShelterBlockEntity home,List<YukkuriEntity> actors) {
        var child=spawn(h,home.entrance().south(),actors);child.family().generatedChild(actor,null);
        FamilyData.get(h.getLevel()).register(actor);FamilyData.get(h.getLevel()).register(child);
        h.assertTrue(child.habitat().choose(home),"A real related child claims a different actual bed");
        place(h,child,home.bed(child.getUUID()));place(h,actor,home.bed(actor.getUUID()));
        vitals(actor,60);vitals(child,40);int stock=home.food();
        h.assertTrue(actor.wintering().planMeal()&&actor.wintering().tickMeal(),"Present hungry juvenile receives one brief first chance");
        h.assertTrue(home.food()==stock&&actor.life().mouth().isEmpty(),"Yielding does not create, reserve or remove a portion");
        for(int tick=0;tick<39;tick++) {
            var saved=actor.life().save();actor.life().load(saved);
            h.assertTrue(actor.wintering().planMeal()&&actor.wintering().tickMeal(),"Repeated pause/reload continues only the remaining yield budget");
        }
        actor.life().load(actor.life().save());h.assertTrue(actor.wintering().planMeal(),"The last resumed attempt is still eligible");
        h.assertTrue(!actor.wintering().tickMeal()&&home.food()==stock-1&&actor.life().mouth().getCount()==1,"After forty total waiting ticks the healthy adult still takes its own real meal");
        // Finish the preceding real portion normally, away from optional mouth-to-mouth sharing.
        child.setNoAi(true);place(h,child,ROOT.south(6));for(int i=0;i<64;i++)actor.life().tick();
        h.assertTrue(actor.life().mouth().isEmpty()&&actor.life().mealTicks()==0,"The prior portion was chewed through the ordinary loaded-time lifecycle");
        vitals(actor,60);place(h,child,home.bed(child.getUUID()));stock=home.food();
        h.assertTrue(actor.wintering().planMeal()&&!actor.wintering().tickMeal()&&home.food()==stock-1,
                "An in-place but no-AI child cannot occupy the local food queue");
        var budget=actor.wintering().save();budget.putBoolean("Yielded",true);budget.putInt("YieldLeft",Integer.MAX_VALUE);actor.wintering().load(budget);
        h.assertTrue(actor.wintering().save().getInt("YieldLeft")==40,"Malformed waiting counters are bounded");
        budget.putInt("YieldLeft",-1);actor.wintering().load(budget);h.assertTrue(actor.wintering().save().getInt("YieldLeft")==0,"Negative counters cannot create time credit");
    }
    private static YukkuriEntity spawn(GameTestHelper h,BlockPos pos,List<YukkuriEntity> actors) {
        var entity=ModEntities.REIMU.get().create(h.getLevel());actors.add(entity);h.getLevel().addFreshEntity(entity);
        var growth=entity.family().save();growth.putBoolean("Initialized",true);growth.putBoolean("Sterile",true);growth.putString("Stage","ADULT");entity.family().load(growth);
        place(h,entity,pos);vitals(entity,100);return entity;
    }
    private static void place(GameTestHelper h,YukkuriEntity actor,BlockPos pos) {
        actor.moveTo(Vec3.atBottomCenterOf(pos));
        h.assertTrue(h.getLevel().isPositionEntityTicking(pos)&&h.getLevel().noCollision(actor),"Fixture body fits a real ticking ground position");
        actor.move(MoverType.SELF,new Vec3(0,-.125,0));
        h.assertTrue(actor.onGround()&&actor.verticalCollision,"Real collision establishes grounding before pathfinding");
    }
    private static void vitals(YukkuriEntity entity,float nutrition) {
        var tag=entity.life().save();tag.putFloat("Nutrition",nutrition);tag.putFloat("Energy",100);tag.putFloat("Bladder",0);tag.putFloat("Wetness",0);tag.putFloat("Dryness",0);
        tag.putInt("RestWait",1200);tag.putInt("HungerProgress",0);tag.putInt("EnergyProgress",0);tag.put("Wintering",new CompoundTag());entity.life().load(tag);
    }
}
