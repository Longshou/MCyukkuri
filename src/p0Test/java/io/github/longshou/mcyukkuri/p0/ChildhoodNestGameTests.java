package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only coverage for shared childhood beds and the real-material personal-nest transition. */
@PrefixGameTestTemplate(false)
public final class ChildhoodNestGameTests {
    private static final BlockPos ORIGIN=new BlockPos(2752,65,8);

    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=900,batch="childhood")
    public static void sharedNestAndPersonalNest(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        long day=level.getDayTime();
        boolean daylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
        boolean grief=level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        boolean rain=level.isRaining(),thunder=level.isThundering();
        float rainLevel=level.getRainLevel(1),thunderLevel=level.getThunderLevel(1);
        var forced=new java.util.HashSet<>(level.getForcedChunks());
        var familyRecords=new java.util.HashSet<java.util.UUID>();
        var terrain=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        ShelterBlockEntity[] home={null};
        YukkuriEntity[] parent={null},child={null};
        ItemEntity[] leaf={null};BlockPos[] grass={null};
        boolean[] cleaned={false};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;
            var current=ShelterBlock.shelter(level,ORIGIN);if(current!=null)current.rollback();
            if(home[0]!=null&&home[0]!=current)home[0].rollback();
            level.getEntitiesOfClass(Entity.class,new AABB(ORIGIN).inflate(20),
                    entity->entity instanceof YukkuriEntity||entity instanceof ItemEntity).forEach(Entity::discard);
            terrain.forEach(level::setBlockAndUpdate);
            level.setDayTime(day);
            level.setWeatherParameters(12000,12000,rain,thunder);level.setRainLevel(rainLevel);level.setThunderLevel(thunderLevel);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(daylight,level.getServer());
            level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(grief,level.getServer());
            FamilyData.get(level).rollbackEncounter(familyRecords);
            for(int x=171;x<=173;x++)for(int z=-1;z<=1;z++)
                GameTestChunks.setForced(level,x,z,forced.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        };
        helper.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener() {
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info) {}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner) { cleanup.run(); }
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner) {}
        });
        ShelterGameTests.daylightFixture(level);
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
        for(int x=171;x<=173;x++)for(int z=-1;z<=1;z++)GameTestChunks.setForced(level,x,z,true);
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,171,173,-1,1)).thenExecute(()->{
            level.getEntitiesOfClass(Entity.class,new AABB(ORIGIN).inflate(20),
                    entity->entity instanceof YukkuriEntity||entity instanceof ItemEntity).forEach(Entity::discard);
            // The nine-cell pit helper excavates existing ground; its surrounding earth provides the banks.
            for(var pos:BlockPos.betweenClosed(ORIGIN.offset(-5,-2,-6),ORIGIN.offset(5,4,4))) {
                terrain.put(pos.immutable(),level.getBlockState(pos));
                if(pos.getY()==64)level.setBlockAndUpdate(pos,Blocks.GRASS_BLOCK.defaultBlockState());
                else if(pos.getY()>64)level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            }
            NaturalShelterFixture.pit(level,ORIGIN.below());
            var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(ORIGIN.below(),ShelterBlock.Kind.BURROW,Direction.SOUTH));
            helper.assertTrue(placed!=null,"A surveyed natural pit is available for the shared childhood nest");
            home[0]=placed.shelter();home[0].initializeEncounter(2);

            parent[0]=ModEntities.REIMU.get().create(level);
            parent[0].moveTo(Vec3.atBottomCenterOf(home[0].getBlockPos()));parent[0].setOnGround(true);parent[0].setNoAi(true);
            helper.assertTrue(level.addFreshEntity(parent[0]),"Parent fixture joins the server");
            child[0]=ModEntities.MARISA.get().create(level);
            child[0].moveTo(Vec3.atBottomCenterOf(home[0].getBlockPos()));child[0].setOnGround(true);
            helper.assertTrue(level.addFreshEntity(child[0]),"Child fixture joins the server");
            familyRecords.add(parent[0].getUUID());familyRecords.add(child[0].getUUID());
            child[0].family().generatedChild(parent[0],null);
            FamilyData.get(level).register(parent[0]);FamilyData.get(level).register(child[0]);
            setVitals(child[0],100);
            helper.assertTrue(parent[0].habitat().choose(home[0])&&child[0].habitat().choose(home[0]),
                    "Parent and identified child claim separate physical shared-nest beds");
            var parentBed=home[0].bed(parent[0].getUUID());
            var childBed=home[0].bed(child[0].getUUID());
            helper.assertTrue(parentBed!=null&&childBed!=null&&!parentBed.equals(childBed)
                    &&home[0].sharedBeds().containsAll(List.of(parentBed,childBed)),
                    "Shared nest keeps non-overlapping occupied leaf-bed cells");
            double assigned=childBed.distSqr(parentBed);
            boolean nearestFree=home[0].beds().stream().filter(bed->!bed.equals(parentBed)&&!bed.equals(childBed))
                    .allMatch(bed->assigned<=bed.distSqr(parentBed));
            helper.assertTrue(nearestFree,"A child receives the nearest available bed to its resident parent");

            var low=child[0].family().save();
            low.putBoolean("Initialized",true);low.putString("Stage","JUVENILE");low.putLong("Age",child[0].family().maturityAge()/2-240);
            low.putLong("Nourishment",child[0].family().maturityAge()*3/8);low.putBoolean("AwaitingLanding",false);
            child[0].family().load(low);
            leaf[0]=drop(level,childBed,Items.OAK_LEAVES);
        }).thenIdle(120).thenExecute(()->{
            helper.assertTrue(!child[0].family().readyForOwnNest()&&!home[0].privateNest(child[0].getUUID())
                    &&leaf[0].isAlive(),
                    "An early child keeps the family nest and does not collect household or nest materials");
            var ready=child[0].family().save();
            ready.putLong("Age",child[0].family().maturityAge()/2);
            ready.putLong("Nourishment",child[0].family().maturityAge()*3/8);
            ready.putBoolean("AwaitingLanding",false);child[0].family().load(ready);
            setVitals(child[0],100);
        }).thenWaitUntil(()->helper.assertTrue(!child[0].habitat().carrying().isEmpty()&&!home[0].privateNest(child[0].getUUID()),
                "A material shortfall leaves the first real portion reserved without creating a personal nest"))
        .thenExecute(()->{
            var saved=child[0].habitat().save();child[0].habitat().stop();child[0].habitat().load(saved);
            helper.assertTrue(saved.contains("Carrying")&&!saved.contains("NestMaterial")&&!child[0].habitat().carrying().isEmpty(),
                    "Interrupted one-portion work survives save and reload without inventing a second portion");
            grass[0]=home[0].beds().stream().filter(bed->!bed.equals(home[0].bed(child[0].getUUID()))
                    &&!bed.equals(home[0].bed(parent[0].getUUID()))).findFirst().orElse(home[0].entrance());
            level.setBlockAndUpdate(grass[0],Blocks.SHORT_GRASS.defaultBlockState());
        }).thenWaitUntil(()->helper.assertTrue(child[0].habitat().save().contains("NestMaterial")&&!home[0].privateNest(child[0].getUUID()),
                "The second real portion is retained separately while the build is in progress"))
        .thenExecute(()->{
            var saved=child[0].habitat().save();child[0].habitat().stop();child[0].habitat().load(saved);
            helper.assertTrue(saved.contains("Carrying")&&saved.contains("NestMaterial"),
                    "Two reserved mixed portions both survive an interrupted construction reload");
        }).thenWaitUntil(()->helper.assertTrue(home[0].privateNest(child[0].getUUID()),
                "Late child must walk, collect two real nearby plant portions and work its own bed before a personal nest exists"))
        .thenExecute(()->{
            helper.assertTrue(home[0].privateBeds().equals(List.of(home[0].bed(child[0].getUUID())))
                    &&home[0].sharedBeds().equals(List.of(home[0].bed(parent[0].getUUID()))),
                    "Personal nest separates only the child's bed while preserving the parent shared-nest bed");
            helper.assertTrue(!leaf[0].isAlive()&&level.getBlockState(grass[0]).isAir()&&child[0].habitat().carrying().isEmpty(),
                    "A real leaf and one short grass harvested near the nest are consumed only after construction completes");
            var saved=home[0].saveWithFullMetadata(level.registryAccess());
            var reloaded=new ShelterBlockEntity(ORIGIN.below(),home[0].getBlockState());reloaded.setLevel(level);
            reloaded.loadWithComponents(saved,level.registryAccess());
            helper.assertTrue(reloaded.privateNest(child[0].getUUID())
                    &&reloaded.privateBeds().equals(List.of(home[0].bed(child[0].getUUID()))),
                    "Personal-nest ownership and its resident bed survive shelter save and load");
            P0Control.write("childhood-nest-checks",List.of(
                    new Check("early child remains in the shared nest",true),
                    new Check("late child consumes a dropped leaf and one nearby short grass",true),
                    new Check("private nest state persists with the original bed",true)));
        }).thenSucceed();
    }

    private record Check(String name,boolean pass) {}
    private static ItemEntity drop(ServerLevel level,BlockPos pos,net.minecraft.world.item.Item item) {
        var entity=new ItemEntity(level,pos.getX()+.5,pos.getY()+.1,pos.getZ()+.5,new ItemStack(item));
        entity.setDeltaMovement(Vec3.ZERO);entity.setNoPickUpDelay();level.addFreshEntity(entity);return entity;
    }
    private static void setVitals(YukkuriEntity entity,float nutrition) {
        var tag=entity.life().save();
        tag.putFloat("Nutrition",nutrition);tag.putFloat("Energy",100);tag.putFloat("Hygiene",100);tag.putFloat("Bladder",0);
        entity.life().load(tag);
    }
}
