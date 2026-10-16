package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyConfig;
import io.github.longshou.mcyukkuri.house.HouseholdEcology;
import io.github.longshou.mcyukkuri.house.HouseholdEcologyData;
import io.github.longshou.mcyukkuri.house.HouseholdProtection;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real server ticks drive natural household activity; no recipes, generated nests or founder state are injected. */
@PrefixGameTestTemplate(false)
public final class HouseholdEcologyGameTests {
    // The real-AI routine shares these rules. Only the last parallel fixture restores them.
    private static int environmentUsers;
    private static boolean originalSpawning,originalGriefing,originalArrivals;
    static void acquireEnvironment(ServerLevel level) {
        if(environmentUsers++==0){originalSpawning=level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);originalGriefing=level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);originalArrivals=FamilyConfig.HOUSE_ARRIVALS.get();}
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());FamilyConfig.HOUSE_ARRIVALS.set(true);
    }
    static void releaseEnvironment(ServerLevel level) {
        if(--environmentUsers==0){level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(originalSpawning,level.getServer());level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(originalGriefing,level.getServer());FamilyConfig.HOUSE_ARRIVALS.set(originalArrivals);}
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=2600,batch="house_ecology")
    public static void occupiedRoomAccumulatesRealSuppliesAndAttractsLimitedResidents(GameTestHelper h) {
        var f=new Fixture(h,100);int[] samples={0};boolean[] sample={false};long[] bucket={-1};
        int arrivalTime=FamilyConfig.HOUSE_ARRIVAL_TIME.get();FamilyConfig.HOUSE_ARRIVAL_TIME.set(200);
        f.extraCleanup=()->FamilyConfig.HOUSE_ARRIVAL_TIME.set(arrivalTime);
        h.onEachTick(()->{
            if(!sample[0]||f.closed)return;
            long now=f.level.getGameTime()/HouseholdEcology.SAMPLE_TICKS;
            if(now==bucket[0])return;bucket[0]=now;
            HouseholdEcology.sample(f.level,List.of(f.pos,f.pos,f.pos.east()));samples[0]++;
            var once=HouseholdEcology.snapshot(f.level,f.pos);
            HouseholdEcology.sample(f.level,List.of(f.pos,f.pos));
            h.assertTrue(once.equals(HouseholdEcology.snapshot(f.level,f.pos)),"same-tick people and repeated sampling cannot accelerate one region");
        });
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            h.assertTrue(f.piles().isEmpty()&&f.nests().isEmpty(),"the sheltered room starts without player-supplied items or any nest");sample[0]=true;
        }).thenWaitUntil(()->h.assertTrue(samples[0]>=3,"waiting for three actual activity buckets")).thenExecute(()->{
            h.assertTrue(f.count(ModItems.NEST_LINT.get())==1&&f.nests().isEmpty(),"600 sampled active ticks create one real fiber before any nest exists");
            h.assertTrue(HouseholdEcology.meal(f.level,f.pos)&&f.count(ModItems.FOOD_SCRAP.get())==1,"a real meal completion contributes one ground crumb");
        }).thenWaitUntil(()->h.assertTrue(samples[0]>=6,"waiting for the natural settlement interval")).thenExecute(()->{
            var nests=f.nests();h.assertTrue(nests.size()==1&&f.level.getBlockState(nests.getFirst().getBlockPos()).is(ModBlocks.HOUSE_DUST_NEST.get()),
                    "sustained ordinary activity creates the natural dust nest itself");
            var nest=nests.getFirst();
            h.assertTrue(nest.materials()+nest.food()==1&&f.count(ModItems.NEST_LINT.get())+f.count(ModItems.FOOD_SCRAP.get())+nest.materials()+nest.food()==3,
                    "natural nest placement transfers one of the two accumulated fibers and meal crumb without creating a free resource");
            h.assertTrue(f.loose()==0,"natural accumulation and settlement never produce tiny dropped entities");
        }).thenWaitUntil(()->h.assertTrue(!f.residents().isEmpty(),"a supplied natural nest near recent human activity attracts an actual resident: "+f.state()))
                .thenIdle(450).thenExecute(()->{
                    h.assertTrue(f.residents().size()<=2&&f.nests().size()==1,"one occupied household remains limited to its finite initial settlers and one nearby nest");
                    h.assertTrue(f.loose()==0,"natural residence keeps resources in real stores");
                }).thenSucceed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1800,batch="house_ecology")
    public static void villagerMealsDebitInventoryAndGroundCapsStopFurtherWaste(GameTestHelper h) {
        var f=new Fixture(h,104);Villager[] villager={null};boolean[] sample={false};long[] bucket={-1};int[] samples={0};
        h.onEachTick(()->{
            if(!sample[0]||f.closed)return;
            long now=f.level.getGameTime()/HouseholdEcology.SAMPLE_TICKS;if(now==bucket[0])return;bucket[0]=now;
            HouseholdEcology.sample(f.level,List.of(f.pos));HouseholdEcology.villagers(f.level,List.of(villager[0],villager[0]));samples[0]++;
        });
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            villager[0]=EntityType.VILLAGER.create(f.level);h.assertTrue(villager[0]!=null,"real villager allocation");
            villager[0].setNoAi(true);villager[0].moveTo(f.pos.getX()+.5,f.pos.getY(),f.pos.getZ()+.5,0,0);
            villager[0].getInventory().setItem(0,new ItemStack(Items.BREAD,16));f.level.addFreshEntity(villager[0]);sample[0]=true;
        }).thenWaitUntil(()->h.assertTrue(samples[0]>=6,"waiting for six loaded household samples")).thenExecute(()->{
            h.assertTrue(villager[0].getInventory().getItem(0).getCount()==15&&f.count(ModItems.FOOD_SCRAP.get())==1,
                    "one region's meal consumes one real surplus bread and emits exactly one real crumb despite duplicate villagers");
            int before=villager[0].getInventory().getItem(0).getCount();HouseholdEcology.villagers(f.level,List.of(villager[0]));
            h.assertTrue(villager[0].getInventory().getItem(0).getCount()==before&&f.loose()==0,"same-bucket meals cannot duplicate food or create ItemEntities");
            sample[0]=false;villager[0].discard();f.clearResources();
            for(int i=0;i<8;i++) {
                var portion=batch(i,1);
                h.assertTrue(HouseholdEcology.residual(f.level,f.pos,portion)==1,"each distinct component batch occupies one legal pile cell");
            }
            var stock=HouseholdEcology.snapshot(f.level,f.pos).stock();
            h.assertTrue(stock.piles()==8&&stock.supplies()==8&&HouseholdEcology.residual(f.level,f.pos,batch(9,1))==0,
                    "eight ground cells prevent an incompatible ninth pile even below the portion limit");
            h.assertTrue(HouseholdEcology.residual(f.level,f.pos,batch(0,64))==16,"a compatible existing pile may fill only the remaining regional portion budget");
            stock=HouseholdEcology.snapshot(f.level,f.pos).stock();
            h.assertTrue(stock.piles()==8&&stock.supplies()==24&&!HouseholdEcology.meal(f.level,f.pos)&&f.loose()==0,
                    "24 real portions stop further residue with no loose-item fallback");
        }).thenSucceed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=5000,batch="house_ecology")
    public static void guardAndCleaningSuppressNaturalGrowthWithoutOfflineCatchup(GameTestHelper h) {
        var f=new Fixture(h,108);var guard=f.pos.east(2).below();boolean[] sample={false};long[] start={0},cleanUntil={0};
        f.extraCleanup=()->HouseholdProtection.get(f.level).remove(guard);
        h.onEachTick(()->{if(sample[0]&&!f.closed)HouseholdEcology.sample(f.level,List.of(f.pos));});
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            h.assertTrue(HouseholdEcology.meal(f.level,f.pos),"an unprotected room accepts a genuine meal crumb");
            h.assertTrue(HouseholdProtection.get(f.level).spray(guard,f.level.getGameTime()),"the room receives one timed spray treatment");
            h.assertTrue(HouseholdProtection.protectedAt(f.level,guard.offset(8,4,8))
                    &&!HouseholdProtection.protectedAt(f.level,guard.east(9))&&!HouseholdProtection.protectedAt(f.level,guard.above(5)),
                    "spray includes horizontal 8 / vertical 4 boundaries and excludes 9 / 5");
            var saved=HouseholdProtection.get(f.level).save(new CompoundTag(),f.level.registryAccess());
            f.level.getDataStorage().set("mcyukkuri_household_protection",HouseholdProtection.load(saved,f.level.registryAccess()));
            h.assertTrue(HouseholdProtection.protectedAt(f.level,guard.offset(-8,-4,-8)),"saved spray rebuilds its chunk index and retains the opposite inclusive boundary");
            h.assertTrue(!HouseholdEcology.meal(f.level,f.pos),"spraying immediately prevents new meal residue");
            HouseholdEcology.sample(f.level,List.of(f.pos));
            h.assertTrue(HouseholdEcology.snapshot(f.level,f.pos).activity().lastSample()<0,"protected activity cannot accumulate settlement credit");
            sample[0]=true;start[0]=f.level.getGameTime();
        }).thenWaitUntil(()->h.assertTrue(f.level.getGameTime()-start[0]>=1200,"observing a full protected settlement interval")).thenExecute(()->{
            h.assertTrue(f.nests().isEmpty()&&f.count(ModItems.NEST_LINT.get())==0&&f.count(ModItems.FOOD_SCRAP.get())==1,
                    "existing supplies under active spray do not produce dust or a new natural nest");
            f.level.removeBlock(guard,false);
            h.assertTrue(HouseholdProtection.protectedAt(f.level,f.pos),"breaking the treated block does not cancel a timed area");
            f.level.setBlockAndUpdate(guard,Blocks.STONE.defaultBlockState());
            // End only this fixture's treatment; real expiry and item interaction have separate focused tests.
            HouseholdProtection.get(f.level).remove(guard);
            h.assertTrue(!HouseholdProtection.protectedAt(f.level,f.pos)&&HouseholdEcology.meal(f.level,f.pos),"without a treatment the room accepts natural residue again");
            HouseholdEcology.cleaned(f.level,f.pos);cleanUntil[0]=HouseholdEcology.snapshot(f.level,f.pos).activity().cleanUntil();
        }).thenWaitUntil(()->{
            var state=HouseholdEcology.snapshot(f.level,f.pos);
            if(f.level.getGameTime()<cleanUntil[0])h.assertTrue(state.suppressed()&&state.activity().dustTicks()==0&&f.nests().isEmpty(),
                    "cleaning keeps dust and settlement suppressed throughout its 2400 real ticks");
            h.assertTrue(f.level.getGameTime()>=cleanUntil[0]&&state.activity().dustTicks()>0,"waiting for the first unsuppressed activity bucket");
        }).thenExecute(()->{
            var state=HouseholdEcology.snapshot(f.level,f.pos);
            h.assertTrue(!state.suppressed()&&state.activity().dustTicks()==200&&state.activity().settlementTicks()==200&&f.nests().isEmpty(),
                    "resumption earns one fresh sample instead of catching up suppressed time");
            h.assertTrue(f.loose()==0,"prevention and cleaning never spill tiny entities");
            sample[0]=false;
        }).thenIdle(600).thenExecute(()->{
            HouseholdEcology.sample(f.level,List.of(f.pos));var state=HouseholdEcology.snapshot(f.level,f.pos);
            h.assertTrue(state.activity().dustTicks()==400&&state.activity().settlementTicks()==400&&f.count(ModItems.NEST_LINT.get())==0,
                    "an interval without activity samples earns no offline catch-up credit when people return");
        }).thenSucceed();
    }

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1200,batch="house_ecology")
    public static void hungryHouseResidentEatsDaytimePileWhileRetainingBlockedMaterial(GameTestHelper h) {
        var f=new Fixture(h,112);YukkuriEntity[] resident={null};HouseNestBlockEntity[] nest={null};
        boolean[] picked={false},retained={false};float[] nutrition={0};
        h.onEachTick(()->{
            if(f.closed||resident[0]==null)return;var actor=resident[0];
            picked[0]|=actor.getBehaviorId().equals("house_pickup");
            retained[0]|=actor.life().mealTicks()>0&&actor.life().mouth().is(ModItems.FOOD_SCRAP.get())&&actor.house().carrying().is(ModItems.NEST_LINT.get());
        });
        h.startSequence().thenWaitUntil(f::ready).thenExecute(()->{
            f.level.setDayTime(6000);f.level.updateSkyBrightness();
            f.level.setBlockAndUpdate(f.pos,ModBlocks.HOUSE_DUST_NEST.get().defaultBlockState());nest[0]=(HouseNestBlockEntity)f.level.getBlockEntity(f.pos);
            nest[0].deposit(new ItemStack(ModItems.NEST_LINT.get()));
            h.assertTrue(SupplyPiles.offer(f.level,f.pos.north(2),new ItemStack(ModItems.FOOD_SCRAP.get(),24),24)==24,"the full region contains real reachable food but no nest food");
            var actor=ModEntities.HOUSE_REIMU.get().create(f.level);h.assertTrue(actor!=null,"house resident allocation");resident[0]=actor;
            actor.setNoAi(true);actor.setPersistenceRequired();actor.moveTo(f.pos.getX()+.5,f.pos.getY(),f.pos.getZ()-1.2,0,0);actor.setOnGround(true);
            var family=actor.family().save();family.putBoolean("Initialized",true);family.putFloat("AdultScale",1);family.putFloat("Scale",1);actor.family().load(family);
            var life=actor.life().save();life.putFloat("Nutrition",20);life.putFloat("Energy",90);actor.life().load(life);nutrition[0]=actor.life().nutrition();
            h.assertTrue(f.level.addFreshEntity(actor)&&actor.house().choose(nest[0]),"the real resident knows its reachable empty-food natural nest");
            var house=actor.house().save();house.put("Carrying",new ItemStack(ModItems.NEST_LINT.get()).save(f.level.registryAccess()));actor.house().load(house);
            actor.house().dropCarrying();h.assertTrue(actor.house().carrying().is(ModItems.NEST_LINT.get())&&f.loose()==0,"a full region preserves an undepositable carried fiber instead of dropping or deleting it");
            actor.setNoAi(false);
        }).thenWaitUntil(()->h.assertTrue(picked[0]&&retained[0]&&resident[0].life().nutrition()>nutrition[0]&&f.count(ModItems.FOOD_SCRAP.get())<24,
                "real daytime hunger must reach, take and eat a ground crumb while keeping the other carried material: "+f.state()+" actor="+(resident[0]==null?"none":resident[0].position()+"/"+resident[0].getBehaviorId()+"/"+resident[0].life().nutrition()+"/"+resident[0].house().save())))
                .thenExecute(()->h.assertTrue(nest[0].food()==0&&f.loose()==0,"nutrition came from the actual pile and no fallback ItemEntity was created")).thenSucceed();
    }

    private static ItemStack batch(int index,int count) {
        var stack=new ItemStack(ModItems.FOOD_SCRAP.get(),count);CustomData.update(DataComponents.CUSTOM_DATA,stack,t->t.putInt("HouseTestBatch",index));return stack;
    }
    private static final class Fixture {
        final GameTestHelper helper;final ServerLevel level;final BlockPos pos;final int chunkX;
        final Map<BlockPos,BlockState> blocks=new LinkedHashMap<>();final List<CompoundTag> priorAreas=new ArrayList<>();
        final boolean forced;boolean closed;Runnable extraCleanup=()->{};
        Fixture(GameTestHelper h,int chunk) {
            helper=h;level=h.getLevel();chunkX=chunk;pos=new BlockPos(chunk*16+8,65,8);
            forced=level.getForcedChunks().contains(ChunkPos.asLong(chunk,0));acquireEnvironment(level);
            // Register before world setup or assertions so constructor failure still releases this lease.
            h.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
                public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
                public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){close();}
                public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){close();}
                public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
            });
            GameTestChunks.setForced(level,chunk,0,true);
            var saved=HouseholdEcologyData.get(level).save(new CompoundTag(),level.registryAccess());
            var areas=saved.getList("Areas",Tag.TAG_COMPOUND);
            for(int i=areas.size()-1;i>=0;i--)if(ours(areas.getCompound(i))){priorAreas.add(areas.getCompound(i).copy());areas.remove(i);}
            level.getDataStorage().set("mcyukkuri_household_ecology",HouseholdEcologyData.load(saved,level.registryAccess()));
            for(var cursor:BlockPos.betweenClosed(pos.offset(-5,-1,-5),pos.offset(5,5,5))) {
                var p=cursor.immutable();blocks.put(p,level.getBlockState(p));level.setBlockAndUpdate(p,p.getY()==64||p.getY()==69?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            h.assertTrue(HouseholdEcology.enabled(level),"natural-household fixture requires the ordinary arrivals, mob spawning and mobGriefing gates enabled");
        }
        boolean ours(CompoundTag area){return area.getInt("X")==chunkX&&area.getInt("Y")==8&&area.getInt("Z")==0;}
        void ready(){GameTestChunks.assertTicking(helper,chunkX,chunkX,0,0);}
        List<SupplyPileBlockEntity> piles(){var result=new ArrayList<SupplyPileBlockEntity>();for(var p:blocks.keySet())if(level.getBlockEntity(p) instanceof SupplyPileBlockEntity pile)result.add(pile);return result;}
        List<HouseNestBlockEntity> nests(){var result=new ArrayList<HouseNestBlockEntity>();for(var p:blocks.keySet())if(level.getBlockEntity(p) instanceof HouseNestBlockEntity nest)result.add(nest);return result;}
        List<YukkuriEntity> residents(){return level.getEntitiesOfClass(YukkuriEntity.class,new AABB(pos).inflate(10),e->e.getVariant().isHouse());}
        int count(Item item){return piles().stream().map(SupplyPileBlockEntity::contents).filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
        int loose(){return level.getEntitiesOfClass(ItemEntity.class,new AABB(pos).inflate(8)).size();}
        String state(){return HouseholdEcology.snapshot(level,pos)+", nests="+nests().size()+", residents="+residents().size()+", loose="+loose();}
        void clearResources(){for(var nest:nests()){nest.clean(true);level.removeBlock(nest.getBlockPos(),false);}for(var pile:piles()){pile.drain();level.removeBlock(pile.getBlockPos(),false);}}
        void close(){
            if(closed)return;closed=true;
            try {
            P0Control.write("household-"+chunkX,Map.of("state",state(),"tick",helper.getTick()));
            level.getEntitiesOfClass(Entity.class,new AABB(pos).inflate(10),e->e instanceof YukkuriEntity||e instanceof Villager||e instanceof ItemEntity).forEach(Entity::discard);
            clearResources();blocks.forEach(level::setBlockAndUpdate);
            var saved=HouseholdEcologyData.get(level).save(new CompoundTag(),level.registryAccess());var areas=saved.getList("Areas",Tag.TAG_COMPOUND);
            for(int i=areas.size()-1;i>=0;i--)if(ours(areas.getCompound(i)))areas.remove(i);priorAreas.forEach(areas::add);
            level.getDataStorage().set("mcyukkuri_household_ecology",HouseholdEcologyData.load(saved,level.registryAccess()));
            level.setChunkForced(chunkX,0,forced);extraCleanup.run();
            } finally {releaseEnvironment(level);}
        }
    }
    private HouseholdEcologyGameTests(){}
}
