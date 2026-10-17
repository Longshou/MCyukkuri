package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only checks for newborn care: physical parent contact, one waste, persistence, and actual movement. */
@PrefixGameTestTemplate(false)
public final class ChildhoodLifeGameTests {
    private static final int X = 3584;

    private static void vitals(YukkuriEntity y, float bladder) {
        CompoundTag tag = y.life().save();
        tag.putFloat("Nutrition", 90); tag.putFloat("Energy", 90); tag.putFloat("Hygiene", 90);
        tag.putFloat("Bladder", bladder); tag.putFloat("Wetness", 0); tag.putFloat("Dryness", 0);
        y.life().load(tag);
    }

    private static void adult(YukkuriEntity y) {
        CompoundTag tag = y.family().save();
        tag.putBoolean("Initialized", true); tag.putString("Stage", "ADULT");
        tag.putLong("Age", y.family().maturityAge()); tag.putLong("Nourishment", y.family().maturityAge()); tag.putFloat("Scale", 1);
        y.family().load(tag);
    }

    private static void newbornFamily(YukkuriEntity child, YukkuriEntity parent) {
        CompoundTag tag = child.family().save();
        tag.putBoolean("Initialized", true); tag.putString("Stage", "BABY");
        tag.putLong("Age", 0); tag.putLong("Nourishment", 0); tag.putFloat("Scale", .14f);
        tag.putUUID("Mother", parent.getUUID()); tag.remove("Father"); tag.putBoolean("AwaitingLanding", false);
        child.family().load(tag);
    }

    private static void newborn(YukkuriEntity child, YukkuriEntity parent) {
        newbornFamily(child, parent);
        child.childhood().newborn();
    }

