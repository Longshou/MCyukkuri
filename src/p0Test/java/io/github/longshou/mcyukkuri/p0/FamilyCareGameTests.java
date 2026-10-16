package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only: a carried portion survives reload and a live parent walks that same kind of portion to its child. */
@PrefixGameTestTemplate(false)
public final class FamilyCareGameTests {
    private static final int X=2432;

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=800,batch="childhood")
    public static void realFoodHandoff(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        long time=level.getDayTime();
        boolean daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean grief=level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        boolean rain=level.isRaining(),thunder=level.isThundering();
        float rainLevel=level.getRainLevel(1),thunderLevel=level.getThunderLevel(1);
        int hunger=YukkuriConfig.HUNGER_INTERVAL.get();
        var forced=new HashSet<>(level.getForcedChunks());
        var terrain=new HashMap<BlockPos,BlockState>();
        var actors=new ArrayList<YukkuriEntity>();
        var items=new ArrayList<ItemEntity>();
        var handoffTrace=new ArrayList<java.util.Map<String,Object>>();
        ShelterPlacement.Placed[] childHome={null};
        YukkuriEntity[] parent={null},child={null}; ItemEntity[] source={null}; Vec3[] parentStart={null};
        boolean[] picked={false},mashed={false},delivered={false},moved={false},contacted={false},swallowed={false},duplicated={false},cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            if(childHome[0]!=null)childHome[0].rollback(level);
            actors.forEach(Entity::discard);items.forEach(Entity::discard);
            var ids=actors.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            FamilyData.get(level).rollbackEncounter(ids);YukkuriWorldData.get(level).rollbackEncounter(ids);
            level.getEntitiesOfClass(ItemEntity.class,new AABB(X,60,0,X+16,80,16)).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);
            level.setDayTime(time);level.updateSkyBrightness();
            level.setWeatherParameters(12000,12000,rain,thunder);level.setRainLevel(rainLevel);level.setThunderLevel(thunderLevel);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief,level.getServer());
            YukkuriConfig.HUNGER_INTERVAL.set(hunger);
            int chunk=X>>4;GameTestChunks.setForced(level,chunk,0,forced.contains(net.minecraft.world.level.ChunkPos.asLong(chunk,0)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) {
                if(parent[0]!=null&&child[0]!=null) {
                    var last=handoffDebug(level,parent[0],child[0],childHome[0]);last.put("event","test_failed");last.put("gameTime",level.getGameTime());
                    handoffTrace.add(last);P0Control.write("family-care-handoff-trace",handoffTrace);
                }
                cleanup.run();
            }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        int chunk=X>>4;GameTestChunks.setForced(level,chunk,0,true);
        helper.onEachTick(()->{
            if(cleaned[0]||parent[0]==null||child[0]==null||source[0]==null)return;
            int portions=(source[0].isAlive()?source[0].getItem().getCount():0)+parent[0].family().carrying().getCount()+child[0].life().mouth().getCount();
            if(portions>1)duplicated[0]=true;
            picked[0]|=!source[0].isAlive()&&parent[0].family().carrying().is(ModItems.YUKKURI_STEM.get());
            mashed[0]|=io.github.longshou.mcyukkuri.life.InfantFood.isMash(parent[0].family().carrying());
            delivered[0]|=child[0].life().mouth().is(ModItems.YUKKURI_STEM.get())&&io.github.longshou.mcyukkuri.life.InfantFood.isMash(child[0].life().mouth());
            moved[0]|=parentStart[0]!=null&&parent[0].position().distanceToSqr(parentStart[0])>4;
            contacted[0]|=parent[0].family().careContactProgress()>0&&FamilyFeedingContact.mouthsMeet(parent[0],child[0]);
            swallowed[0]|=child[0].life().nutrition()>=40&&child[0].life().mouth().isEmpty();
            if(mashed[0]&&!delivered[0]&&level.getGameTime()%40==0) {
                var sample=handoffDebug(level,parent[0],child[0],childHome[0]);sample.put("gameTime",level.getGameTime());sample.put("event","offer_wait");
                handoffTrace.add(sample);if(handoffTrace.size()>16)handoffTrace.remove(0);P0Control.write("family-care-handoff-trace",handoffTrace);
            }
        });
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,chunk,chunk,0,0)).thenExecute(()->{
            for(int x=X;x<X+16;x++)for(int z=0;z<16;z++)for(int y=64;y<70;y++) {
                var pos=new BlockPos(x,y,z);terrain.put(pos,level.getBlockState(pos));
                level.setBlockAndUpdate(pos,y==64?Blocks.SMOOTH_STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            level.setDayTime(time-time%24000+1000);level.updateSkyBrightness();
            level.setWeatherParameters(12000,0,false,false);level.setRainLevel(0);level.setThunderLevel(0);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());YukkuriConfig.HUNGER_INTERVAL.set(24000);

            // This immediate path remains the precise one-portion/reload regression: it also proves lower nutrition wins.
            var directParent=spawn(level,X+4.5,2.5,90);var hungriest=spawn(level,X+4.9,2.5,20);var sibling=spawn(level,X+5.8,2.5,55);
            actors.add(directParent);actors.add(hungriest);actors.add(sibling);
            dependent(hungriest,directParent);sibling.family().generatedChild(directParent,null);
            FamilyData.get(level).register(directParent);FamilyData.get(level).register(hungriest);FamilyData.get(level).register(sibling);
            // Use the exact legal mouth separation: adjacent collision centres may overlap, but this
            // is a physical mouth-to-mouth handoff rather than the former generic nearby check.
            directParent.moveTo(FamilyFeedingContact.approachSpot(directParent,hungriest));directParent.setOnGround(true);
            var directStem=new ItemEntity(level,directParent.getX(),directParent.getY(),directParent.getZ(),new ItemStack(ModItems.YUKKURI_STEM.get(),2));directStem.setNoPickUpDelay();
            helper.assertTrue(level.addFreshEntity(directStem),"physical stem source inserts");items.add(directStem);
            helper.assertTrue(hungriest.family().needsMash()&&!hungriest.life().canEat()
                    &&!hungriest.life().beginMeal(new ItemStack(ModItems.YUKKURI_STEM.get()),null,false),"newborn refuses a complete stem as an independent meal");
            // Family load restores the normal forty-tick decision cooldown before a new task can start.
            for(int i=0;i<40;i++)directParent.family().tick();
            helper.assertTrue(directParent.family().canUse(),"safe parent begins care for a reachable hungry child");directParent.family().tickGoal();
            helper.assertTrue(directParent.family().carrying().is(ModItems.YUKKURI_STEM.get())&&directParent.family().carrying().getCount()==1&&directStem.getItem().getCount()==1,
                    "caregiver picks exactly one real stem portion");
            directParent.family().load(directParent.family().save());for(int i=0;i<40;i++)directParent.family().tick();
            helper.assertTrue(directParent.family().canUse(),"reloaded caregiver resumes only when a hungry child remains");directParent.family().tickGoal();
            for(int i=0;i<39;i++)directParent.family().tickGoal();
            helper.assertTrue(io.github.longshou.mcyukkuri.life.InfantFood.isMash(directParent.family().carrying())&&directParent.family().careChewTicks()==40
                    &&directParent.life().nutrition()==90,"caregiver turns the original stem into mash without eating it");
            directParent.family().load(directParent.family().save());
            helper.assertTrue(io.github.longshou.mcyukkuri.life.InfantFood.isMash(directParent.family().carrying())&&directParent.family().careChewTicks()==40,
                    "reload preserves the prepared serving and completed chew progress");
            for(int i=0;i<40;i++)directParent.family().tick();
            helper.assertTrue(directParent.family().canUse(),"reloaded prepared serving starts a fresh, close delivery task");
            directParent.moveTo(hungriest.position().add(2,0,0));directParent.setOnGround(true);
            directParent.family().tickGoal();for(int i=0;i<2;i++)directParent.family().tickGoal();
            helper.assertTrue(directParent.family().careContactProgress()==0&&directParent.family().carrying().is(ModItems.YUKKURI_STEM.get()),
                    "a prepared serving cannot transfer while the caregiver is still away from the child's mouth");
            directParent.moveTo(FamilyFeedingContact.approachSpot(directParent,hungriest));directParent.setOnGround(true);
            FamilyFeedingContact.faceEachOther(directParent,hungriest);
            float away=directParent.getYRot()+180;directParent.setYRot(away);directParent.yBodyRot=away;directParent.yBodyRotO=away;directParent.setYHeadRot(away);
            helper.assertTrue(!FamilyFeedingContact.mouthsMeet(directParent,hungriest),"turned-away mouths are not a valid feeding contact");
            for(int i=0;i<40&&directParent.family().careContactProgress()==0;i++)directParent.family().tickGoal();
            if(directParent.family().careContactProgress()==0){
                var directDebug=handoffDebug(level,directParent,hungriest,null);
                directDebug.put("approach",directParent.careApproach().debug().toString());
                P0Control.write("family-care-direct-contact",directDebug);
            }
            helper.assertTrue(directParent.family().careContactProgress()>0,"caregiver first turns into an actual contact pose");
            for(int i=0;i<19;i++)directParent.family().tickGoal();
            helper.assertTrue(hungriest.life().mealTicks()==1&&io.github.longshou.mcyukkuri.life.InfantFood.isMash(hungriest.life().mouth())
                    &&sibling.life().mealTicks()==0&&directParent.family().carrying().isEmpty()&&directStem.getItem().getCount()==1,
                    "twenty close delivery ticks hand the saved mash to the lower-nutrition child without duplicating its source");
            directStem.discard();
            helper.assertTrue(directParent.family().activity().equals("care_finish"),
                    "the successful handoff enters its real closing action before a second care task may start");
            for(int i=0;i<80&&directParent.family().activity().equals("care_finish");i++)directParent.family().tickGoal();
            helper.assertTrue(!directParent.family().active()&&!hungriest.careApproach().waiting()
                    &&directParent.family().carrying().isEmpty()&&hungriest.life().mealTicks()==1,
                    "the old handoff closes and releases both actors without consuming or duplicating the child's held meal");
            var stocked=ShelterPlacement.place(level,new ShelterPlacement.Plan(new BlockPos(X+12,65,8),ShelterBlock.Kind.CARDBOARD,Direction.SOUTH));
            helper.assertTrue(stocked!=null,"a real resident store is available for the care food selection check");childHome[0]=stocked;
            directParent.moveTo(Vec3.atBottomCenterOf(stocked.shelter().entrance()));directParent.setOnGround(true);
            helper.assertTrue(directParent.habitat().choose(stocked.shelter()),"caregiver claims the store with an actual reachable bed");
            directParent.moveTo(Vec3.atBottomCenterOf(stocked.shelter().bed(directParent.getUUID())));directParent.setOnGround(true);
            sibling.moveTo(directParent.position().add(.1,0,0));sibling.setOnGround(true);
            var rejected=io.github.longshou.mcyukkuri.body.FillingResource.portion(hungriest,"leak");sibling.body().learnSource(rejected);
            helper.assertTrue(directParent.life().acceptsFood(rejected)&&!sibling.life().acceptsFood(rejected),"child alone knows and rejects the first stored filling");
            helper.assertTrue(stocked.shelter().returnFood(rejected).isEmpty()
                    &&stocked.shelter().returnFood(new ItemStack(ModItems.YUKKURI_STEM.get())).isEmpty(),"both physical portions enter separate stock slots");
            for(int i=0;i<40;i++)directParent.family().tick();
            helper.assertTrue(directParent.family().canUse(),"caregiver finds a stored portion accepted by its child");
            helper.assertTrue(directParent.family().careTargetId()==sibling.getId(),
                    "a new care task selects the hungry sibling while the first child still holds its original meal");
            directParent.family().tickGoal();
            helper.assertTrue(directParent.family().carrying().is(ModItems.YUKKURI_STEM.get())
                    &&stocked.shelter().foodFor(directParent).is(ModItems.FILLING.get())&&stocked.shelter().food()==1,
                    "care takes the later stem slot, leaving the child's rejected filling unchanged");
            var airborne=sibling.family().save();airborne.putBoolean("AwaitingLanding",true);sibling.family().load(airborne);
            helper.assertTrue(!sibling.life().canEat(),"an awaiting-landing newborn cannot begin a meal");
            stocked.rollback(level);childHome[0]=null;
            actors.forEach(Entity::discard);items.forEach(Entity::discard);

            var planned=ShelterPlacement.place(level,new ShelterPlacement.Plan(new BlockPos(X+12,65,8),ShelterBlock.Kind.CARDBOARD,Direction.SOUTH));
            helper.assertTrue(planned!=null,"child's quiet cardboard shelter can be placed");childHome[0]=planned;
            var entrance=childHome[0].shelter().entrance();
            child[0]=spawn(level,entrance.getX()+.5,entrance.getZ()+.5,20);actors.add(child[0]);
            parent[0]=spawn(level,X+2.5,8.5,90);actors.add(parent[0]);
            dependent(child[0],parent[0]);FamilyData.get(level).register(parent[0]);FamilyData.get(level).register(child[0]);
            helper.assertTrue(child[0].family().needsMash()&&!child[0].life().canEat(),"real AI target is a dependent newborn, not an autonomous juvenile fixture");
            helper.assertTrue(child[0].habitat().choose(childHome[0].shelter()),"hungry child claims its real nearby bed and stays there");
            child[0].moveTo(Vec3.atBottomCenterOf(childHome[0].shelter().bed(child[0].getUUID())));child[0].setOnGround(true);
            BlockPos sourcePos=childHome[0].shelter().bed(child[0].getUUID()).west(10);parent[0].moveTo(Vec3.atBottomCenterOf(sourcePos));parent[0].setOnGround(true);parentStart[0]=parent[0].position();
            source[0]=new ItemEntity(level,parent[0].getX(),parent[0].getY(),parent[0].getZ(),new ItemStack(ModItems.YUKKURI_STEM.get()));source[0].setNoPickUpDelay();
            helper.assertTrue(level.addFreshEntity(source[0]),"one physical stem is available only at the parent");items.add(source[0]);
        }).thenWaitUntil(()->helper.assertTrue(picked[0],"Full AI caregiver must pick the only stem before leaving its source"))
        .thenWaitUntil(()->helper.assertTrue(mashed[0],"Full AI caregiver must chew the carried stem into mash before approaching the child"))
        .thenWaitUntil(()->helper.assertTrue(delivered[0],"Full AI caregiver walks the prepared serving to the child and begins the child's real meal; "+handoffDebug(level,parent[0],child[0],childHome[0])))
        .thenWaitUntil(()->helper.assertTrue(swallowed[0],"The child reaches the stem's ordinary twenty-nutrition swallow result"))
        .thenExecute(()->helper.assertTrue(moved[0]&&contacted[0]&&!duplicated[0]&&!source[0].isAlive(),
                "Parent walked into actual mouth contact; the one source portion was neither left behind nor duplicated"))
        .thenSucceed();
    }

    private static YukkuriEntity spawn(ServerLevel level,double x,double z,float nutrition) {
        var entity=ModEntities.REIMU.get().create(level);if(entity==null)throw new IllegalStateException("Missing Reimu type");
        entity.moveTo(x,65,z,0,0);entity.setOnGround(true);entity.setPersistenceRequired();
        var family=entity.family().save();family.putBoolean("Initialized",true);family.putString("Stage","ADULT");entity.family().load(family);
        var life=entity.life().save();life.putFloat("Nutrition",nutrition);life.putFloat("Energy",100);life.putFloat("Hygiene",100);life.putFloat("Bladder",0);life.putInt("RestWait",1200);life.putInt("SocialWait",1200);entity.life().load(life);
        if(!level.addFreshEntity(entity))throw new IllegalStateException("Family-care fixture spawn rejected");return entity;
    }
    private static void dependent(YukkuriEntity child,YukkuriEntity parent) {
        child.family().generatedChild(parent,null);
        var data=child.family().save();data.putString("Stage","BABY");data.putLong("Age",0);data.putLong("Nourishment",0);data.putFloat("Scale",.14f);
        data.putBoolean("AwaitingLanding",false);child.family().load(data);
    }
    private static java.util.Map<String,Object> handoffDebug(ServerLevel level,YukkuriEntity parent,YukkuriEntity child,ShelterPlacement.Placed home) {
        var state=new java.util.LinkedHashMap<String,Object>();
        if(parent==null||child==null) { state.put("fixture","parent_or_child_missing");return state; }
        var toward=child.position().subtract(parent.position()).multiply(1,0,1);double horizontal=toward.length();
        var unit=horizontal>1.0e-6?toward.scale(1/horizontal):Vec3.ZERO;
        var approach=FamilyFeedingContact.approachSpot(parent,child);
        state.put("parent",handoffEntity(level,parent));state.put("child",handoffEntity(level,child));
        state.put("horizontalDistance",horizontal);state.put("mouthMinimum",FamilyFeedingContact.minimumMouthDistance(parent,child));state.put("mouthMaximum",FamilyFeedingContact.mouthDistance(parent,child));
        state.put("mouthHeightDelta",Math.abs((parent.getY()+parent.getBbHeight()*.5)-(child.getY()+child.getBbHeight()*.5)));
        state.put("parentHasLineOfSight",parent.hasLineOfSight(child));state.put("childHasLineOfSight",child.hasLineOfSight(parent));state.put("mouthsMeet",FamilyFeedingContact.mouthsMeet(parent,child));
        state.put("parentFacingDot",horizontal>1.0e-6?handoffForward(parent).dot(unit):0.0);state.put("childFacingDot",horizontal>1.0e-6?handoffForward(child).dot(unit.scale(-1)):0.0);
        state.put("approachSpot",approach.toString());state.put("parentDistanceToApproach",parent.position().distanceTo(approach));
        state.put("approachCollisionFree",level.noCollision(parent,parent.getBoundingBox().move(approach.subtract(parent.position()))));
        if(home!=null) {
            var entrance=home.shelter().entrance();var bed=home.shelter().bed(child.getUUID());
            state.put("childHomeEntrance",entrance.toShortString());state.put("childHomeBed",bed==null?"none":bed.toShortString());
            state.put("entranceBlock",level.getBlockState(entrance).toString());state.put("entranceHasCollision",!level.getBlockState(entrance).getCollisionShape(level,entrance).isEmpty());
            state.put("homeSound",home.shelter().sound());state.put("homeInterior",home.shelter().interior().toString());
        }
        return state;
    }
    private static java.util.Map<String,Object> handoffEntity(ServerLevel level,YukkuriEntity entity) {
        var state=new java.util.LinkedHashMap<String,Object>();
        state.put("position",entity.position().toString());state.put("width",entity.getBbWidth());state.put("height",entity.getBbHeight());
        state.put("yaw",entity.getYRot());state.put("headYaw",entity.getYHeadRot());state.put("velocity",entity.getDeltaMovement().toString());
        state.put("onGround",entity.onGround());state.put("horizontalCollision",entity.horizontalCollision);state.put("verticalCollision",entity.verticalCollision);
        state.put("collisionFree",level.noCollision(entity,entity.getBoundingBox()));state.put("behavior",entity.getBehaviorId());state.put("lifeActivity",entity.life().activity());
        state.put("mealTicks",entity.life().mealTicks());state.put("nutrition",entity.life().nutrition());state.put("mouth",entity.life().mouth().toString());
        state.put("familyActive",entity.family().active());state.put("familyTask",entity.family().activity());state.put("careTargetId",entity.family().careTargetId());
        state.put("careProgress",entity.family().careContactProgress());state.put("careChewTicks",entity.family().careChewTicks());state.put("carrying",entity.family().carrying().toString());
        return state;
    }
    private static Vec3 handoffForward(YukkuriEntity entity) { return Vec3.directionFromRotation(0,entity.getYHeadRot()).multiply(1,0,1).normalize(); }
    private FamilyCareGameTests() {}
}
