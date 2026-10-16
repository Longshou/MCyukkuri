package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.social.DailyLife;
import io.github.longshou.mcyukkuri.social.EcologyPresentation;
import java.util.ArrayList;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

@net.neoforged.neoforge.gametest.PrefixGameTestTemplate(false)
public final class EcologyGameTests {
    private record Check(String name, boolean pass) {}
    private final ArrayList<Check> checks = new ArrayList<>();
    private void check(String name, boolean pass) { checks.add(new Check(name,pass)); }
    static void floor(ServerLevel level, int x) {
        level.setDayTime(6000);level.setWeatherParameters(24000,0,false,false);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        for(int chunk=x>>4;chunk<=(x+31)>>4;chunk++)GameTestChunks.setForced(level,chunk,0,true);
        for(int dx=x;dx<x+32;dx++)for(int z=0;z<14;z++){
            level.setBlockAndUpdate(new BlockPos(dx,64,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
            for(int y=65;y<71;y++)level.setBlockAndUpdate(new BlockPos(dx,y,z),Blocks.AIR.defaultBlockState());
        }
    }
    static YukkuriEntity spawn(ServerLevel level,double x,double z) {
        return spawn(level,x,z,io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU);
    }
    static YukkuriEntity spawn(ServerLevel level,double x,double z,io.github.longshou.mcyukkuri.entity.YukkuriVariant variant) {
        var e=ModEntities.forVariant(variant).create(level);e.moveTo(x,65,z,180,0);e.setOnGround(true);e.setDeltaMovement(0,-.08,0);e.setPersistenceRequired();
        e.setYHeadRot(180);e.yBodyRot=e.yBodyRotO=180;
        var life=e.life().save();life.putFloat("Nutrition",95);life.putFloat("Energy",95);e.life().load(life);
        var family=e.family().save();family.putBoolean("Initialized",true);e.family().load(family);
        level.addFreshEntity(e);return e;
    }
    static void friends(ServerLevel level,YukkuriEntity a,YukkuriEntity b) {
        var world=YukkuriWorldData.get(level);world.relate(a.getUUID(),b.getUUID(),15);world.relate(b.getUUID(),a.getUUID(),15);
    }
    private static void reset(YukkuriEntity e) { e.dailyLife().load(new CompoundTag()); }
    private static String state(YukkuriEntity e) {
        return e.position()+" ground="+e.onGround()+" alive="+e.isAlive()+" life="+e.life().activity()+" family="+e.family().active()
            +" habitat="+e.habitat().active()+" daily="+e.dailyLife().save()+" social="+e.social().activity()+" behavior="+e.getBehaviorId()+" health="+e.getHealth();
    }
    private static void clean(ServerLevel level,int x) {
        level.getEntitiesOfClass(Entity.class,new AABB(x,63,0,x+32,75,14),e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
        for(int chunk=x>>4;chunk<=(x+31)>>4;chunk++)GameTestChunks.setForced(level,chunk,0,false);
    }
    private void finish(GameTestHelper helper,String report,int x) {
        P0Control.write(report,checks);clean(helper.getLevel(),x);
        var failed=checks.stream().filter(c->!c.pass).toList();
        if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=500,batch="ecology_rules")
    public static void rules(GameTestHelper helper) {
        var test=new EcologyGameTests();var level=helper.getLevel();floor(level,2048);
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,2048>>4,(2048+31)>>4,0,0))
            .thenExecute(()->{try{test.rules(level);}catch(Throwable e){test.check("unexpected: "+e,false);}test.finish(helper,"ecology-rules",2048);});
    }
    private void rules(ServerLevel level) {
        var a=spawn(level,2050.5,5.5);var b=spawn(level,2051.22,5.5);var c=spawn(level,2053,5.5);
        check("unknown peers cannot nuzzle",!a.dailyLife().nuzzle(b));friends(level,a,b);friends(level,c,b);
        var needs=a.dailyLife().save();needs.putInt("Affection",85);a.dailyLife().load(needs);
        check("friends reserve both participants",a.dailyLife().nuzzle(b)&&b.dailyLife().active());
        check("third party cannot take partner",!c.dailyLife().nuzzle(b));
        float nutrition=a.life().nutrition();float hygiene=a.life().hygiene();int before=a.dailyLife().affectionNeed();
        for(int i=0;i<20;i++)a.dailyLife().tickGoal();
        check("early contact gives no reward",a.dailyLife().affectionNeed()==before&&a.dailyLife().nuzzles()==0);
        a.dailyLife().stop();check("interruption releases both",!a.dailyLife().active()&&!b.dailyLife().active()&&a.dailyLife().nuzzlePartnerId()==-1&&b.dailyLife().nuzzlePartnerId()==-1);
        check("interruption keeps cooldown",!a.dailyLife().nuzzle(b));reset(a);reset(b);
        check("restart after reset",a.dailyLife().nuzzle(b));
        for(int i=0;i<60;i++)a.dailyLife().tickGoal();
        check("completed contact counts exactly once",a.dailyLife().nuzzles()==1&&b.dailyLife().nuzzles()==1);
        check("affection is separate from food and hygiene",a.life().nutrition()==nutrition&&a.life().hygiene()==hygiene&&a.dailyLife().affectionNeed()==0);
        a.dailyLife().tickGoal();check("finished session cannot pay twice",a.dailyLife().nuzzles()==1);
        var saved=a.dailyLife().save();a.dailyLife().load(saved);
        check("needs and completion persist without replay",a.dailyLife().nuzzles()==1&&!a.dailyLife().active()&&a.dailyLife().affectionNeed()==0);
        reset(a);reset(b);b.life().takeAccessory(null);
        check("lost accessory prevents friend recognition",!a.dailyLife().nuzzle(b));
        a.pet().issue(io.github.longshou.mcyukkuri.pet.BadgeGrade.GOLD,1,null);check("gold recognition preserves identity",a.dailyLife().nuzzle(b));
        var token=YukkuriWorldData.get(level).capture(b);a.dailyLife().clock();
        check("capture releases partner without reward",token!=null&&!a.dailyLife().active()&&a.dailyLife().nuzzles()==0);
        reset(a);var far=spawn(level,2060,5.5);friends(level,a,far);
        check("distant contact rejected",!a.dailyLife().nuzzle(far));
        var young=spawn(level,2057.5,8.5);young.family().generatedChild(a,null);
        var state=young.life().save();state.putFloat("Dryness",80);state.putFloat("Bladder",75);young.life().load(state);
        check("dry youngster starts constipated",young.life().constipated());
        check("exercise can start while constipated",young.dailyLife().begin(DailyLife.Kind.EXERCISE));
        for(int i=0;i<279;i++)young.dailyLife().tickGoal();
        check("incomplete exercise does not relieve constipation",young.life().constipated());young.dailyLife().tickGoal();
        check("completed young exercise relieves without hydration or waste removal",!young.life().constipated()&&young.life().dryness()==80&&young.life().bladder()==75&&young.dailyLife().exercises()==1);
        var adultState=a.life().save();adultState.putFloat("Dryness",80);adultState.putFloat("Bladder",75);a.life().load(adultState);a.life().finishExercise();
        check("adult keeping time does not cure constipation",a.life().constipated()&&a.life().exerciseReliefTicks()==0);
        var relief=young.life().save();young.life().load(relief);
        check("temporary exercise relief survives reload",!young.life().constipated()&&young.life().exerciseReliefTicks()==1200);
        for(int i=0;i<1200;i++)young.life().tick();check("relief expires in loaded time",young.life().constipated());
        reset(far);check("solo song starts",far.dailyLife().begin(DailyLife.Kind.SING));
        for(int i=0;i<160;i++)far.dailyLife().tickGoal();check("solo song completion relieves boredom",far.dailyLife().songs()==1&&far.dailyLife().boredom()==0);
        var invalid=new CompoundTag();invalid.putInt("Affection",999);invalid.putInt("Boredom",-1);invalid.putInt("Cooldown",999999);far.dailyLife().load(invalid);
        check("saved need bounds validated",far.dailyLife().affectionNeed()==100&&far.dailyLife().boredom()==0);
        var wild=spawn(level,2072.5,5.5);var wildLife=wild.life().save();wildLife.putFloat("Bladder",95);wildLife.putFloat("Nutrition",65);wild.life().load(wildLife);
        int wasteBefore=level.getEntitiesOfClass(ItemEntity.class,wild.getBoundingBox().inflate(3),e->e.getItem().is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get())).size();
        check("wild toilet plans before any reward",wild.life().plan()&&wild.life().bladder()==95);
        for(int i=0;i<39;i++)wild.life().tickGoal();check("no instant waste on planning",wild.life().bladder()==95);
        wild.life().tickGoal();
        // addFreshEntity commits during this tick; the entity query index is visible on the next world tick.
        // The live test below checks the physical portion after the engine has processed that insertion.
        check("wild bowel settles once after full motion",wild.life().bladder()==55);
        check("deliberate outdoor toilet does not soil self",wild.life().hygiene()==100);
        check("exercise and excretion use distinct clips",EcologyPresentation.action("unun_exercise")!=EcologyPresentation.action("ground_toilet"));
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="ecology_live")
    public static void live(GameTestHelper helper) {
        var test=new EcologyGameTests();var level=helper.getLevel();floor(level,2112);var pair=new YukkuriEntity[2];var seen=new HashSet<String>();
        helper.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(helper,2112>>4,(2112+31)>>4,0,0))
            .thenExecute(()->{pair[0]=spawn(level,2115.5,5.5);pair[1]=spawn(level,2116.22,5.5);friends(level,pair[0],pair[1]);
                helper.assertTrue(pair[0].dailyLife().nuzzle(pair[1]),"live nuzzle starts "+state(pair[0])+" OTHER "+state(pair[1]));
                helper.onEachTick(()->{if(pair[0]!=null){seen.add(pair[0].getBehaviorId());seen.add("face:"+pair[0].getExpressionId());
                    if(pair[0].getBehaviorId().equals("nuzzle")&&pair[0].nuzzlePartnerId()==pair[1].getId()&&pair[1].nuzzlePartnerId()==pair[0].getId())seen.add("bound_partners");}});})
            .thenIdle(90).thenExecute(()->{
                test.check("real AI completes mutual nuzzle: "+pair[0].getBehaviorId()+"/"+pair[1].getBehaviorId()+" counts="+pair[0].dailyLife().nuzzles()+"/"+pair[1].dailyLife().nuzzles(),pair[0].dailyLife().nuzzles()==1&&pair[1].dailyLife().nuzzles()==1);
                test.check("synced nuzzle has relaxed smile and real partner IDs: "+seen,seen.contains("nuzzle")&&seen.contains("face:closed_smile")&&seen.contains("bound_partners"));
                pair[1].discard();pair[1]=spawn(level,2123.5,8.5);pair[1].family().generatedChild(pair[0],null);
                var state=pair[1].life().save();state.putFloat("Dryness",80);state.putFloat("Bladder",75);pair[1].life().load(state);
                helper.assertTrue(pair[1].dailyLife().begin(DailyLife.Kind.EXERCISE),"live exercise starts");
            }).thenIdle(290).thenExecute(()->{
                test.check("real AI finishes young exercise: "+pair[1].getBehaviorId()+" count="+pair[1].dailyLife().exercises(),pair[1].dailyLife().exercises()==1&&!pair[1].life().constipated());
                pair[1].discard();pair[1]=spawn(level,2138.5,5.5);
                var state=pair[1].life().save();state.putFloat("Bladder",95);pair[1].life().load(state);
            }).thenIdle(100).thenExecute(()->{
                var waste=level.getEntitiesOfClass(ItemEntity.class,pair[1].getBoundingBox().inflate(5),e->e.getItem().is(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get()));
                test.check("real wild toilet emits one physical portion",pair[1].life().bladder()==55&&waste.size()==1&&waste.getFirst().getItem().getCount()==1);
                test.finish(helper,"ecology-live",2112);
            });
    }
}