    private static int waste(ServerLevel level, AABB area) {
        return level.getEntitiesOfClass(ItemEntity.class, area, item -> item.getItem().is(ModItems.WASTE.get()))
                .stream().mapToInt(item -> item.getItem().getCount()).sum();
    }

    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 1600, batch = "childhood")
    public static void parentAssistsFirstToiletThenMovement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(X, 65, 8);
        AABB area = new AABB(origin).inflate(12, 5, 12);
        int firstChunk = (origin.getX() - 5) >> 4, lastChunk = (origin.getX() + 5) >> 4;
        Map<Long, Boolean> forced = new HashMap<>();
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++)
            forced.put(net.minecraft.world.level.ChunkPos.asLong(chunk, 0), level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk, 0)));
        Map<BlockPos, BlockState> terrain = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-5, -1, -5), origin.offset(5, -1, 5))) {
            terrain.put(p.immutable(), level.getBlockState(p));
            level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
        }
        boolean[] cleaned = {false};
        YukkuriEntity[] parent = {null}, child = {null};
        String[] phase = {"spawn"};
        var trace = new java.util.ArrayList<Map<String, Object>>();
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            level.getEntitiesOfClass(Entity.class, area, entity -> entity instanceof YukkuriEntity || entity instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);
            for (int chunk = firstChunk; chunk <= lastChunk; chunk++)
                GameTestChunks.setForced(level, chunk, 0, forced.get(net.minecraft.world.level.ChunkPos.asLong(chunk, 0)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) { }
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) {
                if (parent[0] != null && child[0] != null) {
                    trace.add(practiceDebug(level, phase[0], parent[0], child[0]));
                    P0Control.write("childhood-ground-care-trace", trace);
                }
                cleanup.run();
            }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo, net.minecraft.gametest.framework.GameTestInfo newInfo, net.minecraft.gametest.framework.GameTestRunner runner) { }
        });
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++) GameTestChunks.setForced(level, chunk, 0, true);
        Vec3[] parentStart = {Vec3.ZERO};
        AtomicBoolean assistingAtRear = new AtomicBoolean(), observePractice = new AtomicBoolean();
        AtomicBoolean airborne = new AtomicBoolean(), landedAfterAirborne = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (parent[0] == null || child[0] == null || !parent[0].isAlive() || !child[0].isAlive()) return;
            if (level.getGameTime() % 40 == 0) {
                trace.add(practiceDebug(level, phase[0], parent[0], child[0]));
                if (trace.size() > 40) trace.remove(0);
            }
            if (parent[0].childhood().activity().equals("first_toilet_assist") && child[0].getUUID().equals(parent[0].childhood().careTargetId())) {
                Vec3 offset = parent[0].position().subtract(child[0].position()).multiply(1, 0, 1);
                Vec3 rear = child[0].getLookAngle().multiply(-1, 0, -1).normalize();
                boolean moved = parent[0].position().distanceToSqr(parentStart[0]) > .04;
                if (offset.lengthSqr() > 1.0e-6 && offset.lengthSqr() < 1.2 && offset.normalize().dot(rear) > .30 && moved) assistingAtRear.set(true);
            }
            if (observePractice.get()) {
                if (!child[0].onGround() || child[0].getDeltaMovement().y > .03) airborne.set(true);
                if (airborne.get() && child[0].onGround()) landedAfterAirborne.set(true);
            }
        });
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper, firstChunk, lastChunk, 0, 0)).thenExecute(() -> {
            parent[0] = ModEntities.REIMU.get().create(level); child[0] = ModEntities.REIMU.get().create(level);
            parent[0].moveTo(origin.getX() + .5, origin.getY(), origin.getZ() - 2.25, 0, 0);
            child[0].moveTo(origin.getX() + .5, origin.getY(), origin.getZ() + .5, 0, 0);
            child[0].setYRot(0); child[0].setYHeadRot(0); child[0].yBodyRot = 0;
            parent[0].setOnGround(true); child[0].setOnGround(true); adult(parent[0]); newbornFamily(child[0], parent[0]);
            CompoundTag legacy = child[0].childhood().save(); legacy.remove("Version"); child[0].childhood().load(legacy);
            helper.assertTrue(child[0].childhood().canJump() && child[0].childhood().firstToiletDone()
                            && !child[0].childhood().canEatSolid() && !child[0].family().canEatIndependently(),
                    "a Version-less BABY save keeps its old mobility and toilet state but still uses FamilyLife's independent-food gate");
            child[0].childhood().newborn();
            helper.assertTrue(child[0].childhood().isNewborn() && !child[0].childhood().firstToiletDone() && !child[0].childhood().canJump(),
                    "only an actual new birth opts into the current first-toilet and movement contract");
            vitals(parent[0], 0); vitals(child[0], 10); parentStart[0] = parent[0].position();
            helper.assertTrue(level.addFreshEntity(parent[0]) && level.addFreshEntity(child[0]), "actual parent and newborn join the level");
            phase[0] = "settle";
        }).thenWaitUntil(() -> helper.assertTrue(child[0].childhood().landed() && child[0].childhood().needsAssistedFirstToilet(),
                "settled newborn with a real urge asks for parent help before any ordinary toilet"))
        .thenExecute(() -> phase[0] = "rear_approach")
        .thenWaitUntil(() -> helper.assertTrue(assistingAtRear.get(),
                "the parent walks to the child's rear and holds actual close contact before the first stool"))
        .thenExecute(() -> {
            phase[0] = "rear_contact";
            helper.assertTrue(!child[0].life().completeAssistedFirstToilet(parent[0])
                            && !child[0].childhood().completeAssistedFirstToilet(parent[0])
                            && waste(level, area) == 0,
                    "brief rear contact cannot bypass the continuous stimulation and release phases through either public API");
        })
        .thenWaitUntil(() -> helper.assertTrue(child[0].childhood().firstToiletDone(), "continuous rear stimulation followed by the release phase completes the first toilet"))
        .thenExecute(() -> {
            helper.assertTrue(waste(level, area) == 1 && child[0].life().bladder() == 0,
                    "completed assistance creates exactly one real waste and clears the current urge");
            CompoundTag saved = child[0].childhood().save(); child[0].childhood().load(saved);
            vitals(child[0], 10);
            phase[0] = "saved_first_toilet";
        }).thenIdle(40).thenExecute(() -> {
            helper.assertTrue(child[0].childhood().firstToiletDone() && waste(level, area) == 1,
                    "saved completion prevents a second assisted-first-toilet reward");
            vitals(child[0], 0); observePractice.set(true); phase[0] = "movement_practice";
        }).thenWaitUntil(() -> helper.assertTrue(child[0].childhood().canJump() && airborne.get() && landedAfterAirborne.get(),
                "ordinary newborn gains jumping only after real crawling and a complete lift-off then landing; state="
                        + child[0].childhood().save() + ", activity=" + child[0].getBehaviorId()
                        + ", position=" + child[0].position() + ", energy=" + child[0].life().energy()))
        .thenSucceed();
    }

    private static Map<String, Object> practiceDebug(ServerLevel level, String phase, YukkuriEntity parent, YukkuriEntity child) {
        var state = new java.util.LinkedHashMap<String, Object>();
        state.put("gameTime", level.getGameTime()); state.put("phase", phase);
        state.put("parent", parent.saveWithoutId(new CompoundTag()).toString());
        state.put("child", child.saveWithoutId(new CompoundTag()).toString());
        state.put("parentActivity", parent.childhood().activity()); state.put("childActivity", child.childhood().activity());
        state.put("parentBehavior", parent.getBehaviorId()); state.put("childBehavior", child.getBehaviorId());
        state.put("parentApproach", parent.careApproach().debug().toString()); state.put("childApproach", child.careApproach().debug().toString());
        state.put("rearContact", io.github.longshou.mcyukkuri.family.ChildhoodLife.toiletContact(parent, child));
        state.put("careProgress", parent.childhood().careProgress());
        return state;
    }

    // Remote trace: rear care/return ends around tick 360, actual crawling reaches .75 near 640,
    // then five landings with 24-tick rests finish near 785. Allow that complete sequence.
    @GameTest(templateNamespace = P0Mod.ID, template = "empty", timeoutTicks = 1000, batch = "childhood")
    public static void aquaticNewbornReceivesHatCare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = new BlockPos(X + 32, 65, 8);
        AABB area = new AABB(origin).inflate(12, 6, 12);
        int firstChunk = (origin.getX() - 6) >> 4, lastChunk = (origin.getX() + 12) >> 4;
        Map<Long, Boolean> forced = new HashMap<>();
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++)
            forced.put(net.minecraft.world.level.ChunkPos.asLong(chunk, 0), level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(chunk, 0)));
        Map<BlockPos, BlockState> terrain = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-6, -1, -6), origin.offset(6, -1, 6))) {
            terrain.put(p.immutable(), level.getBlockState(p));
            level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
        }
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(1, 0, -2), origin.offset(5, 0, 2))) {
            terrain.put(p.immutable(), level.getBlockState(p));
            level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
        }
        YukkuriEntity[] parent = {null}, child = {null}; HatBoatEntity[] boat = {null};
        var practiceTrace = new java.util.ArrayList<Map<String,Object>>();
        boolean[] cleaned = {false};
        Runnable cleanup = () -> {
            if (cleaned[0]) return;
            cleaned[0] = true;
            level.getEntitiesOfClass(Entity.class, area, entity -> entity instanceof YukkuriEntity || entity instanceof HatBoatEntity || entity instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);
            for (int chunk = firstChunk; chunk <= lastChunk; chunk++)
                GameTestChunks.setForced(level, chunk, 0, forced.get(net.minecraft.world.level.ChunkPos.asLong(chunk, 0)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) { }
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info, net.minecraft.gametest.framework.GameTestRunner runner) {
                if(parent[0]!=null && child[0]!=null && boat[0]!=null){
                    var debug=new java.util.LinkedHashMap<String,Object>();
                    debug.put("parent",parent[0].saveWithoutId(new CompoundTag()).toString());
                    debug.put("child",child[0].saveWithoutId(new CompoundTag()).toString());
                    debug.put("hat",boat[0].saveWithoutId(new CompoundTag()).toString());
                    debug.put("activity",parent[0].aquaticLife().care().activity());
                    debug.put("careReady",boat[0].careSeatReady(child[0]));debug.put("toiletReady",boat[0].toiletSeatReady(child[0]));
                    debug.put("parentTransport",boat[0].transportingInHat(parent[0]));
                    debug.put("parentLocal",boat[0].toLocal(parent[0].position()).toString());
                    debug.put("childLocal",boat[0].toLocal(child[0].position()).toString());
                    debug.put("mouthsMeet",io.github.longshou.mcyukkuri.family.FamilyFeedingContact.mouthsMeet(parent[0],child[0]));
                    debug.put("rearContact",io.github.longshou.mcyukkuri.family.ChildhoodLife.toiletContact(parent[0],child[0]));
                    P0Control.write("aquatic-newborn-care-failure",debug);
                    P0Control.write("aquatic-newborn-practice-trace",practiceTrace);
                }
                cleanup.run();
            }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo, net.minecraft.gametest.framework.GameTestInfo newInfo, net.minecraft.gametest.framework.GameTestRunner runner) { }
        });
        for (int chunk = firstChunk; chunk <= lastChunk; chunk++) GameTestChunks.setForced(level, chunk, 0, true);
        ItemEntity[] firstWaste = {null};
        Vec3[] firstWastePosition = {null}, jumpStart = {null};
        AtomicBoolean reachedWater = new AtomicBoolean(), sawHatCrawl = new AtomicBoolean();
        AtomicBoolean sawHatJump = new AtomicBoolean(), roseDuringHatJump = new AtomicBoolean(), observePractice = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (boat[0] == null || child[0] == null) return;
            if(child[0].tickCount%20==0){
                var sample=new java.util.LinkedHashMap<String,Object>();
                sample.put("tick",child[0].tickCount);sample.put("state",child[0].childhood().debug().toString());
                sample.put("local",boat[0].toLocal(child[0].position()).toString());sample.put("practiceMove",boat[0].practisingMove(child[0]));
                sample.put("changingSeat",boat[0].changingSeat(child[0]));sample.put("protected",boat[0].protects(child[0]));
                sample.put("childActivity",child[0].aquaticLife().activity());sample.put("parentCare",parent[0].aquaticLife().care().activity());
                practiceTrace.add(sample);if(practiceTrace.size()>60)practiceTrace.remove(0);
            }
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, area, entry -> entry.getItem().is(ModItems.WASTE.get()))) {
                if (firstWaste[0] == null) {
                    firstWaste[0] = item;
                    firstWastePosition[0] = item.position();
                }
                if (level.getFluidState(item.blockPosition()).is(net.minecraft.tags.FluidTags.WATER)) reachedWater.set(true);
            }
            if (!observePractice.get()) return;
            String activity = child[0].childhood().activity();
            if (activity.equals("newborn_crawl")) sawHatCrawl.set(true);
            if (activity.equals("learn_jump")) {
                sawHatJump.set(true);
                Vec3 local=boat[0].toLocal(child[0].position());
                if (jumpStart[0] == null) jumpStart[0] = local;
                else if ((local.y-jumpStart[0].y)*boat[0].unit() > .02) roseDuringHatJump.set(true);
            }
        });
        helper.startSequence().thenWaitUntil(() -> GameTestChunks.assertTicking(helper, firstChunk, lastChunk, 0, 0)).thenExecute(() -> {
            parent[0] = ModEntities.MARISA.get().create(level); child[0] = ModEntities.MARISA.get().create(level);
            parent[0].subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA, SubspeciesLife.Congenital.NORMAL);
            child[0].subspecies().set(SubspeciesLife.Kind.AQUATIC_MARISA, SubspeciesLife.Congenital.NORMAL);
            // createFor requires a dry owner within three blocks of the exact water centre.
            parent[0].moveTo(origin.getX() + .75, origin.getY(), origin.getZ(), 0, 0);
            child[0].moveTo(origin.getX() - .5, origin.getY(), origin.getZ(), 0, 0);
            adult(parent[0]); newborn(child[0], parent[0]); vitals(parent[0], 0); vitals(child[0], 10);
            helper.assertTrue(level.addFreshEntity(parent[0]) && level.addFreshEntity(child[0]), "aquatic parent and newborn join the level");
            boat[0] = HatBoatEntity.createFor(parent[0], origin.offset(3, 0, 0));
            helper.assertTrue(boat[0] != null, "a dry parent within the real launch radius creates its hat");
            helper.assertTrue(boat[0].boardYoung(child[0]), "the actual newborn occupies a protected hat berth");
        }).thenWaitUntil(() -> helper.assertTrue(child[0].childhood().landed() && child[0].childhood().needsAssistedFirstToilet(),
                "a settled hat passenger keeps its real first-toilet need instead of suppressing it"))
        .thenWaitUntil(() -> helper.assertTrue(child[0].childhood().firstToiletDone(),
                "the parent completes the same one-time care while both family members remain in the hat"))
        .thenWaitUntil(() -> helper.assertTrue(firstWaste[0] != null && reachedWater.get(),
                "the one rear-rim waste begins above the water and falls into the surrounding surface"))
        .thenExecute(() -> {
            helper.assertTrue(parent[0].getVehicle() == boat[0] && child[0].getVehicle() == boat[0] && waste(level, area) == 1,
                    "hat care keeps both family members aboard and creates exactly one source-tagged waste");
            Vec3 localWaste = boat[0].toLocal(firstWastePosition[0]);
            helper.assertTrue(firstWastePosition[0].y > origin.getY() + 1.05
                            && !Double.isFinite(HatVessel.surface(HatVessel.Mode.FLOAT, localWaste.x, localWaste.z)),
                    "waste starts high beyond the actual hat brim, rather than being placed directly on the water plane");
            vitals(child[0], 0);
            observePractice.set(true);
        }).thenWaitUntil(() -> helper.assertTrue(child[0].childhood().canJump() && sawHatCrawl.get() && sawHatJump.get() && roseDuringHatJump.get()
                        &&child[0].childhood().save().getDouble("CrawlDistance")>=io.github.longshou.mcyukkuri.family.ChildhoodLife.CRAWL_DISTANCE_REQUIRED
                        &&child[0].childhood().save().getInt("JumpLandings")>=5,
                "the protected newborn earns its aquatic jump after real relative-to-hat crawling and five completed upward hops"))
        .thenExecute(()->P0Control.write("aquatic-newborn-practice-trace",practiceTrace))
        .thenSucceed();
    }

    private ChildhoodLifeGameTests() {}
}
