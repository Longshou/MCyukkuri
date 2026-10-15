package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.family.ShedStemDrops;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Remote-only actual birth/landing and durable outbox boundaries. */
@PrefixGameTestTemplate(false)
public final class ShedStemGameTests {
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=300,batch="shed_stem")
    public static void stemTransfer(GameTestHelper h) {
        var level=h.getLevel();var origin=h.absolutePos(new BlockPos(2,2,2));
        int minX=(origin.getX()-2)>>4,maxX=(origin.getX()+12)>>4,minZ=(origin.getZ()-2)>>4,maxZ=(origin.getZ()+8)>>4;
        var forcedBefore=new java.util.HashSet<Long>(level.getForcedChunks());
        var families=FamilyData.get(level);var drops=ShedStemDrops.get(level);
        var actors=new ArrayList<Entity>();var babies=new ArrayList<YukkuriEntity>();
        var brood=new FamilyData.Brood[1];var unloaded=new CompoundTag[1];var waiting=new CompoundTag[1];
        var parents=new YukkuriEntity[2];var hat=new HatBoatEntity[1];
        var blocks=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        var cleaned=new boolean[1];
        Consumer<EntityJoinLevelEvent> cancel=e->{if(brood[0]!=null&&e.getEntity().getUUID().equals(ShedStemDrops.itemId(brood[0].id)))e.setCanceled(true);};
        Runnable cleanup=()->{
            if(cleaned[0])return;cleaned[0]=true;NeoForge.EVENT_BUS.unregister(cancel);
            actors.forEach(Entity::discard);blocks.forEach(level::setBlockAndUpdate);
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)GameTestChunks.setForced(level,x,z,forcedBefore.contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        };
        h.testInfo.addListener(new net.minecraft.gametest.framework.GameTestListener(){
            public void testStructureLoaded(net.minecraft.gametest.framework.GameTestInfo info){}
            public void testPassed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testFailed(net.minecraft.gametest.framework.GameTestInfo info,net.minecraft.gametest.framework.GameTestRunner runner){cleanup.run();}
            public void testAddedForRerun(net.minecraft.gametest.framework.GameTestInfo oldInfo,net.minecraft.gametest.framework.GameTestInfo newInfo,net.minecraft.gametest.framework.GameTestRunner runner){}
        });
        NeoForge.EVENT_BUS.addListener(cancel);
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)GameTestChunks.setForced(level,x,z,true);
        h.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(h,minX,maxX,minZ,maxZ)).thenExecute(()->{
            for(var p:BlockPos.betweenClosed(origin.offset(-2,-1,-2),origin.offset(12,8,8))){
                blocks.put(p.immutable(),level.getBlockState(p));level.setBlockAndUpdate(p,p.getY()==origin.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
            }
            var first=origin;var second=origin.east(3);
            level.setBlockAndUpdate(first,ModBlocks.NEST.get().defaultBlockState());level.setBlockAndUpdate(second,ModBlocks.NEST.get().defaultBlockState());
            // Ordinary nests need a real covered entrance, just like player-provided homes.
            for(var home:java.util.List.of(first,second))for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)
                level.setBlockAndUpdate(home.offset(x,3,z),Blocks.STONE.defaultBlockState());
            parents[0]=parent(level,false,origin.offset(0,0,-1));parents[1]=parent(level,true,origin.offset(2,0,-1));
            actors.add(parents[0]);actors.add(parents[1]);
            h.assertTrue(parents[0].life().entrance(first)!=null&&parents[1].life().entrance(second)!=null,"physical roofs provide both nests with dry covered entrances");
            h.assertTrue(parents[0].life().assignHome(GlobalPos.of(level.dimension(),first))&&parents[1].life().assignHome(GlobalPos.of(level.dimension(),second)),"real parents own physical homes");
            YukkuriWorldData.get(level).relate(parents[0].getUUID(),parents[1].getUUID(),30);
            YukkuriWorldData.get(level).relate(parents[1].getUUID(),parents[0].getUUID(),30);
            long seed=parents[0].getUUID().getMostSignificantBits()^parents[1].getUUID().getLeastSignificantBits();int serial=0;
            while(serial<100000&&FamilyData.offspringCount(parents[0].family().adultScale(),seed^serial)!=5)serial++;
            var family=parents[0].family().save();family.putInt("Conceptions",serial);parents[0].family().load(family);
            brood[0]=families.conceive(parents[0],parents[1],FamilyData.BirthKind.STEM,GlobalPos.of(level.dimension(),first));
            h.assertTrue(brood[0]!=null&&brood[0].children.size()==5,"real conception reserves five identified children");
            var b=brood[0];b.elapsed=b.duration+40;
            h.assertTrue(!drops.queue(parents[0],b),"incomplete brood cannot queue a stem");
            for(int i=0;i<5;i++){
                var baby=families.birth(b,i,level,Vec3.atBottomCenterOf(origin.offset(5+i,4,3)));
                h.assertTrue(baby!=null&&baby.family().awaitingLanding()&&!baby.onGround(),"real child starts airborne and unresolved");
                WaddleGameTests.clearGoals(baby);babies.add(baby);actors.add(baby);
            }
            eligibility(h,parents[0],b);
            parents[0].family().tick();parents[0].syncFamily();drops.deliver(level.getServer());
            h.assertTrue(parents[0].family().brood()==b&&parents[0].broodCount()==5&&drops.pending(b.id)&&!drops.ready(b.id),"complete insertion keeps maternal stem while actual children are airborne");
            // One normal ground landing, one real hat flight, one doomed water contact, one discard, one unload/reload.
            babies.get(0).setNoAi(true); // Disable AI while airborne: physics must still confirm actual ground contact.
            hat[0]=HatBoatEntity.setDown(parents[1],Vec3.atBottomCenterOf(origin.offset(6,0,6)),0,HatVessel.Mode.NEST,HatVessel.Role.HELPER);
            h.assertTrue(hat[0]!=null,"father provides an actual catching hat");actors.add(hat[0]);
            babies.get(1).family().launchInto(hat[0],HatVessel.TMIN,null);
            level.setBlockAndUpdate(origin.offset(7,0,3),Blocks.WATER.defaultBlockState());
            babies.get(2).family().doom();babies.get(2).moveTo(Vec3.atBottomCenterOf(origin.offset(7,0,3)).add(0,.1,0));
            babies.get(3).discard();
            unloaded[0]=new CompoundTag();babies.get(4).saveWithoutId(unloaded[0]);babies.get(4).remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            waiting[0]=drops.save(new CompoundTag(),level.registryAccess());
        }).thenIdle(45).thenExecute(()->{
            var b=brood[0];
            h.assertTrue(b.children.get(0).landed&&babies.get(0).isNoAi()&&babies.get(0).onGround()&&!babies.get(0).family().awaitingLanding(),"NoAI child really lands on the ground before confirmation");
            h.assertTrue(b.children.get(1).landed&&babies.get(1).getVehicle()==hat[0]&&!babies.get(1).family().flying(),"hat birth waits until the actual flight has boarded");
            h.assertTrue(families.person(b.children.get(2).id).status==FamilyData.Status.DEAD,"doomed water contact records death, not a live landing");
            h.assertTrue(families.person(b.children.get(3).id).status==FamilyData.Status.REMOVED,"discard is an explicit terminal outcome");
            h.assertTrue(!b.children.get(4).landed&&!drops.ready(b.id)&&parents[0].family().brood()==b,"ordinary child unload keeps the maternal stem and waits");
            var restored=ShedStemDrops.load(waiting[0],level.registryAccess());restored.deliver(level.getServer());
            h.assertTrue(restored.pending(b.id)&&!restored.ready(b.id)&&level.getEntity(ShedStemDrops.itemId(b.id))==null,"waiting state survives outbox reload without inventing a landing");
            parents[0].hurt(level.damageSources().generic(),100);
            h.assertTrue(!parents[0].isAlive()&&drops.pending(b.id),"mother death cannot lose the world-owned landing wait");
            var reloaded=ModEntities.forVariant(b.children.get(4).variant).create(level);reloaded.load(unloaded[0]);
            h.assertTrue(level.addFreshEntity(reloaded),"unloaded child returns with the same identity");WaddleGameTests.clearGoals(reloaded);babies.set(4,reloaded);actors.add(reloaded);
        }).thenIdle(40).thenExecute(()->{
            var b=brood[0];UUID itemId=ShedStemDrops.itemId(b.id);
            h.assertTrue(b.children.get(4).landed&&!babies.get(4).family().awaitingLanding(),"reloaded child really lands before the wait resolves");
            drops.deliver(level.getServer());
            h.assertTrue(drops.ready(b.id)&&drops.pending(b.id)&&level.getEntity(itemId)==null,"cancelled item insertion retains the now-resolved transfer");
            var resolved=drops.save(new CompoundTag(),level.registryAccess());
            NeoForge.EVENT_BUS.unregister(cancel);
            var restored=ShedStemDrops.load(resolved,level.registryAccess());restored.deliver(level.getServer());
            h.assertTrue(restored.delivered(b.id)&&level.getEntity(itemId) instanceof ItemEntity item
                    &&item.getItem().is(ModItems.YUKKURI_STEM.get())&&item.getItem().getCount()==1,"saved resolved transfer survives mother death and creates one real empty stem");
            var replay=ShedStemDrops.load(resolved,level.registryAccess());replay.deliver(level.getServer());
            h.assertTrue(replay.delivered(b.id),"existing stable item identity closes a stale transfer");
            drops.deliver(level.getServer());h.assertTrue(drops.delivered(b.id),"live outbox closes against the same stable item");
            level.getEntity(itemId).discard();
            var delivered=ShedStemDrops.load(restored.save(new CompoundTag(),level.registryAccess()),level.registryAccess());delivered.deliver(level.getServer());
            h.assertTrue(level.getEntity(itemId)==null,"consumed delivered item is not recreated on reload");
            cleanup.run();h.succeed();
        });
    }
    private static void eligibility(GameTestHelper h,YukkuriEntity mother,FamilyData.Brood actual){
        var sample=new FamilyData.Brood();sample.id=UUID.randomUUID();sample.mother=actual.mother;sample.children.addAll(actual.children);
        var guard=new ShedStemDrops();sample.kind=FamilyData.BirthKind.LIVE;
        h.assertTrue(!guard.queue(mother,sample),"live birth cannot queue an empty stem");sample.kind=FamilyData.BirthKind.EGG;
        h.assertTrue(!guard.queue(mother,sample),"house eggs cannot queue an empty stem");sample.kind=FamilyData.BirthKind.STEM;sample.detachedHolder=UUID.randomUUID();
        h.assertTrue(!guard.queue(mother,sample),"cut cultivated stem cannot queue an extra item");sample.detachedHolder=null;sample.cancelled=true;
        h.assertTrue(!guard.queue(mother,sample),"cancelled brood cannot queue a stem");sample.cancelled=false;
        h.assertTrue(guard.queue(mother,sample)&&!guard.queue(mother,sample)&&!guard.ready(sample.id),"natural completion queues one wait, never immediate landing success");
    }
    private static YukkuriEntity parent(net.minecraft.server.level.ServerLevel level,boolean marisa,BlockPos pos){
        var y=(marisa?ModEntities.MARISA:ModEntities.REIMU).get().create(level);y.moveTo(Vec3.atBottomCenterOf(pos));y.setOnGround(true);y.setNoAi(true);y.setPersistenceRequired();
        var family=y.family().save();family.putBoolean("Initialized",true);family.putLong("Maturity",24000);family.putLong("Age",24001);family.putLong("Nourishment",24000);family.putLong("Lifespan",72000);y.family().load(family);
        var life=y.life().save();life.putFloat("Nutrition",90);life.putFloat("Energy",90);life.putFloat("Hygiene",100);y.life().load(life);
        if(!level.addFreshEntity(y))throw new IllegalStateException("Parent insertion failed");return y;
    }
    private ShedStemGameTests() {}
}
