package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class ShelterGameTests {
    private record Check(String name,boolean pass) {}
    private static final BlockPos ORIGIN=new BlockPos(968,65,8);
    private final List<Check> checks=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1400,batch="temperature_refuge_sleep")
    public static void temperatureRefugeSleep(GameTestHelper helper) { TemperatureChecks.refugeSleep(helper); }
    static void daylightFixture(ServerLevel level) {
        level.setDayTime(1000);level.setWeatherParameters(12000,0,false,false);level.setRainLevel(0);level.setThunderLevel(0);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1200,batch="shelter_resources")
    public static void householdResources(GameTestHelper helper) {
        var level=helper.getLevel();
        long previousDay=level.getDayTime();
        boolean previousDaylight=level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DAYLIGHT);
        boolean previousGrief=level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING);
        var previousForced=new java.util.HashSet<Long>(level.getForcedChunks());
        var trace=new ArrayList<java.util.Map<String,Object>>();
        YukkuriEntity[] subject=new YukkuriEntity[1];ShelterBlockEntity[] home=new ShelterBlockEntity[1];
        String[] phase={"setup"};boolean[] cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            P0Control.write("shelter-resource-trace",trace);
            var current=ShelterBlock.shelter(level,ORIGIN);if(current!=null)current.rollback();
            if(home[0]!=null&&home[0]!=current)home[0].rollback();
            level.getEntitiesOfClass(Entity.class,new AABB(944,60,-16,992,80,32),e->e instanceof YukkuriEntity||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
            for(int x=963;x<=973;x++)for(int z=5;z<=17;z++)if(x==963||x==973||z==5||z==17)
                for(int y=65;y<=68;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setDayTime(previousDay);
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(previousDaylight,level.getServer());
            level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING).set(previousGrief,level.getServer());
            for(int x=59;x<=61;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,previousForced.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
        });
        daylightFixture(level);
        for(int x=59;x<=61;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,true);
        var previous=ShelterBlock.shelter(level,ORIGIN);if(previous!=null)previous.rollback();
        for(int x=944;x<992;x++)for(int z=-16;z<32;z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=65;y<70;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        // A physical courtyard keeps every standing cell within the worker's eight-block
        // plant search, while retaining the real pit rim and five-block outdoor carry route.
        for(int x=963;x<=973;x++)for(int z=5;z<=17;z++)if(x==963||x==973||z==5||z==17)
            for(int y=65;y<=68;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.GLASS.defaultBlockState());
        net.minecraft.world.entity.item.ItemEntity[] material=new net.minecraft.world.entity.item.ItemEntity[1];
        BlockPos outside=ORIGIN.south(5);
        helper.onEachTick(()->{
            if(subject[0]==null||home[0]==null||cleaned[0])return;
            var worker=subject[0];
            trace.add(java.util.Map.of("tick",helper.getTick(),"phase",phase[0],"position",worker.position().toString(),
                "activity",worker.getBehaviorId(),"habitatActive",worker.habitat().active(),"carrying",worker.habitat().carrying().toString(),
                "lining",home[0].lining(),"plant",level.getBlockState(outside).toString(),
                "navigationDone",worker.getNavigation().isDone(),"wanted",new Vec3(worker.getMoveControl().getWantedX(),worker.getMoveControl().getWantedY(),worker.getMoveControl().getWantedZ()).toString()));
        });
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,59,61,-1,1)).thenIdle(20)
            .thenExecute(()->{
                level.getEntitiesOfClass(Entity.class,new AABB(ORIGIN).inflate(22),e->e instanceof YukkuriEntity||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
                level.setDayTime(1000);
                NaturalShelterFixture.pit(level,ORIGIN.below());
                home[0]=ShelterPlacement.place(level,new ShelterPlacement.Plan(ORIGIN.below(),ShelterBlock.Kind.BURROW,Direction.SOUTH)).shelter();
                helper.assertTrue(home[0].capacity()==9&&home[0].entrance().getY()==65,"Real below-ground pit has nine floor cells and a raised exit");
                home[0].initializeEncounter(4);
                var tag=home[0].saveWithFullMetadata(level.registryAccess());tag.putInt("Condition",70);tag.putInt("Lining",3);
                home[0].loadWithComponents(tag,level.registryAccess());
                helper.assertTrue(home[0].lining()==3,"Resource fixture begins with three bedding layers");
                subject[0]=ModEntities.REIMU.get().create(level);subject[0].moveTo(Vec3.atBottomCenterOf(home[0].getBlockPos()));subject[0].setOnGround(true);
                var life=subject[0].life().save();life.putFloat("Nutrition",100);life.putFloat("Energy",100);life.putFloat("Hygiene",100);subject[0].life().load(life);
                level.addFreshEntity(subject[0]);helper.assertTrue(subject[0].habitat().choose(home[0]),"Resource worker owns physical home");
                helper.assertTrue(subject[0].life().dryStand(outside)&&subject[0].life().reachable(outside,false),"Bounded courtyard retains a real reachable outdoor collection point");
                phase[0]="repair";
                material[0]=new net.minecraft.world.entity.item.ItemEntity(level,outside.getX()+.5,65,outside.getZ()+.5,new ItemStack(Items.STICK));
                material[0].setNoPickUpDelay();level.addFreshEntity(material[0]);
            }).thenWaitUntil(()->helper.assertTrue(home[0].condition()==85&&subject[0].habitat().carrying().isEmpty(),"Waiting for real pickup and return to repair"))
            .thenExecute(()->{
                helper.assertTrue(!material[0].isAlive(),"One actual stick consumed by repair");
                helper.assertTrue(subject[0].distanceToSqr(Vec3.atBottomCenterOf(ORIGIN))<2,"Repair worker walked back inside");
                helper.assertTrue(home[0].lining()==3,"Repair does not add bedding; lining="+home[0].lining());
                level.setBlockAndUpdate(outside,Blocks.SHORT_GRASS.defaultBlockState());
                phase[0]="harvest bedding";
            }).thenWaitUntil(()->helper.assertTrue(home[0].lining()==4&&subject[0].habitat().carrying().isEmpty(),"Waiting for harvesting and bedding return; lining="+home[0].lining()
                +", pos="+subject[0].blockPosition()+", activity="+subject[0].habitat().activity()+", active="+subject[0].habitat().active()+", carried="+subject[0].habitat().carrying()
                +", nutrition="+subject[0].life().nutrition()+", energy="+subject[0].life().energy()+", home="+subject[0].habitat().homeStand()+", plant="+level.getBlockState(outside)))
            .thenExecute(()->{
                helper.assertTrue(level.getBlockState(outside).isAir(),"One harvested real plant became bedding; state="+level.getBlockState(outside)+", lining="+home[0].lining());
                var dirty=home[0].saveWithFullMetadata(level.registryAccess());dirty.putInt("Dirt",60);home[0].loadWithComponents(dirty,level.registryAccess());
                level.setBlockAndUpdate(outside,Blocks.SHORT_GRASS.defaultBlockState());
                phase[0]="replace dirty bedding";
            }).thenWaitUntil(()->helper.assertTrue(home[0].dirt()<=31&&subject[0].habitat().carrying().isEmpty(),"Waiting for dirty bedding replacement"))
            .thenExecute(()->{
                helper.assertTrue(level.getBlockState(outside).isAir()&&home[0].lining()==4,"One new plant replaces dirty bedding without adding layers");
                var waste=new net.minecraft.world.entity.item.ItemEntity(level,ORIGIN.getX()+.5,65,ORIGIN.getZ()+.5,
                    new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get()));waste.setNoPickUpDelay();level.addFreshEntity(waste);
                phase[0]="carry waste";
            }).thenWaitUntil(()->helper.assertTrue(home[0].wasteInside().isEmpty()&&subject[0].habitat().carrying().isEmpty()
                &&!level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(ORIGIN).inflate(12),
                    item->item.getItem().is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get())&&!home[0].contains(item.blockPosition())).isEmpty(),"Waiting for actual waste carry outside"))
            .thenExecute(()->{
                int waste=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new AABB(ORIGIN).inflate(12),
                    item->item.getItem().is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get())).stream().mapToInt(item->item.getItem().getCount()).sum();
                helper.assertTrue(waste==1,"Cleaning relocates exactly one real waste item");
                P0Control.write("shelter-resource-checks",List.of(new Check("server AI climbs a real pit rim to collect a stick and return it to its boundary",true),
                    new Check("server AI harvests a real plant and returns it as bedding",true),new Check("server AI replaces dirty bedding using another real plant",true),new Check("server AI carries waste through entrance without duplication",true)));
                phase[0]="complete";cleanup.run();
            }).thenSucceed();
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="shelters")
    public static void shelterAcceptance(GameTestHelper helper) {
        var level=helper.getLevel();var test=new ShelterGameTests();
        daylightFixture(level);
        for(int x=59;x<=61;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,true);
        var previous=ShelterBlock.shelter(level,ORIGIN);if(previous!=null)previous.rollback();
        for(int x=944;x<992;x++)for(int z=-16;z<32;z++) {
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.GRASS_BLOCK.defaultBlockState());
            for(int y=65;y<70;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,59,61,-1,1))
            .thenExecute(()->{
                try{
                    level.getEntitiesOfClass(Entity.class,new AABB(ORIGIN).inflate(22),e->e instanceof YukkuriEntity||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
                    test.run(level);
                }catch(Throwable error){test.check("Unexpected exception: "+error,false);}
                finally {
                    var home=ShelterBlock.shelter(level,ORIGIN);if(home!=null)home.rollback();
                    level.getEntitiesOfClass(YukkuriEntity.class,new AABB(ORIGIN).inflate(20)).forEach(Entity::discard);
                    for(int x=59;x<=61;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,false);
                }
                P0Control.write("shelter-checks",test.checks);
                var failed=test.checks.stream().filter(c->!c.pass).toList();
                if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
            });
    }
    private void run(ServerLevel level) {
        var natural=new ShelterPlacement.Plan(ORIGIN,ShelterBlock.Kind.BURROW,Direction.SOUTH);
        check("open flat ground cannot become a built burrow",ShelterPlacement.place(level,natural)==null);
        for(var facing:Direction.Plane.HORIZONTAL) {
            NaturalShelterFixture.clearCave(level,ORIGIN);NaturalShelterFixture.cave(level,ORIGIN,facing);
            var terrain=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
            for(var pos:BlockPos.betweenClosed(ORIGIN.offset(-5,0,-5),ORIGIN.offset(5,3,5)))terrain.put(pos.immutable(),level.getBlockState(pos));
            var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(ORIGIN,ShelterBlock.Kind.BURROW,facing));
            check(facing+" existing cavity accepted",placed!=null);if(placed==null)continue;
            var home=placed.shelter();
            check(facing+" claiming changes only leaf marker",terrain.entrySet().stream().allMatch(e->e.getKey().equals(ORIGIN)||level.getBlockState(e.getKey()).equals(e.getValue())));
            check(facing+" capacity follows surveyed standing cells",home.capacity()==10&&home.beds().stream().distinct().count()==10);
            check(facing+" later encounters cannot overlap registered beds",home.beds().stream().filter(pos->!pos.equals(ORIGIN)).noneMatch(pos->{
                for(var direction:Direction.Plane.HORIZONTAL)if(ShelterPlacement.canPlace(level,new ShelterPlacement.Plan(pos,ShelterBlock.Kind.BURROW,direction)))return true;return false;}));
            var resident=ModEntities.REIMU.get().create(level);resident.moveTo(Vec3.atBottomCenterOf(home.entrance()));resident.setOnGround(true);resident.setNoAi(true);level.addFreshEntity(resident);
            check(facing+" resident walks through natural mouth",resident.life().reachable(ORIGIN,false)&&resident.habitat().choose(home));
            check(facing+" actual roof supplies cover",home.rainSheltered(ORIGIN)&&resident.life().covered(ORIGIN));
            check(facing+" leaf bedding uses one real transfer",home.deposit(new ItemStack(Items.OAK_LEAVES))&&home.lining()==1);
            check(facing+" three twigs are the entire boundary budget",home.deposit(new ItemStack(Items.STICK))&&home.deposit(new ItemStack(Items.STICK))&&home.deposit(new ItemStack(Items.STICK))&&!home.deposit(new ItemStack(Items.STICK))&&home.branches()==3);
            home.deposit(new ItemStack(Items.APPLE));var saved=home.saveWithFullMetadata(level.registryAccess());
            var copy=new ShelterBlockEntity(ORIGIN,home.getBlockState());copy.setLevel(level);copy.loadWithComponents(saved,level.registryAccess());
            check(facing+" site stores beds residents leaves and twigs through NBT",copy.beds().equals(home.beds())&&copy.owns(resident.getUUID())&&copy.food()==1&&copy.lining()==1&&copy.branches()==3);
            level.setWeatherParameters(0,12000,true,false);level.setRainLevel(1);for(int i=0;i<1200;i++)home.tick();
            check(facing+" cave stays dry without constructed roof",home.moisture()==0);
            level.setBlockAndUpdate(ORIGIN.above(2),Blocks.AIR.defaultBlockState());
            check(facing+" removed terrain roof immediately loses cover",!home.rainSheltered(ORIGIN)&&!resident.life().covered(ORIGIN));
            level.setBlockAndUpdate(ORIGIN.above(2),terrain.get(ORIGIN.above(2)));
            resident.discard();placed.rollback(level);
            check(facing+" removing nest leaves all cave terrain intact",terrain.entrySet().stream().allMatch(e->level.getBlockState(e.getKey()).equals(e.getValue())));
            daylightFixture(level);
        }
        NaturalShelterFixture.clearCave(level,ORIGIN);
        for(var side:List.of(Direction.NORTH,Direction.WEST,Direction.EAST))level.setBlockAndUpdate(ORIGIN.relative(side),Blocks.DIRT.defaultBlockState());
        var pit=ShelterPlacement.place(level,natural);check("existing shallow pit can hold one nest",pit!=null&&pit.shelter().capacity()==1);
        if(pit!=null) {
            var home=pit.shelter();home.initializeEncounter(1);
            var resident=ModEntities.REIMU.get().create(level);resident.moveTo(Vec3.atBottomCenterOf(home.entrance()));resident.setOnGround(true);resident.setNoAi(true);level.addFreshEntity(resident);
            check("pit is habitable in dry weather",resident.habitat().choose(home));
            check("leaves and boundary never grant pit a roof",!resident.life().covered(ORIGIN)&&!home.rainSheltered(ORIGIN));
            resident.moveTo(Vec3.atBottomCenterOf(ORIGIN));resident.setNoAi(false);
            var rested=resident.life().save();rested.putFloat("Nutrition",100);rested.putFloat("Energy",20);resident.life().load(rested);
            for(int i=0;i<200;i++)resident.life().tick();
            boolean planned=resident.life().plan();for(int i=0;i<25;i++)resident.life().tickGoal();
            check("dry pit resident actually enters sleep",planned&&resident.life().isSleeping());
            level.setWeatherParameters(0,12000,true,false);level.setRainLevel(1);for(int i=0;i<1200;i++)home.tick();
            resident.life().tickGoal();check("rain interrupts exposed pit sleep",!resident.life().isSleeping());
            check("open pit bedding soaks in rain",home.moisture()>=85&&!home.usable(resident));
            level.setBlockAndUpdate(home.entrance(),Blocks.WATER.defaultBlockState());check("flooded pit is unusable",home.flooded()&&!home.sound());
            level.setBlockAndUpdate(home.entrance(),Blocks.AIR.defaultBlockState());resident.discard();pit.rollback(level);daylightFixture(level);
        }
        NaturalShelterFixture.clearCave(level,ORIGIN);
        NaturalShelterFixture.cave(level,ORIGIN,Direction.SOUTH);
        for(var pos:BlockPos.betweenClosed(ORIGIN.offset(-2,2,-4),ORIGIN.offset(2,2,0)))level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        var familyPit=ShelterPlacement.place(level,new ShelterPlacement.Plan(ORIGIN.north(),ShelterBlock.Kind.BURROW,Direction.SOUTH));
        check("larger exposed pit reserves several real ground cells",familyPit!=null&&familyPit.shelter().capacity()>1&&familyPit.shelter().beds().stream().allMatch(pos->!familyPit.shelter().rainSheltered(pos)));
        if(familyPit!=null)familyPit.rollback(level);
        NaturalShelterFixture.clearCave(level,ORIGIN);
        var box=ShelterPlacement.place(level,new ShelterPlacement.Plan(ORIGIN,ShelterBlock.Kind.CARDBOARD,Direction.SOUTH));
        check("human placed discarded cardboard remains available",box!=null&&box.shelter().capacity()==1&&box.shelter().complete());
        if(box!=null)box.rollback(level);
        TemperatureChecks.run(level, ORIGIN, this::check);
    }
}
