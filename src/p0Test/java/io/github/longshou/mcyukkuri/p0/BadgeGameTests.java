package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.badge.*;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.life.*;
import io.github.longshou.mcyukkuri.registry.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class BadgeGameTests {
    private record Check(String name, boolean pass) {}
    private final List<Check> checks = new ArrayList<>();
    private static final int X=2320;
    private static final AABB AREA=new AABB(X-2,63,-2,X+24,74,16);
    private YukkuriEntity animal;
    private YukkuriEntity routines;
    private HumanNpc shop;
    private FakePlayer player;
    private void check(String name, boolean pass) { checks.add(new Check(name,pass)); }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1600,batch="badges")
    public static void badgeSystem(GameTestHelper h) {
        var t=new BadgeGameTests();var l=h.getLevel();
        for(int x=X>>4;x<=(X+24)>>4;x++) l.setChunkForced(x,0,true);
        h.startSequence().thenWaitUntil(()->h.assertTrue(l.isPositionEntityTicking(new BlockPos(X+5,65,5)),"Waiting for badge arena"))
            .thenExecute(()->{
                try { t.setup(l);t.checks(l);t.liveExam(l);t.routines(l); }
                catch(Throwable e) {t.check("Setup exception: "+e,false);}
            }).thenExecuteAfter(500,()->{
                try {
                    t.check("Actual goal navigation reaches the examiner's marker and grants silver",t.animal.life().medal()==1&&t.animal.badges().result()==1&&t.animal.badges().score(4)==100);
                    t.check("Exam certificate records examiner and completed attempt",t.animal.badges().source()==3&&t.animal.badges().attempts()==1&&t.animal.badges().save().getUUID("Issuer").equals(t.shop.getUUID()));
                    var token=YukkuriWorldData.get(l).capture(t.animal);var stored=YukkuriWorldData.get(l).captiveSnapshot(token);
                    t.check("Capture keeps the certificate and original individual",stored.getUUID("UUID").equals(t.animal.getUUID())&&stored.getCompound("YukkuriBadges").getInt("Source")==3);
                    var released=YukkuriWorldData.get(l).release(token,l,new net.minecraft.world.phys.Vec3(X+8,65,5),0);
                    t.check("Release keeps qualification and wearing state",released!=null&&released.life().medal()==1&&released.badges().worn());
                    t.animal=released;t.trained(released,90);
                    t.check("Live gold upgrade is admitted",released.badges().exam(t.player,t.shop,2));
                } catch(Throwable e) {t.check("Live exception: "+e,false);}
            }).thenExecuteAfter(500,()->{
                try {
                    t.check("Live gold exam completes at the higher thresholds",t.animal.life().medal()==2&&t.animal.badges().result()==1&&t.animal.badges().score(4)==100);
                    t.check("Actual bowl meal contributes once",t.routines.badges().routine(0)==1);
                    t.check("Actual toilet use contributes only after waste transfer",t.routines.badges().routine(1)>=1);
                    t.check("A completed natural rest contributes evidence",t.routines.badges().routine(2)>=1);
                } catch(Throwable e) {t.check("Routine exception: "+e,false);}
                P0Control.write("badge-checks",t.checks);
                l.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof HumanNpc||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
                if(t.player!=null)t.player.discard();
                for(int x=X>>4;x<=(X+24)>>4;x++)l.setChunkForced(x,0,false);
                var failed=t.checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())h.succeed();else h.fail(failed.toString());
            });
    }
    private YukkuriEntity create(ServerLevel l,double x) {
        var y=ModEntities.REIMU.get().create(l);y.moveTo(x,65,5.5,0,0);y.setOnGround(true);y.setPersistenceRequired();
        var life=y.life().save();life.putFloat("Nutrition",80);life.putFloat("Energy",90);y.life().load(life);
        l.addFreshEntity(y);HumanCommerce.assign(y,player.getUUID(),"companion");return y;
    }
    private void setup(ServerLevel l) {
        l.setDayTime(6000);l.updateSkyBrightness();l.setWeatherParameters(60000,0,false,false);
        l.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        for(int x=X;x<X+24;x++)for(int z=0;z<14;z++) {
            l.setBlock(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=65;y<74;y++)l.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
        player=FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"BadgeExam"));l.addNewPlayer(player);
        player.moveTo(X+5,65,7.5);player.getInventory().clearContent();player.getInventory().add(new ItemStack(Items.EMERALD,64));
        shop=HumanWorld.BROTHER.get().create(l);shop.moveTo(X+5,65,4);shop.setJob(HumanNpc.Job.SHOPKEEPER);
        var state=new CompoundTag();shop.addAdditionalSaveData(state);state.putInt("HumanJob",1);shop.readAdditionalSaveData(state);
        l.addFreshEntity(shop);shop.setNoAi(true);
        animal=create(l,X+5.5);
    }
    private void trained(YukkuriEntity y,int score) {
        var t=y.badges().save();t.putIntArray("Skills",new int[]{score,score,score});t.putIntArray("Routines",new int[]{3,3,2});
        t.putInt("Cooldown",0);t.putInt("Attempts",0);t.putInt("Result",0);y.badges().load(t);
        for(int i=0;i<3;i++)y.life().personality().lessonCompleted(player.getUUID());
    }
    private int money() {return player.getInventory().items.stream().filter(s->s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();}
    private void checks(ServerLevel l) {
        check("Fixture has a real shopkeeper",shop.job()==HumanNpc.Job.SHOPKEEPER);
        var b=animal.badges();
        check("Blank gold token cannot grant qualification",!b.attach(player,new ItemStack(HumanWorld.GOLD_BADGE.get()))&&animal.life().medal()==0);
        check("Training starts without instantly granting skill",b.train(player,0)&&b.skill(0)==0);
        check("Repeated training packet cannot restart or stack a session",!b.train(player,0));
        for(int i=0;i<299;i++)b.tick();
        check("Training only awards after its full duration",b.skill(0)==0);b.tick();
        check("Completed training grants one bounded increment and no grade",b.skill(0)==10&&!b.active()&&animal.life().medal()==0);
        check("Training cooldown survives save/load",b.save().getInt("Cooldown")==100);
        var snapshot=b.save();b.load(snapshot);check("Skill progression survives reload",b.skill(0)==10&&!b.train(player,0));
        for(int i=0;i<100;i++)b.tick();b.train(player,1);snapshot=b.save();b.load(snapshot);
        check("Reload interrupts unfinished session without free skill",!b.active()&&b.result()==2&&b.skill(1)==0&&b.cooldown()==1200);
        trained(animal,90);int before=money();l.setDayTime(18000);l.updateSkyBrightness();
        check("Night exam refused without charge",!b.exam(player,shop,1)&&money()==before);l.setDayTime(6000);l.updateSkyBrightness();
        HumanCommerce.assign(animal,UUID.randomUUID(),"companion");
        check("Wrong owner cannot train, examine or detach",!b.train(player,0)&&!b.exam(player,shop,1)&&!b.detach(player)&&money()==before);
        HumanCommerce.assign(animal,player.getUUID(),"companion");
        var menu=new BadgeMenu(2,player.getInventory(),shop.getId(),animal.getId());
        check("Forged menu action and absent funds rejected",!menu.clickMenuButton(player,99));
        var cash=player.getInventory().items.stream().filter(s->s.is(Items.EMERALD)).findFirst().orElseThrow();int amount=cash.getCount();cash.setCount(0);
        check("Insufficient money cannot create a session",!b.exam(player,shop,1)&&!b.active());cash.setCount(amount);
        check("Exam starts once and charges once",b.exam(player,shop,1)&&money()==before-3);
        check("Repeated exam packet cannot duplicate a payment",!b.exam(player,shop,1)&&money()==before-3);
        var other=create(l,X+6.5);trained(other,90);
        check("Examiner cannot assess two animals concurrently",!other.badges().exam(player,shop,1));other.discard();
        player.moveTo(X+20,65,5);b.tick();
        check("Leaving aborts examination without certificate or refund",!b.active()&&b.result()==2&&animal.life().medal()==0&&money()==before-3);
        player.moveTo(X+5,65,7.5);trained(animal,20);b.exam(player,shop,1);for(int i=0;i<200;i++)b.tick();
        check("Living knowledge deficiency is reported and cannot be rerolled",b.result()==11&&b.score(1)==20&&animal.life().medal()==0&&!b.exam(player,shop,1));
        animal.life().setMedal(1);trained(animal,60);b.exam(player,shop,2);for(int i=0;i<200;i++)b.tick();
        check("Failed gold upgrade preserves existing silver",b.result()==11&&animal.life().medal()==1);
        var raw=b.save();raw.putInt("Cooldown",0);b.load(raw);check("Certified silver can be detached",b.detach(player)&&animal.life().medal()==1&&b.visibleTier()==0);
        var badge=player.getInventory().items.stream().filter(s->s.is(HumanWorld.SILVER_BADGE.get())).findFirst().orElseThrow();var old=badge.copy();
        other=create(l,X+6.5);other.life().setMedal(1);var otherTag=other.badges().save();otherTag.putBoolean("Worn",false);other.badges().load(otherTag);
        check("Physical badge cannot transfer qualification to another identity",!other.badges().attach(player,badge));other.discard();
        check("Only examiner can reissue",!b.reissue(player,null));before=money();check("Reissue debits exactly one emerald",b.reissue(player,shop)&&money()==before-1);
        check("Reissue invalidates old serial",!b.attach(player,old));
        var replacement=player.getInventory().items.stream().filter(s->s.is(HumanWorld.SILVER_BADGE.get())&&!ItemStack.isSameItemSameComponents(s,old)).findFirst().orElseThrow();var duplicate=replacement.copy();
        check("Current identity-bound token can be reattached",b.attach(player,replacement)&&b.visibleTier()==1);
        check("Copied token cannot attach twice",!b.attach(player,duplicate));
        var accessory=animal.life().takeAccessory(player.getUUID());check("Removing accessory hides badge, keeps certification",b.visibleTier()==0&&animal.life().medal()==1);
        check("Returning exact accessory restores wearing state",animal.life().returnAccessory(animal.getUUID(),accessory,player.getUUID())&&b.visibleTier()==1);
        var detachedAccessory=new ItemStack(ModItems.RIBBON.get());b.markAccessory(detachedAccessory);
        check("Accessory item retains its attached badge appearance",detachedAccessory.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag().getInt("BadgeTier")==1);
        accessory=animal.life().takeAccessory(player.getUUID());
        check("A badge lost with the whole accessory can be reissued",b.reissue(player,shop)&&!b.worn()&&animal.life().medal()==1);
        animal.life().returnAccessory(animal.getUUID(),accessory,player.getUUID());
        check("Returning an obsolete accessory cannot re-equip its invalid badge",b.visibleTier()==0);
        var life=animal.life().save();life.putBoolean("GoldMedal",true);animal.life().load(life);b.load(new CompoundTag());
        check("Legacy gold loads as unreviewed rather than fabricated examination",b.source()==1&&b.attempts()==0&&animal.life().hasGoldMedal());
        other=create(l,X+6.5);other.life().takeAccessory(player.getUUID());
        check("Gold recognition survives detached badge",b.detach(player)&&animal.life().canRecognize(other));other.discard();
        var peer=create(l,X+6.5);peer.badges().initializeStock(2,shop.getUUID());
        var envious=create(l,X+7.5);var kind=create(l,X+8.5);
        temperament(envious,20,80);temperament(kind,80,20);
        var relations=YukkuriWorldData.get(l);
        envious.badges().reactTo(peer);kind.badges().reactTo(peer);
        check("Higher badges provoke envy or admiration according to personality",relations.affinity(envious.getUUID(),peer.getUUID())<0&&relations.affinity(kind.getUUID(),peer.getUUID())>0);
        check("Repeated meetings have a badge reaction cooldown",!envious.badges().reactTo(peer));
        peer.badges().detach(player);var bystander=create(l,X+9.5);
        check("A hidden badge does not advertise social rank",!bystander.badges().reactTo(peer));
        peer.discard();envious.discard();kind.discard();bystander.discard();
        animal.discard();animal=create(l,X+5.5);
    }
    private void liveExam(ServerLevel l) {
        trained(animal,90);shop.setNoAi(false);check("Live exam is admitted",animal.badges().exam(player,shop,1));
        // The subsequent 500 real server ticks exercise the goal, collision, path and sync pipeline.
    }
    private void temperament(YukkuriEntity y,int care,int bold) {
        var life=y.life().save();var p=life.getCompound("Personality");p.putIntArray("Innate",new int[]{50,50,bold,care,50,50});
        p.putIntArray("Experience",new int[6]);life.put("Personality",p);y.life().load(life);
    }
    private void routines(ServerLevel l) {
        routines=create(l,X+16.5);
        var bowl=new BlockPos(X+16,65,6);var toilet=new BlockPos(X+17,65,6);var nest=new BlockPos(X+19,65,6);
        l.setBlockAndUpdate(bowl,ModBlocks.FOOD_BOWL.get().defaultBlockState());
        l.setBlockAndUpdate(toilet,ModBlocks.TOILET.get().defaultBlockState());
        l.setBlockAndUpdate(nest,ModBlocks.NEST.get().defaultBlockState());
        ((CareBlockEntity)l.getBlockEntity(bowl)).setItem(0,new ItemStack(ModItems.GRAIN_REGULAR.get()));
        for(int x=X+15;x<=X+21;x++)for(int z=3;z<=8;z++)l.setBlock(new BlockPos(x,69,z),Blocks.OAK_PLANKS.defaultBlockState(),2);
        var life=routines.life().save();life.putFloat("Nutrition",60);life.putFloat("Energy",20);life.putFloat("Bladder",60);life.putInt("RestWait",0);routines.life().load(life);
        check("Routine fixture owns a real nest",routines.life().assignHome(net.minecraft.core.GlobalPos.of(l.dimension(),nest)));
    }
}
