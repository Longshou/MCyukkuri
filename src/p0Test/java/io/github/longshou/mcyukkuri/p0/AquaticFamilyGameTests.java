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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Optional remote fixture: inventory ownership across interruptions, reloads, completed care and capsize. */
@PrefixGameTestTemplate(false)
public final class AquaticFamilyGameTests {
    private static final int X = 3472;
    private static boolean wasteFrom(ItemEntity item,YukkuriEntity source) {
        if (!item.getItem().is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get())) return false;
        var data=item.getItem().getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        return data.hasUUID("Source") && data.getUUID("Source").equals(source.getUUID());
    }
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
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1200,batch="aquatic_family")
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
        kin.putFloat("Scale",io.github.longshou.mcyukkuri.family.LiveBirthGeometry.BABY_SCALE); kin.putUUID("Mother",parent.getUUID()); child.family().load(kin);
        var siblingKin=sibling.family().save(); siblingKin.putBoolean("Initialized",true); siblingKin.putString("Stage","BABY");
        siblingKin.putFloat("Scale",io.github.longshou.mcyukkuri.family.LiveBirthGeometry.BABY_SCALE); siblingKin.putUUID("Mother",parent.getUUID()); sibling.family().load(siblingKin);
        var boat=new HatBoatEntity[1]; var care=parent.aquaticLife().care();
        // Seat changes are real rider moves, so each care phase is driven across actual server ticks.
        // Calling tend repeatedly at one game time would never let a service berth arrive.
        int[] careStage={0}; boolean[] chewed={false}, firstMash={false}, lastTend={false}, nuzzleRelieved={false}, nuzzlePure={false};
        var lastServingTrace=new java.util.ArrayList<Map<String,Object>>();
        float[] heldHeading={Float.NaN};
        helper.onEachTick(()->{
            if(cleaned[0])return;
            var hat=boat[0];
            if(hat==null||parent.isRemoved()||child.isRemoved()||sibling.isRemoved())return;
            boolean bedtime=careStage[0]==3;
            int comfortsBefore=care.comforts(), affectionBefore=child.dailyLife().affectionNeed();
            float wetBefore=child.life().wetness(), hygieneBefore=child.life().hygiene(), healthBefore=child.getHealth(), foodBefore=child.life().nutrition();
            lastTend[0]=careStage[0]>0&&care.tend(hat,bedtime);
            if (care.comforts()>comfortsBefore) {
                nuzzleRelieved[0]=child.dailyLife().affectionNeed()==Math.max(0,affectionBefore-35);
                nuzzlePure[0]=child.life().wetness()==wetBefore && child.life().hygiene()==hygieneBefore
                        && child.getHealth()==healthBefore && child.life().nutrition()==foodBefore;
            }
            if(care.active()) {
                if(Float.isFinite(heldHeading[0]))helper.assertTrue(Math.abs(net.minecraft.util.Mth.wrapDegrees(hat.getYRot()-heldHeading[0]))<.01,
                        "turning a caregiving head never turns the supported hat floor beneath the pair");
                heldHeading[0]=hat.getYRot();
            } else heldHeading[0]=Float.NaN;
            if (careStage[0] == 4 && care.meals() == 2 && child.life().mealTicks() > 0) {
                helper.assertTrue(care.active() && hat.careSeatReady(child), "served child retains real parent contact until swallowing finishes");
                helper.assertTrue(care.response(child).equals("boat_eat"), "a swallowing child stops begging for its already-delivered serving");
            }
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
                if(boat[0]!=null) {
                    var trace=lastServingDebug(level,boat[0],care,parent,careStage[0]==5?sibling:child,lastTend[0]);
                    trace.put("event","test_failed");trace.put("stage",careStage[0]);lastServingTrace.add(trace);
                    trace.put("cargoRoom",boat[0].cargoRoom());
                    trace.put("cargoBerths",HatVessel.cargoBerths(boat[0].mode(),8).toString());
                    trace.put("passengers",boat[0].getPassengers().stream().map(p->p.getUUID()+" local="+boat[0].toLocal(p.position())
                            +" width="+p.getBbWidth()+" height="+p.getBbHeight()).toList());
                    trace.put("looseItems",level.getEntitiesOfClass(ItemEntity.class,new AABB(X,60,0,X+16,76,16))
                            .stream().map(item->item.getItem()+" at "+item.position()).toList());
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
                boat[0].cargo().givePaddle(new ItemStack(Items.STICK),false); nutrition(parent,85); nutrition(child,40); nutrition(sibling,85);
            }).thenIdle(3).thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(hat.protects(parent)&&hat.protects(child),"both bodies are dry passengers");
                var provisions=new ItemStack(Items.APPLE,2);
                helper.assertTrue(hat.cargo().store(provisions,2,false)==2&&provisions.isEmpty()&&hat.cargo().count()==2,
                        "two real servings fit after the passengers occupy their supported seats: room="+hat.cargoRoom());
                helper.assertTrue(HatBoatRoute.restingPlace(hat,parent.aquaticLife().boardingBank())!=null,"quiet surface can be found");
                helper.assertTrue(HatVessel.braidCanServe(parent,child),"the real same-hat close-care boundary reaches this child's assigned day seat");
                helper.assertTrue(!hat.cargo().feed(child)&&hat.cargo().count()==2,"a dependent newborn cannot bypass care by eating boat cargo alone");
                helper.assertTrue(care.tend(hat,false)&&hat.cargo().count()==1&&care.serving().getCount()==1
                        &&child.life().mouth().isEmpty()&&child.life().nutrition()==40,"serving reserves one real item without feeding early");
                helper.assertTrue(care.activity().equals("family_chew"),"newborn care uses the chew activity before a serving is prepared");
                care.cancel(); care.cancel();
                helper.assertTrue(hat.cargo().count()==2&&care.serving().isEmpty()&&care.meals()==0,
                        "interruption refunds exactly once: cargo="+hat.cargo().count()+", room="+hat.cargoRoom()
                                +", serving="+care.serving()+", meals="+care.meals());
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
                careStage[0]=2;
            }).thenWaitUntil(()->helper.assertTrue(care.comforts()==1,
                    "nuzzle completes across its real service interval"))
            .thenExecute(()->{
                var hat=boat[0];
                helper.assertTrue(care.comforts()==1&&nuzzleRelieved[0],"completed nuzzle removes exactly 35 affection need at actual contact completion");
                helper.assertTrue(nuzzlePure[0],"the exact nuzzle reward step does not wash, heal or feed");
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
    /** The whole contract runs through real rider movement, contact, swallowing, brim release and return. */
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="aquatic_family_contact")
    public static void supportedCareAndFirstToiletReturn(GameTestHelper helper) {
        var level = helper.getLevel(); long day = level.getDayTime(); int x = X + 64, chunk = x / 16;
        boolean forced = level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk, 0));
        Map<BlockPos,BlockState> terrain = new HashMap<>();
        for (var p : BlockPos.betweenClosed(x,64,0,x+15,71,15)) terrain.put(p.immutable(),level.getBlockState(p));
        boolean[] cleaned = {false};
        YukkuriEntity[] watch={null,null};HatBoatEntity[] watchHat={null};
        Runnable cleanup = () -> {
            if (cleaned[0]) return; cleaned[0] = true;
            level.getEntitiesOfClass(Entity.class,new AABB(x,60,0,x+16,76,16),
                    e -> e instanceof YukkuriEntity || e instanceof HatBoatEntity || e instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate); level.setDayTime(day); GameTestChunks.setForced(level,chunk,0,forced);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) {
                if(watch[0]!=null && watch[1]!=null && watchHat[0]!=null)
                    P0Control.write("hat-supported-care-failure",lastServingDebug(level,watchHat[0],watch[0].aquaticLife().care(),watch[0],watch[1],false));
                cleanup.run();
            }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        GameTestChunks.setForced(level,chunk,0,true); level.setDayTime(6000);
        for (var p : BlockPos.betweenClosed(x,64,0,x+15,71,15))
            level.setBlockAndUpdate(p,p.getY()<=65 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        for (var p : BlockPos.betweenClosed(x+2,65,2,x+13,65,13)) {
            level.setBlockAndUpdate(p,Blocks.WATER.defaultBlockState()); level.setBlockAndUpdate(p.above(5),Blocks.STONE.defaultBlockState());
        }
        var parent = ModEntities.MARISA.get().create(level); var child = ModEntities.MARISA.get().create(level);
        watch[0]=parent;watch[1]=child;
        for (var y : java.util.List.of(parent,child)) {
            WaddleGameTests.clearGoals(y); y.setPersistenceRequired();
            y.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        }
        var kin = child.family().save(); kin.putBoolean("Initialized",true); kin.putString("Stage","BABY");
        kin.putFloat("Scale",.14f); kin.putUUID("Mother",parent.getUUID()); child.family().load(kin);
        var infant = child.childhood().save(); infant.putBoolean("Newborn",true); infant.putBoolean("Landed",true);
        infant.putBoolean("FirstToiletDone",false); child.childhood().load(infant);
        parent.moveTo(x+4.5,66,7.5,0,0); child.moveTo(x+4.5,66,7.5,0,0);
        level.addFreshEntity(parent); level.addFreshEntity(child); nutrition(parent,85); nutrition(child,20);
        var needs = child.life().save(); needs.putFloat("Bladder",20); child.life().load(needs);
        var boat = HatBoatEntity.createFor(parent,new BlockPos(x+6,65,7));
        watchHat[0]=boat;
        helper.assertTrue(boat != null && boat.boardYoung(child),"care pair boards the real hat");
        boat.cargo().store(new ItemStack(Items.APPLE),1,false);
        var care = parent.aquaticLife().care();
        boolean[] started = {false}, childCarried = {false}, parentWalked = {false}, swallowed = {false}, rearContact = {false}, brimReached = {false};
        int[] meals = {0}; Vec3[] original = {null};
        helper.onEachTick(() -> {
            if (cleaned[0] || !started[0]) return;
            care.tend(boat,false);
            for (var rider : java.util.List.of(parent,child)) if (boat.walkingInHat(rider)) {
                if (rider == parent) parentWalked[0] = true;
                Vec3 local = boat.toLocal(rider.position());
                double radius = HatVessel.radius(rider.getBbWidth()) / boat.unit();
                double support = HatVessel.footprint(boat.mode(),local.x,local.z,radius);
                helper.assertTrue(Double.isFinite(HatVessel.surface(boat.mode(),local.x,local.z)) && local.y >= support-.03,
                        "each walking rider remains over the real supported hat surface");
            }
            if (boat.hatCarrier(child)!=null) {
                childCarried[0]=true;
                helper.assertTrue(boat.hatCarrier(child).equals(parent.getUUID()) && !child.childhood().canJump(),"an untrained newborn crosses layers with its real parent as holder");
                helper.assertTrue(child.position().distanceTo(boat.hatCarryAnchor(child))<.04,"held child follows the synchronized parent anchor");
                if(boat.walkingInHat(parent)) {
                    Vec3 p=io.github.longshou.mcyukkuri.subspecies.HatCareAnchor.mouth(parent,"boat_carry_young");
                    Vec3 c=io.github.longshou.mcyukkuri.subspecies.HatCareAnchor.mouth(child,"boat_be_carried");
                    helper.assertTrue(p!=null && c!=null,"both selected carry poses export a real mouth anchor");
                    Vec3 parentMouth=parent.position().add(p.yRot((float)Math.toRadians(180-parent.yBodyRot)));
                    Vec3 childMouth=child.position().add(c.yRot((float)Math.toRadians(180-child.yBodyRot)));
                    helper.assertTrue(parentMouth.distanceTo(childMouth)<.015,"held child meets the actual authored mouths including root pivot and floor lift");
                }
            }
            if (care.meals() > meals[0]) {
                helper.assertTrue(boat.careSeatReady(child) && io.github.longshou.mcyukkuri.family.FamilyFeedingContact.mouthsMeet(parent,child),
                        "ownership changes only after both real service positions and mouths meet");
                meals[0] = care.meals();
            }
            if (care.meals() == 1 && child.life().mealTicks() > 0)
                helper.assertTrue(care.active() && boat.careSeatReady(child),"parent retains its service seat throughout actual swallowing");
            if (care.meals() == 1 && child.life().mealTicks() == 0) swallowed[0] = true;
            if (care.activity().equals("first_toilet_assist") && care.contactProgress() > 0) {
                rearContact[0] = true;
                if (care.contactProgress() < io.github.longshou.mcyukkuri.family.ChildhoodLife.FIRST_TOILET_CONTACT_TICKS)
                    helper.assertTrue(io.github.longshou.mcyukkuri.family.ChildhoodLife.toiletContact(parent,child),"rear-care progress requires actual rear contact");
            }
            if (!child.childhood().firstToiletDone() && boat.toiletSeatReady(child)) {
                brimReached[0] = true;
                helper.assertTrue(rearContact[0] && boat.toiletAssistanceValidated(child) && boat.toiletDropPosition(child) != null,
                        "first waste release follows rear care and a supported brim over open water");
                helper.assertTrue(parent.distanceTo(child) < 1,"parent escorts the child to the brim");
            }
            if (!child.childhood().firstToiletDone())
                helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(x,64,0,x+16,75,16),i->wasteFrom(i,child)).isEmpty(),"no child waste appears before the real first-toilet completion");
        });
        helper.startSequence().thenIdle(3).thenExecute(() -> { original[0] = boat.toLocal(child.position()); started[0] = true; })
            .thenWaitUntil(() -> helper.assertTrue(child.childhood().firstToiletDone(),"hungry newborn is fed before its assisted first toilet finishes"))
            .thenWaitUntil(() -> helper.assertTrue(!care.active() && !boat.changingSeat(child) && !boat.transportingInHat(child)
                    && boat.toLocal(child.position()).distanceTo(original[0]) < .1
                    && parent.position().distanceTo(boat.toWorld(boat.ownerSeat(parent))) < .02,"both riders finish actual return paths"))
            .thenExecute(() -> {
                helper.assertTrue(childCarried[0] && parentWalked[0] && swallowed[0] && rearContact[0] && brimReached[0],"every visible care phase occurred on the server");
                helper.assertTrue(care.meals() == 1 && boat.cargo().count() == 0 && care.serving().isEmpty(),"one serving is consumed once across the full contract");
                helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class,new AABB(x,64,0,x+16,75,16),i->wasteFrom(i,child)).size() == 1,"first toilet creates exactly one physical child waste item");
                started[0]=false; nutrition(child,85);
                helper.assertTrue(boat.prepareCareSeat(child,parent),"a second real assisted trip can start after returning");
                helper.assertTrue(boat.transportingInHat(parent) && boat.hatCarrier(child)==null,"the parent first approaches instead of claiming a child remotely");
                var approaching=boat.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                boat.load(approaching);
                helper.assertTrue(boat.transportingInHat(parent) && boat.hatCarrier(child)==null,"approach reload preserves the uncollected child and rebuilds the pickup route");
            }).thenWaitUntil(()->helper.assertTrue(boat.hatCarrier(child)!=null && boat.walkingInHat(parent),"reload happens during actual owned transport"))
            .thenExecute(()->{
                Vec3 before=child.position();
                var saved=boat.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                boat.load(saved);
                helper.assertTrue(parent.getUUID().equals(boat.hatCarrier(child)) && child.position().distanceTo(before)<.0001,
                        "reload retains the same physical child and holder without a teleport");
            }).thenWaitUntil(()->helper.assertTrue(!boat.transportingInHat(child) && !boat.changingSeat(child)
                    && boat.toLocal(child.position()).distanceTo(original[0])<.1
                    && parent.position().distanceTo(boat.toWorld(boat.ownerSeat(parent)))<.02,"interrupted loaded transport safely returns both riders"))
            .thenExecute(()->helper.assertTrue(care.meals()==1 && care.serving().isEmpty() && boat.cargo().count()==0,
                    "transport reload grants no care reward and creates no serving"))
            .thenSucceed();
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=80,batch="aquatic_family")
    public static void juvenileSailingGateAndBrim(GameTestHelper helper) {
        var child=ModEntities.MARISA.get().create(helper.getLevel());
        child.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        var growth=child.family().save();growth.putBoolean("Initialized",true);growth.putString("Stage","JUVENILE");growth.putFloat("Scale",io.github.longshou.mcyukkuri.subspecies.FirstVoyage.MIN_SCALE-.01f);child.family().load(growth);
        var voyage=child.aquaticLife().firstVoyage();
        helper.assertTrue(!voyage.readySize()&&!voyage.mayLaunch(),"small juveniles cannot launch their own hats");
        growth.putFloat("Scale",io.github.longshou.mcyukkuri.subspecies.FirstVoyage.MIN_SCALE);child.family().load(growth);
        helper.assertTrue(voyage.readySize()&&!voyage.mayLaunch(),"the early-juvenile size gate still requires the first supervised voyage");
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
        var departureTrace=new java.util.ArrayList<Map<String,Object>>(); int[] departureTraceStage={0};
        YukkuriEntity[] traceFather={null},traceChild={null}; HatBoatEntity[] traceHome={null};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            level.getEntitiesOfClass(Entity.class,new AABB(x,60,0,x+16,76,16),e->e instanceof YukkuriEntity||e instanceof HatBoatEntity||e instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);level.setDayTime(day);GameTestChunks.setForced(level,chunk,0,wasForced);
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) {
                if(traceFather[0]!=null&&traceChild[0]!=null&&traceHome[0]!=null) {
                    var trace=departureDebug(level,traceHome[0],traceFather[0],traceChild[0]);
                    trace.put("event","test_failed"); departureTrace.add(trace);
                    P0Control.write("aquatic-departure-trace",departureTrace);
                }
                cleanup.run();
            }
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
        var home=HatBoatEntity.createFor(father,new BlockPos(x+5,65,7));helper.assertTrue(home!=null,"father uses his actual hat");traceFather[0]=father;traceHome[0]=home;
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
            var kin=y.family().save();kin.putBoolean("Initialized",true);kin.putString("Stage","JUVENILE");kin.putFloat("Scale",io.github.longshou.mcyukkuri.subspecies.FirstVoyage.MIN_SCALE);
            kin.putUUID("Father",father.getUUID());y.family().load(kin);y.moveTo(home.getX(),66,home.getZ(),0,0);level.addFreshEntity(y);nutrition(y,85);
            // Capture the attempted child before boarding: an early failure used to leave traceChild
            // null, so the failure listener could not report which real envelope exhausted the layout.
            traceChild[0]=y;
            var boarding=voyageBoardingDebug(level,home,father,y,i+1);
            boolean boarded=home.boardYoung(y);
            boarding.put("boarded",boarded);boarding.put("youngAboardAfter",home.youngAboard());
            departureTrace.add(boarding);P0Control.write("aquatic-departure-trace",departureTrace);
            System.out.println("MCYUKKURI_P0 voyage boarding sibling="+(i+1)+", boarded="+boarded
                    +", shape="+boarding.get("bodySpace")+", radius="+boarding.get("radius")
                    +", room="+boarding.get("hasRoom")+", inside="+boarding.get("inside")
                    +", brim="+boarding.get("brim")+", occupied="+boarding.get("taken"));
            helper.assertTrue(boarded,"each of five siblings can board; sibling="+(i+1)
                    +", growth="+y.growthScale()+", width="+y.getBbWidth()+", height="+y.getBbHeight()
                    +", room="+boarding.get("hasRoom")+", unsteady="+home.unsteadyMotion()
                    +", inside="+boarding.get("inside")+", brim="+boarding.get("brim")
                    +", body="+boarding.get("bodySpace")+", aboard="+home.youngAboard());children.add(y);
        }
        // The current early-juvenile lesson starts at 0.20. A larger sibling is not an invented
        // sixth slot: rejecting its real body must leave all five passengers and its own hat intact.
        var oversized=ModEntities.MARISA.get().create(level);
        oversized.subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA,SubspeciesLife.Congenital.NORMAL);
        var largeKin=oversized.family().save();largeKin.putBoolean("Initialized",true);largeKin.putString("Stage","JUVENILE");
        largeKin.putFloat("Scale",.90f);largeKin.putUUID("Father",father.getUUID());oversized.family().load(largeKin);
        helper.assertTrue(!home.boardYoung(oversized)&&!oversized.isPassenger()&&oversized.life().hasAccessory()
                &&home.youngAboard()==5&&children.stream().allMatch(y->y.getVehicle()==home),
                "an oversized extra child is refused without taking its hat or displacing any of five siblings");
        var child=children.getFirst();traceChild[0]=child;child.aquaticLife().giveStick(new ItemStack(Items.STICK),false);
        helper.onEachTick(()->{
            if(cleaned[0]||!father.isAlive()||father.isRemoved())return;
            if(child.aquaticLife().firstVoyage().active())child.aquaticLife().firstVoyage().tick();
            if(father.aquaticLife().firstVoyage().active())father.aquaticLife().firstVoyage().tick();
            if(departureTraceStage[0]>0&&level.getGameTime()%40==0) {
                var trace=departureDebug(level,home,father,child); trace.put("event","departure_wait"); departureTrace.add(trace);
                if(departureTrace.size()>20)departureTrace.remove(0);
                P0Control.write("aquatic-departure-trace",departureTrace);
            }
        });
        // Boarding reserves the brim synchronously, before any newly spawned entity has ticked.
        // Wait for real rider placement and the normal server clock before testing shore recovery.
        int recoveryStartTick=father.tickCount;
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(home.onBrim(child)
                &&Math.abs(home.toLocal(child.position()).y-HatVessel.FREEBOARD)<.01,
                "the pupil's real supported brim route completes before its lesson"))
        .thenWaitUntil(()->helper.assertTrue(father.tickCount>recoveryStartTick&&shore.landTicks()<600
                &&father.getVehicle()==home&&home.protects(father)&&home.youngAboard()==5
                &&children.stream().allMatch(y->y.getVehicle()==home&&home.protects(y)),
                "real dry hat passengers recover land strain on their server clock while all five siblings remain protected"))
        .thenExecute(()->{
            helper.assertTrue(shore.landTicks()<600,"real dry hat passengers recover land strain");
            helper.assertTrue(home.onBrim(child)&&child.aquaticLife().firstVoyage().tryStart(),"lesson starts on the father's brim");
            helper.assertTrue(home.voyageBerth(child)!=null&&home.hasRoomFor(child),"the measured family layout retains the pupil's own berth");
            departureTraceStage[0]=1;
        }).thenWaitUntil(()->{
            // Launching the hat is followed by a supported edge approach and the actual boarding
            // controller. A fixed 100-tick delay can end while that real SeatMove is still queued.
            var own=HatBoatEntity.owned(child);
            helper.assertTrue(own!=null&&own!=home&&child.getVehicle()==own&&own.protects(child),
                    "pupil boards its own real hat from the father's boat");
        }).thenExecute(()->{
            var own=HatBoatEntity.owned(child);
            helper.assertTrue(own!=null&&own!=home&&child.getVehicle()==own,"pupil boards its own real hat from the father's boat");
            helper.assertTrue(home.youngAboard()==4&&home.voyageBerth(child)!=null&&home.hasRoomFor(child),"away pupil keeps its reserved return berth alongside four real siblings");
            helper.assertTrue(father.getVehicle()==home&&children.subList(1,5).stream().allMatch(y->y.getVehicle()==home),"father and four siblings remain afloat");
            helper.assertTrue(!child.life().hasAccessory()&&own.cargo().paddle()&&!child.aquaticLife().hasStick(),"hat and paddle have one physical owner");
            child.aquaticLife().firstVoyage().load(child.aquaticLife().firstVoyage().save());
        }).thenWaitUntil(()->helper.assertTrue(child.getVehicle()==home&&HatBoatEntity.owned(child)==null&&home.youngAboard()==5,
                "interrupted pupil returns to the reserved berth after physical reboarding and hat retrieval"))
        .thenExecute(()->{
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
            // This point is more than 300 real world ticks after boarding.  A dry, protected hat
            // must preserve every rider's actual breathing and health; FirstVoyage correctly aborts
            // when any sibling is unhealthy, so do not let drowning masquerade as a berth failure.
            helper.assertTrue(home.getPassengers().stream().filter(YukkuriEntity.class::isInstance).map(YukkuriEntity.class::cast)
                    .allMatch(y -> home.protects(y) && y.getHealth()==y.getMaxHealth() && y.getAirSupply()==y.getMaxAirSupply()),
                    "all long-riding protected family members stay at full health and air");
            double r=HatVessel.radius(child.getBbWidth())/home.unit();
            helper.assertTrue(child.childhood().canLiveIndependently(),"0.70 late juvenile remains eligible before adult stage");
            helper.assertTrue(HatVessel.brimPlace(r,java.util.List.of(),true)==null,"0.70 subadult likewise cannot use the narrow full-body brim strip");
            helper.assertTrue(child.aquaticLife().firstVoyage().tryStart(),"0.70 subadult can reserve the dedicated front-edge departure seat through its real departure path");
            departureTraceStage[0]=1;
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
        state.put("hatYaw",hat.getYRot());state.put("parentYaw",parent.getYRot());state.put("childYaw",sibling.getYRot());state.put("childHeadYaw",sibling.getYHeadRot());
        state.put("hatSave",hat.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString());
        state.put("carePlan",hat.carePlanDebug());
        state.put("parentTransport",hat.transportingInHat(parent));state.put("childTransport",hat.transportingInHat(sibling));
        state.put("childCarrier",String.valueOf(hat.hatCarrier(sibling)));state.put("carryAnchor",String.valueOf(hat.hatCarryAnchor(sibling)));
        state.put("parentLocal",hat.toLocal(parent.position()).toString());state.put("childLocal",hat.toLocal(sibling.position()).toString());
        state.put("parentProtects",hat.protects(parent)); state.put("siblingProtects",hat.protects(sibling));
        state.put("sameHat",parent.getVehicle()==hat&&sibling.getVehicle()==hat); state.put("lineOfSight",parent.hasLineOfSight(sibling));
        state.put("braidCanServe",HatVessel.braidCanServe(parent,sibling)); state.put("siblingChangingSeat",hat.changingSeat(sibling)); state.put("siblingCareSeatReady",hat.careSeatReady(sibling));
        state.put("parentAlive",parent.isAlive()); state.put("parentRemoved",parent.isRemoved()); state.put("parentHealth",parent.getHealth()); state.put("parentPanicking",parent.isPanicking()); state.put("parentCommand",parent.isCommandPlaying());
        state.put("parentNutrition",parent.life().nutrition()); state.put("parentEnergy",parent.life().energy()); state.put("parentMealTicks",parent.life().mealTicks()); state.put("parentSleeping",parent.life().isSleeping());
        state.put("siblingAlive",sibling.isAlive()); state.put("siblingRemoved",sibling.isRemoved()); state.put("siblingHealth",sibling.getHealth()); state.put("siblingPanicking",sibling.isPanicking()); state.put("siblingCommand",sibling.isCommandPlaying()); state.put("siblingNoAi",sibling.isNoAi());
        state.put("siblingNutrition",sibling.life().nutrition()); state.put("siblingEnergy",sibling.life().energy()); state.put("siblingMealTicks",sibling.life().mealTicks()); state.put("siblingSleeping",sibling.life().isSleeping());
        state.put("siblingAwaitingLanding",sibling.family().awaitingLanding()); state.put("siblingOperations",sibling.operations().active()); state.put("siblingCareFood",sibling.life().canReceiveCareFood()); state.put("parentCaresForSibling",parent.family().caresFor(sibling));
        state.put("parentEntity",parent.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString()); state.put("siblingEntity",sibling.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString());
        return state;
    }
    /** Read-only geometry probes for the five real siblings; no reservation or size is changed. */
    @SuppressWarnings("unchecked")
    private static Map<String,Object> voyageBoardingDebug(net.minecraft.server.level.ServerLevel level,HatBoatEntity home,YukkuriEntity father,YukkuriEntity child,int index) {
        var state=departureDebug(level,home,father,child);
        state.put("event","boarding_attempt");state.put("siblingIndex",index);
        state.put("hasRoom",home.hasRoomFor(child));state.put("hasSafeRoom",home.hasSafeRoomFor(child));
        state.put("young",home.young(child));state.put("unsteady",home.unsteadyMotion());
        state.put("hullScale",home.hullScale());state.put("unit",home.unit());
        state.put("growthScale",child.growthScale());state.put("canJump",child.childhood().canJump());
        state.put("childhood",child.childhood().debug().toString());state.put("height",child.getBbHeight());
        double radius=HatVessel.radius(child.getBbWidth())/home.unit();state.put("radius",radius);
        try {
            var shapeMethod=HatVessel.class.getDeclaredMethod("bodySpace",YukkuriEntity.class,double.class,double.class);shapeMethod.setAccessible(true);
            var shape=(HatVessel.BodySpace)shapeMethod.invoke(null,child,(double)home.hullScale(),home.unit());
            var takenMethod=HatBoatEntity.class.getDeclaredMethod("layoutTaken",java.util.UUID.class);takenMethod.setAccessible(true);
            var taken=(java.util.List<double[]>)takenMethod.invoke(home,child.getUUID());
            var regionMethod=HatBoatEntity.class.getDeclaredMethod("region");regionMethod.setAccessible(true);
            var region=(String)regionMethod.invoke(home);
            state.put("bodySpace",shape.toString());state.put("region",region);
            state.put("taken",taken.stream().map(java.util.Arrays::toString).toList());
            state.put("inside",String.valueOf(HatVessel.place(home.mode(),radius,region,null,taken,null,Double.NaN,shape)));
            state.put("brim",String.valueOf(HatVessel.brimPlace(radius,taken)));
            state.put("launchBrim",String.valueOf(HatVessel.brimPlace(radius,taken,true)));
            state.put("homeEntity",home.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString());
        } catch(ReflectiveOperationException error) { throw new IllegalStateException("Cannot observe actual voyage boarding geometry",error); }
        return state;
    }
    /** Failure-only trace for the late-juvenile departure; every field is observational. */
    private static Map<String,Object> departureDebug(net.minecraft.server.level.ServerLevel level,HatBoatEntity home,YukkuriEntity father,YukkuriEntity child) {
        var state=new java.util.LinkedHashMap<String,Object>(); var voyage=child.aquaticLife().firstVoyage();
        state.put("gameTime",level.getGameTime()); state.put("dayTime",level.getDayTime()); state.put("night",level.isNight());
        state.put("voyageStatus",voyage.status()); state.put("voyageActivity",voyage.activity()); state.put("voyageActive",voyage.active()); state.put("voyageIndependent",voyage.independent()); state.put("voyageSave",voyage.save().toString());
        state.put("childPosition",child.position().toString()); state.put("childScale",child.getScale()); state.put("childFamilyScale",child.family().scale()); state.put("childWidth",child.getBbWidth()); state.put("childVehicle",child.getVehicle()==null?"none":child.getVehicle().getClass().getSimpleName()+":"+child.getVehicle().getUUID());
        state.put("childAlive",child.isAlive()); state.put("childRemoved",child.isRemoved()); state.put("childHealth",child.getHealth()); state.put("childPanicking",child.isPanicking()); state.put("childCommand",child.isCommandPlaying()); state.put("childAwaitingLanding",child.family().awaitingLanding());
        state.put("fatherPosition",father.position().toString()); state.put("fatherVehicle",father.getVehicle()==null?"none":father.getVehicle().getClass().getSimpleName()+":"+father.getVehicle().getUUID()); state.put("fatherVoyage",father.aquaticLife().firstVoyage().save().toString());
        state.put("homePosition",home.position().toString()); state.put("homeAfloat",home.isAfloat()); state.put("homeProtectsChild",home.protects(child)); state.put("homeChangingSeat",home.changingSeat(child)); state.put("homeOnVoyageBerth",home.onVoyageBerth(child)); state.put("homeVoyageBerth",String.valueOf(home.voyageBerth(child))); state.put("homeIncoming",home.hasIncoming()); state.put("homeYoungAboard",home.youngAboard());
        state.put("childHomeLocal",home.toLocal(child.position()).toString());
        state.put("boardingPlan",child.family().hatBoardingDebug());
        state.put("homeEntity",home.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString());
        var own=HatBoatEntity.owned(child);
        state.put("ownHat",own==null?"none":own.getUUID().toString());
        if(own!=null){
            state.put("ownPosition",own.position().toString());state.put("ownIncoming",own.hasIncoming());state.put("ownProtectsChild",own.protects(child));
            state.put("ownEntity",own.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString());
        }
        state.put("childEntity",child.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString()); state.put("fatherEntity",father.saveWithoutId(new net.minecraft.nbt.CompoundTag()).toString());
        return state;
    }
    private AquaticFamilyGameTests() {}
}
