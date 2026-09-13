package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class GroupGameTests {
    private record Check(String name,boolean pass){}
    private final ArrayList<Check> checks=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    private static void floor(ServerLevel level,int start){
        for(int chunk=start>>4;chunk<=(start+31)>>4;chunk++)level.setChunkForced(chunk,0,true);
        for(int x=start;x<start+32;x++)for(int z=0;z<12;z++){
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
            for(int y=65;y<70;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        }
    }
    private static void clean(ServerLevel level,int start){
        level.getEntitiesOfClass(Entity.class,new AABB(start,63,0,start+32,71,12),e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
        for(int chunk=start>>4;chunk<=(start+31)>>4;chunk++)level.setChunkForced(chunk,0,false);
    }
    private YukkuriEntity spawn(ServerLevel level,double x,boolean ai){
        var e=ModEntities.REIMU.get().create(level);e.moveTo(x,65,5.5,0,0);e.setNoAi(!ai);e.setOnGround(true);e.setPersistenceRequired();
        var state=e.life().save();state.putFloat("Nutrition",90);state.putFloat("Energy",90);state.putFloat("Hygiene",100);e.life().load(state);
        var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);level.addFreshEntity(e);return e;
    }
    private void friend(ServerLevel level,YukkuriEntity a,YukkuriEntity b){var r=YukkuriWorldData.get(level);r.relate(a.getUUID(),b.getUUID(),10);r.relate(b.getUUID(),a.getUUID(),10);}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=500,batch="group_rules")
    public static void groupRules(GameTestHelper helper){
        var test=new GroupGameTests();var level=helper.getLevel();floor(level,1296);
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(new BlockPos(1302,65,5)),"waiting for group area"))
        .thenExecute(()->{
            try{test.rules(level);}catch(Throwable e){test.check("unexpected "+e,false);}finally{clean(level,1296);}
            P0Control.write("group-rule-checks",test.checks);var failures=test.checks.stream().filter(c->!c.pass).toList();
            if(failures.isEmpty())helper.succeed();else helper.fail(failures.toString());
        });
    }
    private void rules(ServerLevel level){
        var a=spawn(level,1302.5,false);var b=spawn(level,1303.2,false);var c=spawn(level,1304,false);
        check("strangers decline challenge",!a.groupInteraction().challenge(b));friend(level,a,b);friend(level,c,b);
        check("friends reserve both participants",a.groupInteraction().challenge(b)&&b.groupInteraction().active());
        check("third participant cannot take occupied opponent",!c.groupInteraction().challenge(b));
        float before=a.life().energy();a.groupInteraction().tick();check("approach does not charge result",a.life().energy()==before);
        var saved=a.groupInteraction().save();a.groupInteraction().load(saved);
        check("reload cancels both reservations and retains cooldown",!a.groupInteraction().active()&&!b.groupInteraction().active()&&a.groupInteraction().cooldown()>0);
        check("reload cannot replay challenge",!a.groupInteraction().challenge(b));
        a.groupInteraction().load(new net.minecraft.nbt.CompoundTag());b.groupInteraction().load(new net.minecraft.nbt.CompoundTag());
        check("next eligible bout starts",a.groupInteraction().challenge(b));
        var token=YukkuriWorldData.get(level).capture(b);
        check("capture releases partner immediately",token!=null&&!a.groupInteraction().active());
        var restored=YukkuriWorldData.get(level).release(token,level,new net.minecraft.world.phys.Vec3(1303.2,65,5.5),0);
        check("custody preserves identity and cooldown",restored!=null&&restored.getUUID().equals(b.getUUID())&&restored.groupInteraction().cooldown()>0);
        var toy=new ItemEntity(level,1302.85,65,5.5,new ItemStack(ModItems.TOY.get(),3));toy.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);level.addFreshEntity(toy);
        check("first player reserves real toy",a.social().beginPlay(toy));check("toy cannot be pushed by two sessions",!c.social().beginPlay(toy));
        before=a.life().energy();for(int i=0;i<12;i++)a.social().tickGoal();
        check("contact nudges same stack and consumes energy",a.social().toyTouches()==1&&toy.getItem().getCount()==3&&a.life().energy()==before-1&&toy.getDeltaMovement().horizontalDistanceSqr()>0);
        a.social().stop();check("interruption releases toy",c.social().beginPlay(toy));c.social().stop();
        check("distant toy cannot start interaction",!spawn(level,1320,false).social().beginPlay(toy));
        check("negative energy spending rejected",!a.life().spendEnergy(-1));
        var guardian=spawn(level,1305,true);var traits=guardian.life().personality().save();
        traits.putIntArray("Innate",new int[]{90,90,90,90,90,90});traits.putIntArray("Experience",new int[6]);guardian.life().personality().load(traits);
        YukkuriWorldData.get(level).relate(guardian.getUUID(),restored.getUUID(),30);
        restored.invulnerableTime=0;restored.hurt(level.damageSources().mobAttack(a),1);
        check("present caring acquaintance reserves protection",guardian.groupInteraction().active());
        var victimToken=YukkuriWorldData.get(level).capture(restored);guardian.groupInteraction().clock();
        check("protected peer removal interrupts protection",victimToken!=null&&!guardian.groupInteraction().active()&&!a.groupInteraction().active());
        for(var type:java.util.List.of(io.github.longshou.mcyukkuri.life.Facilities.CERAMIC_TOILET,io.github.longshou.mcyukkuri.life.Facilities.COVERED_TOILET)){
            var pos=new BlockPos(1310,65,8);level.setBlockAndUpdate(pos,type.get().defaultBlockState());var care=(io.github.longshou.mcyukkuri.life.CareBlockEntity)level.getBlockEntity(pos);
            int capacity=care.capacity();for(int i=0;i<capacity;i++){var portion=io.github.longshou.mcyukkuri.body.DetachedItems.waste(a);care.relieve(portion);}
            check("upgraded capacity full without overflow "+capacity,care.waste()==capacity&&!care.relieve());
            care.loadWithComponents(care.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
            var recovered=care.cleanItems();check("upgrade reload returns every portion "+capacity,recovered.size()==capacity&&care.waste()==0&&care.cleanItems().isEmpty());
            level.removeBlock(pos,false);
        }
        var abrasive=spawn(level,1317.5,true);
        for(int x=1316;x<=1319;x++)for(int z=4;z<=6;z++)level.setBlockAndUpdate(new BlockPos(x,65,z),io.github.longshou.mcyukkuri.life.Facilities.SANDPAPER.get().defaultBlockState());
        abrasive.setPos(1317.5,65+.25/16,5.5);abrasive.setOnGround(true);abrasive.surfaceLife().tick();float health=abrasive.getHealth();
        for(int i=0;i<20;i++)abrasive.surfaceLife().tick();check("stationary sandpaper does not injure",abrasive.getHealth()==health);
        for(int i=0;i<15;i++){abrasive.move(net.minecraft.world.entity.MoverType.SELF,new net.minecraft.world.phys.Vec3(.025,-.02,0));abrasive.surfaceLife().tick();}
        check("real grounded sandpaper motion causes a scratch",abrasive.getHealth()<health&&abrasive.body().wounds().stream().anyMatch(w->w.kind().equals("scratch")));
        float previous=abrasive.surfaceLife().save().getFloat("Abrasion");abrasive.setPos(1319,65+.25/16,5.5);abrasive.surfaceLife().tick();
        check("teleport does not integrate skipped surface",abrasive.surfaceLife().save().getFloat("Abrasion")==previous);
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=600,batch="group_live")
    public static void liveBout(GameTestHelper helper){
        var test=new GroupGameTests();var level=helper.getLevel();floor(level,1344);var pair=new YukkuriEntity[2];
        helper.startSequence().thenWaitUntil(()->helper.assertTrue(level.isPositionEntityTicking(new BlockPos(1350,65,5)),"waiting for live group area"))
        .thenExecute(()->{pair[0]=test.spawn(level,1350.5,true);pair[1]=test.spawn(level,1351.2,true);test.friend(level,pair[0],pair[1]);
            helper.assertTrue(pair[0].groupInteraction().challenge(pair[1]),"live bout starts");})
        .thenIdle(210).thenExecute(()->{
            test.check("real goal consumes contact energy",pair[0].life().energy()<87&&pair[1].life().energy()<87);
            test.check("real bout completes with both free",!pair[0].groupInteraction().active()&&!pair[1].groupInteraction().active());
            test.check("default bout causes no injury",pair[0].body().wound()==0&&pair[1].body().wound()==0);
            P0Control.write("group-live-checks",test.checks);clean(level,1344);
            var failed=test.checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
        });
    }
}
