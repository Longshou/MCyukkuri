package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Optional remote fixture: inventory ownership across interruptions, reloads, completed care and capsize. */
@PrefixGameTestTemplate(false)
public final class AquaticFamilyGameTests {
    private static final int X = 3472;
    private static void nutrition(YukkuriEntity y, float value) {
        var data=y.life().save(); data.putFloat("Nutrition",value); y.life().load(data);
    }
    private static void lateJuvenile(YukkuriEntity y,float scale) {
        var growth=y.family().save(); long maturity=y.family().maturityAge();
        growth.putBoolean("Initialized",true); growth.putString("Stage","JUVENILE"); growth.putFloat("Scale",scale);
        growth.putLong("Age",maturity*3/4); growth.putLong("Nourishment",maturity*9/16); y.family().load(growth);
    }
    /** Model an already-completed lesson so a late juvenile uses the real departure path, not an orphaned berth. */
    private static void readyForDeparture(YukkuriEntity y) {
        var voyage=y.aquaticLife().firstVoyage().save();
        voyage.putBoolean("Completed",true); voyage.putBoolean("Independent",false); voyage.putBoolean("InterruptedTrip",false);
        voyage.putInt("Cooldown",0); voyage.remove("Mentor"); voyage.remove("HomeHat");
        y.aquaticLife().firstVoyage().load(voyage);
    }
    private static void clearPupil(YukkuriEntity parent) {
        var voyage=parent.aquaticLife().firstVoyage().save();
        voyage.putBoolean("InterruptedTrip",false); voyage.remove("Pupil");
        parent.aquaticLife().firstVoyage().load(voyage);
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=680,batch="aquatic_family")
    public static void realServingsAndComfort(GameTestHelper helper) {
        var level=helper.getLevel(); long daytime=level.getDayTime();
        int chunk=X/16;
        boolean wasForced=level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk,0));
        Map<BlockPos,BlockState> terrain=new HashMap<>();
        for(var p:BlockPos.betweenClosed(X,64,0,X+15,70,15))terrain.put(p.immutable(),level.getBlockState(p));
        boolean[] cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            level.getEntitiesOfClass(Entity.class,new AABB(X,60,0,X+16,76,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity||e instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);level.setDayTime(daytime);GameTestChunks.setForced(level,chunk,0,wasForced);
        };
        GameTestChunks.setForced(level,X/16,0,true);
        for(var p:BlockPos.betweenClosed(X,64,0,X+15,69,15))
            level.setBlockAndUpdate(p,p.getY()<=65?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(var p:BlockPos.betweenClosed(X+5,65,5,X+11,65,9)) {
            level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(p.above(5),Blocks.STONE.defaultBlockState());
        }
        var parent=ModEntities.MARISA.get().create(level); var child=ModEntities.MARISA.get().create(level); var sibling=ModEntities.MARISA.get().create(level);
        for(var y:java.util.List.of(parent,child,sibling)) {
            WaddleGameTests.clearGoals(y); y.setPersistenceRequired();
            y.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        }
        parent.moveTo(X+4.5,66,7.5,0,0); child.moveTo(X+3.5,66,7.5,0,0); sibling.moveTo(X+2.5,66,7.5,0,0);
        var kin=child.family().save(); kin.putBoolean("Initialized",true); kin.putString("Stage","BABY");
        kin.putFloat("Scale",.32f); kin.putUUID("Mother",parent.getUUID()); child.family().load(kin);
        var siblingKin=sibling.family().save(); siblingKin.putBoolean("Initialized",true); siblingKin.putString("Stage","BABY");
        siblingKin.putFloat("Scale",.32f); siblingKin.putUUID("Mother",parent.getUUID()); sibling.family().load(siblingKin);
        var boat=new HatBoatEntity[1]; var care=parent.aquaticLife().care();
        // Seat changes are real rider moves, so each care phase is driven across actual server ticks.
        // Calling tend repeatedly at one game time would never let a service berth arrive.
        int[] careStage={0}; boolean[] chewed={false}, firstMash={false}, lastTend={false}; float[] nuzzleVitals={0,0,0};
        var lastServingTrace=new java.util.ArrayList<Map<String,Object>>();
        helper.onEachTick(()->{
            if(cleaned[0])return;
            var hat=boat[0];
            if(hat==null||parent.isRemoved()||child.isRemoved()||sibling.isRemoved())return;
            boolean bedtime=careStage[0]==3;
            lastTend[0]=careStage[0]>0&&care.tend(hat,bedtime);
            if(careStage[0]==1&&care.activity().equals("family_chew")&&care.serving().is(Items.APPLE)
                    &&child.life().mouth().isEmpty())chewed[0]=true;
            if(careStage[0]==1&&care.meals()==1&&io.github.longshou.mcyukkuri.life.InfantFood.isMash(child.life().mouth()))firstMash[0]=true;
            if(careStage[0]==5&&level.getGameTime()%40==0) {
                var trace=lastServingDebug(level,hat,care,parent,sibling,lastTend[0]);
                trace.put("event","wait_reservation"); lastServingTrace.add(trace);
                if(lastServingTrace.size()>16)lastServingTrace.remove(0);
                P0Control.write("aquatic-last-serving-trace",lastServingTrace);
            }
        });
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) {
                if(careStage[0]==5&&boat[0]!=null) {
                    var trace=lastServingDebug(level,boat[0],care,parent,sibling,lastTend[0]);
                    trace.put("event","test_failed"); lastServingTrace.add(trace);
                    P0Control.write("aquatic-last-serving-trace",lastServingTrace);
                }
                cleanup.run();
            }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(parent.blockPosition()),"family arena loaded"))
            .thenExecute(()->{
                level.setDayTime(6000); level.addFreshEntity(parent); level.addFreshEntity(child); level.addFreshEntity(sibling);
                boat[0]=HatBoatEntity.createFor(parent,new BlockPos(X+5,65,7));
                helper.assertTrue(boat[0]!=null && boat[0].boardYoung(child) && boat[0].boardYoung(sibling),"real parent and both children aboard");
                boat[0].cargo().store(new ItemStack(Items.APPLE,2),2,false);
                boat[0].cargo().givePaddle(new ItemStack(Items.STICK),false); nutrition(parent,85); nutrition(child,40); nutrition(sibling,85);
            }).thenIdle(3).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(hat.protects(parent)&&hat.protects(child),"both bodies are dry passengers");
                helper.assertTrue(HatBoatRoute.restingPlace(hat,parent.aquaticLife().boardingBank())!=null,"quiet surface can be found");
                helper.assertTrue(HatVessel.braidCanServe(parent,child),"the real same-hat close-care boundary reaches this child's assigned day seat");
                helper.assertTrue(!hat.cargo().feed(child)&&hat.cargo().count()==2,"a dependent newborn cannot bypass care by eating boat cargo alone");
                helper.assertTrue(care.tend(hat,false)&&hat.cargo().count()==1&&care.serving().getCount()==1
                        &&child.life().mouth().isEmpty()&&child.life().nutrition()==40,"serving reserves one real item without feeding early");
                helper.assertTrue(care.activity().equals("family_chew"),"newborn care uses the chew activity before a serving is prepared");
                care.cancel(); care.cancel();
                helper.assertTrue(hat.cargo().count()==2&&care.serving().isEmpty()&&care.meals()==0,"interruption refunds exactly once");
                care.tend(hat,false); care.load(care.save()); care.clock(); care.clock();
                helper.assertTrue(hat.cargo().count()==2&&!care.active()&&care.meals()==0,"reload recovers serving without replaying care rewards");
                care.tend(hat,false); parent.setNoAi(true); care.clock(); parent.setNoAi(false);
                helper.assertTrue(hat.cargo().count()==2&&!care.active(),"disabling AI cancels and refunds pending care");
                careStage[0]=1;
            }).thenWaitUntil(()->helper.assertTrue(care.meals()==1&&firstMash[0],
                    "real seat movement, forty chew ticks and twenty braid-contact ticks transfer one mash serving"))
            .thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(chewed[0]&&hat.cargo().count()==1&&care.serving().isEmpty()
                        &&child.life().mouth().getCount()==1&&io.github.longshou.mcyukkuri.life.InfantFood.isMash(child.life().mouth())&&child.life().nutrition()==40,
                        "parent holds one real apple while chewing, then transfers that item as mash without gaining nutrition");
                careStage[0]=0;
                care.cancel();
                helper.assertTrue(hat.cargo().count()==1&&child.life().mouth().getCount()==1,"after handoff cancellation cannot duplicate the meal");
            }).thenIdle(70).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(child.life().nutrition()>40&&child.life().mouth().isEmpty(),"only actual chewing and swallowing supply nutrition");
                nutrition(parent,85); nutrition(child,85);
                var needs=child.dailyLife().save(); needs.putInt("Affection",60); child.dailyLife().load(needs);
                nuzzleVitals[0]=child.life().wetness(); nuzzleVitals[1]=child.life().hygiene(); nuzzleVitals[2]=child.getHealth();
                careStage[0]=2;
            }).thenWaitUntil(()->helper.assertTrue(care.comforts()==1,
                    "nuzzle completes across its real service interval"))
            .thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(care.comforts()==1&&child.dailyLife().affectionNeed()==25,"completed nuzzle eases loneliness");
                helper.assertTrue(child.life().wetness()==nuzzleVitals[0]&&child.life().hygiene()==nuzzleVitals[1]&&child.getHealth()==nuzzleVitals[2]
                        &&child.life().nutrition()==85,"nuzzle does not wash, heal or feed");
                level.setDayTime(13000);
                careStage[0]=3;
            }).thenWaitUntil(()->helper.assertTrue(care.songs()==1&&care.bedtimeReady(),
                    "lullaby completes before physiological sleep over real night ticks"))
            .thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(HatVessel.braidCanServe(parent,child)&&HatVessel.braidCanServe(parent,sibling),
                        "night seat reflow keeps both protected children within the boat-care boundary");
                helper.assertTrue(care.songs()==1&&care.bedtimeReady()&&!parent.life().isSleeping(),"lullaby finishes before physiological sleep");
                careStage[0]=4; nutrition(child,20);
            }).thenWaitUntil(()->helper.assertTrue(care.meals()==2&&boat[0].cargo().count()==0
                    &&io.github.longshou.mcyukkuri.life.InfantFood.isMash(child.life().mouth()),
                    "night-time two-child seating still completes a real prepared serving"))
            .thenIdle(70).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(child.life().mouth().isEmpty(),"second mash also reaches a real swallowing tick");
                // The night feed above is already proven. Begin this final reservation case from an
                // idle, awake daytime manifest so a prior completed service cannot mask it.
                careStage[0]=0; care.cancel(); level.setDayTime(6000);
                parent.life().wakeInHat(); child.life().wakeInHat(); sibling.life().wakeInHat();
                nutrition(parent,85); nutrition(child,85); nutrition(sibling,20);
                helper.assertTrue(!care.active()&&hat.cargo().count()==0&&HatVessel.braidCanServe(parent,sibling),
                        "the final sibling serving starts from an idle protected hat");
                hat.cargo().store(new ItemStack(Items.APPLE),1,false); careStage[0]=5;
            }).thenWaitUntil(()->helper.assertTrue(care.active()&&care.serving().getCount()==1&&boat[0].cargo().count()==0,
                    "last serving is physically reserved before it can be handed off"))
            .thenExecute(()->{
                var hat=boat[0]; careStage[0]=0;
                hat.capsize(); care.clock(); care.clock();
                int apples=level.getEntitiesOfClass(ItemEntity.class,new AABB(X,64,0,X+16,75,16),i->i.getItem().is(Items.APPLE))
                        .stream().mapToInt(i->i.getItem().getCount()).sum();
                helper.assertTrue(apples==1&&care.serving().isEmpty()&&!care.active(),"capsize drops the unserved item exactly once");
                helper.assertTrue(parent.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA,"family care never changes aquatic identity");
            }).thenSucceed();
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=80,batch="aquatic_family")
    public static void juvenileSailingGateAndBrim(GameTestHelper helper) {
        var child=ModEntities.MARISA.get().create(helper.getLevel());
        child.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        var growth=child.family().save();growth.putBoolean("Initialized",true);growth.putString("Stage","JUVENILE");growth.putFloat("Scale",.34f);child.family().load(growth);
        var voyage=child.aquaticLife().firstVoyage();
        helper.assertTrue(!voyage.readySize()&&!voyage.mayLaunch(),"small juveniles cannot launch their own hats");
        growth.putFloat("Scale",.35f);child.family().load(growth);
        helper.assertTrue(voyage.readySize()&&!voyage.mayLaunch(),"35 percent size still requires the first supervised voyage");
        var saved=voyage.save();saved.putBoolean("InterruptedTrip",true);saved.putLong("Bank",new BlockPos(0,65,0).asLong());
        saved.putLong("Harbor",new BlockPos(1,64,0).asLong());saved.putString("Dimension",helper.getLevel().dimension().location().toString());
        voyage.load(saved);
        helper.assertTrue(!voyage.completed()&&!voyage.mayLaunch()&&voyage.activity().equals("voyage_return"),"reload returns without awarding a successful lesson");
        saved.putBoolean("Completed",true);saved.putBoolean("InterruptedTrip",false);voyage.load(saved);
        helper.assertTrue(voyage.completed()&&voyage.mayLaunch(),"completed milestone persists and unlocks juvenile sailing");
        var v=io.github.longshou.mcyukkuri.subspecies.HatVessel.brimPlace(1.1,java.util.List.of());
        helper.assertTrue(v!=null,"small young has a real brim place");
        for(int i=0;i<32;i++){
            double h=io.github.longshou.mcyukkuri.subspecies.HatVessel.surface(io.github.longshou.mcyukkuri.subspecies.HatVessel.Mode.FLOAT,
                    v.x+1.1*Math.cos(i*Math.PI/16),v.z+1.1*Math.sin(i*Math.PI/16));
            helper.assertTrue(Double.isFinite(h)&&Math.abs(h-io.github.longshou.mcyukkuri.subspecies.HatVessel.FREEBOARD)<.001,"entire young footprint supported above water");
        }
        helper.assertTrue(io.github.longshou.mcyukkuri.subspecies.HatVessel.brimPlace(3,java.util.List.of())==null,"oversized young never hang beyond the brim");
        helper.succeed();
    }
    /** Separate batch: this daylight voyage must not overlap the family-care fixture changing the world to night. */
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=760,batch="aquatic_family_voyage")
    public static void firstVoyageFromFather(GameTestHelper helper) {
        var level=helper.getLevel();long day=level.getDayTime();int x=X+32;
        int chunk=x/16;
        boolean wasForced=level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk,0));
        Map<BlockPos,BlockState> terrain=new HashMap<>();
        for(var p:BlockPos.betweenClosed(x,64,0,x+15,71,15))terrain.put(p.immutable(),level.getBlockState(p));
        boolean[] cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            level.getEntitiesOfClass(Entity.class,new AABB(x,60,0,x+16,76,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity||e instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);level.setDayTime(day);GameTestChunks.setForced(level,chunk,0,wasForced);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        GameTestChunks.setForced(level,chunk,0,true);level.setDayTime(1000);
        for(var p:BlockPos.betweenClosed(x,64,0,x+15,71,15))
            level.setBlockAndUpdate(p,p.getY()<=65?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(var p:BlockPos.betweenClosed(x+4,65,2,x+13,65,13)){
            level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState());level.setBlockAndUpdate(p.above(5),Blocks.STONE.defaultBlockState());
        }
        var father=ModEntities.MARISA.get().create(level);WaddleGameTests.clearGoals(father);
        father.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        var adult=father.family().save();adult.putBoolean("Initialized",true);adult.putString("Stage","ADULT");adult.putFloat("Scale",1);father.family().load(adult);
        father.moveTo(x+3.5,66,7.5,0,0);level.addFreshEntity(father);nutrition(father,85);
        var home=HatBoatEntity.createFor(father,new BlockPos(x+5,65,7));helper.assertTrue(home!=null,"father uses his actual hat");
        home.cargo().givePaddle(new ItemStack(Items.STICK),false);
        var shore=father.aquaticLife().shore();
        helper.assertTrue(shore.coastal(new BlockPos(x+3,66,7))&&!shore.coastal(new BlockPos(x,66,7)),"land activity stays in the two-block bank strip");
        var crossing=new net.minecraft.world.level.pathfinder.Path(java.util.List.of(
                new net.minecraft.world.level.pathfinder.Node(x+3,66,8),new net.minecraft.world.level.pathfinder.Node(x,66,7),
                new net.minecraft.world.level.pathfinder.Node(x+3,66,7)),new BlockPos(x+3,66,7),true);
        helper.assertTrue(!shore.allows(crossing),"a coastal destination cannot hide an inland detour");
        var strain=shore.save();strain.putInt("LandTicks",600);shore.load(strain);
        var children=new java.util.ArrayList<YukkuriEntity>();
        for(int i=0;i<5;i++){
            var y=ModEntities.MARISA.get().create(level);WaddleGameTests.clearGoals(y);
            y.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
            var kin=y.family().save();kin.putBoolean("Initialized",true);kin.putString("Stage","JUVENILE");kin.putFloat("Scale",i==0?.35f:.20f);
            kin.putUUID("Father",father.getUUID());y.family().load(kin);y.moveTo(home.getX(),66,home.getZ(),0,0);level.addFreshEntity(y);nutrition(y,85);
            helper.assertTrue(home.boardYoung(y),"each of five siblings can board");children.add(y);
        }
        var child=children.getFirst();child.aquaticLife().giveStick(new ItemStack(Items.STICK),false);
        helper.onEachTick(()->{
            if(cleaned[0]||!father.isAlive()||father.isRemoved())return;
            if(child.aquaticLife().firstVoyage().active())child.aquaticLife().firstVoyage().tick();
            if(father.aquaticLife().firstVoyage().active())father.aquaticLife().firstVoyage().tick();
        });
        helper.startSequence().thenIdle(40).thenExecute(()->{
            helper.assertTrue(shore.landTicks()<600,"real dry hat passengers recover land strain");
            helper.assertTrue(home.onBrim(child)&&child.aquaticLife().firstVoyage().tryStart(),"lesson starts on the father's brim");
            helper.assertTrue(home.voyageBerth(child)!=null&&!home.hasRoomForYoung(),"five-child manifest retains the pupil's berth");
        }).thenIdle(100).thenExecute(()->{
            var own=HatBoatEntity.owned(child);
            helper.assertTrue(own!=null&&own!=home&&child.getVehicle()==own,"pupil boards its own real hat from the father's boat");
            helper.assertTrue(home.youngAboard()==4&&!home.hasRoomForYoung()&&home.hasRoomFor(child),"away pupil still occupies the fifth place");
            helper.assertTrue(father.getVehicle()==home&&children.subList(1,5).stream().allMatch(y->y.getVehicle()==home),"father and four siblings remain afloat");
            helper.assertTrue(!child.life().hasAccessory()&&own.cargo().paddle()&&!child.aquaticLife().hasStick(),"hat and paddle have one physical owner");
            child.aquaticLife().firstVoyage().load(child.aquaticLife().firstVoyage().save());
        }).thenIdle(70).thenExecute(()->{
            helper.assertTrue(child.getVehicle()==home&&HatBoatEntity.owned(child)==null&&home.youngAboard()==5,"interrupted pupil returns to the reserved berth");
            helper.assertTrue(child.life().hasAccessory()&&child.aquaticLife().hasStick()&&!child.aquaticLife().firstVoyage().completed(),"same hat and paddle return without a milestone reward");
            helper.assertTrue(home.cargo().paddle()&&home.protects(father)&&children.stream().allMatch(home::protects),"all five children remain dry passengers");
            lateJuvenile(child,.60f);
            double r=HatVessel.radius(child.getBbWidth())/home.unit();
            helper.assertTrue(child.childhood().canLiveIndependently(),"0.60 late juvenile meets the separate independence capability gate");
            helper.assertTrue(HatVessel.brimPlace(r,java.util.List.of(),true)==null,"0.60 subadult no longer fits the small-juvenile full-body brim strip");
            readyForDeparture(child);
            helper.assertTrue(child.aquaticLife().firstVoyage().tryStart(),"0.60 subadult reserves the supported departure edge through its real departure path");
        }).thenWaitUntil(()->helper.assertTrue(home.onVoyageBerth(child),"0.60 departure berth finishes its actual rider SeatMove"))
        .thenExecute(()->{
            helper.assertTrue(home.voyageBerth(child)!=null&&home.onVoyageBerth(child),"large subadult reaches the dedicated supported departure seat after its real move");
            home.releaseVoyageBerth(child.getUUID()); clearPupil(father); readyForDeparture(child); lateJuvenile(child,.70f);
        }).thenIdle(80).thenExecute(()->{
            double r=HatVessel.radius(child.getBbWidth())/home.unit();
            helper.assertTrue(child.childhood().canLiveIndependently(),"0.70 late juvenile remains eligible before adult stage");
            helper.assertTrue(HatVessel.brimPlace(r,java.util.List.of(),true)==null,"0.70 subadult likewise cannot use the narrow full-body brim strip");
            helper.assertTrue(child.aquaticLife().firstVoyage().tryStart(),"0.70 subadult can reserve the dedicated front-edge departure seat through its real departure path");
        }).thenWaitUntil(()->helper.assertTrue(home.onVoyageBerth(child),"0.70 departure berth also finishes physical rider movement"))
        .thenExecute(()->{
            helper.assertTrue(home.voyageBerth(child)!=null&&home.onVoyageBerth(child),"departure remains at the dedicated supported edge after the second real move");
        }).thenWaitUntil(()->helper.assertTrue(child.aquaticLife().firstVoyage().independent(),"eligible subadult completes the real away-voyage before becoming independent"))
        .thenExecute(()->{
            var own=HatBoatEntity.owned(child);
            helper.assertTrue(own!=null&&own!=home&&child.getVehicle()==own&&own.protects(child),"independent subadult retains the own afloat hat reached through the real departure");
            helper.assertTrue(home.voyageBerth(child)==null&&home.youngAboard()==4&&home.hasRoomForYoung(),"departure releases exactly the retained family berth");
            helper.assertTrue(father.getVehicle()==home&&children.subList(1,5).stream().allMatch(y->y.getVehicle()==home),"father and all four nondeparting siblings remain on their original hat");
            helper.assertTrue(!child.life().hasAccessory()&&own.cargo().paddle()&&!child.aquaticLife().hasStick(),"departed subadult has one hat and paddle, without duplicate personal equipment");
        }).thenSucceed();
    }
    /** Diagnostic emitted only when the last-serving wait fails; it does not schedule or move a care seat. */
    private static Map<String,Object> lastServingDebug(net.minecraft.server.level.ServerLevel level,HatBoatEntity hat,
            io.github.longshou.mcyukkuri.subspecies.HatFamilyCare care,YukkuriEntity parent,YukkuriEntity sibling,boolean tendResult) {
        var state=new java.util.LinkedHashMap<String,Object>();
        state.put("gameTime",level.getGameTime()); state.put("dayTime",level.getDayTime());
        state.put("careActivity",care.activity()); state.put("careActive",care.active()); state.put("careSave",care.save().toString());
        state.put("normalTendResult",tendResult); state.put("cargo",hat.cargo().items().toString()); state.put("cargoCount",hat.cargo().count());
        state.put("hatState",hat.state().toString()); state.put("hatTowed",hat.towed()); state.put("hatFlooding",hat.flooding()); state.put("hatIncoming",hat.hasIncoming());
        state.put("parentProtects",hat.protects(parent)); state.put("siblingProtects",hat.protects(sibling));
        state.put("sameHat",parent.getVehicle()==hat&&sibling.getVehicle()==hat); state.put("lineOfSight",parent.hasLineOfSight(sibling));
        state.put("braidCanServe",HatVessel.braidCanServe(parent,sibling)); state.put("siblingChangingSeat",hat.changingSeat(sibling)); state.put("siblingCareSeatReady",hat.careSeatReady(sibling));
        state.put("parentNutrition",parent.life().nutrition()); state.put("parentEnergy",parent.life().energy()); state.put("parentMealTicks",parent.life().mealTicks()); state.put("parentSleeping",parent.life().isSleeping());
        state.put("siblingNutrition",sibling.life().nutrition()); state.put("siblingEnergy",sibling.life().energy()); state.put("siblingMealTicks",sibling.life().mealTicks()); state.put("siblingSleeping",sibling.life().isSleeping());
        state.put("siblingCareFood",sibling.life().canReceiveCareFood()); state.put("parentCaresForSibling",parent.family().caresFor(sibling));
        return state;
    }
    private AquaticFamilyGameTests() {}
}
