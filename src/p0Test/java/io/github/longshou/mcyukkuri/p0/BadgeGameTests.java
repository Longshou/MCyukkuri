package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.badge.*;
import io.github.longshou.mcyukkuri.pet.*;
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
    private long routineStarted;
    private void check(String name, boolean pass) { checks.add(new Check(name,pass)); }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1600,batch="badges")
    public static void badgeSystem(GameTestHelper h) {
        var t=new BadgeGameTests();var l=h.getLevel();
        t.force(l,true);
        h.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(h,X>>4,(X+24)>>4,0,0))
            .thenExecute(()->{
                try { t.setup(l);t.checks(l);t.routines(l);t.routineStarted=l.getGameTime(); }
                catch(Throwable e) {t.check("Setup exception: "+e,false);}
            // Daily routine checks keep their original minimum observation period.
            }).thenWaitUntil(()->h.assertTrue(l.getGameTime()-t.routineStarted>=1000,"Observing actual daily routines"))
            .thenExecute(()->{
                try {
                    t.check("Actual bowl meal contributes once",t.routines.pet().routine(0)==1);
                    t.check("Actual toilet use contributes only after waste transfer",t.routines.pet().routine(1)>=1);
                    t.check("A completed natural rest contributes evidence",t.routines.pet().routine(2)>=1);
                } catch(Throwable e) {t.check("Routine exception: "+e,false);}
                t.finish(h,"badge-checks");
            });
    }
    private void force(ServerLevel level,boolean force) {
        for(int x=X>>4;x<=(X+24)>>4;x++)GameTestChunks.setForced(level,x,0,force);
    }
    private void finish(GameTestHelper helper,String report) {
        P0Control.write(report,checks);
        var level=helper.getLevel();
        level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof HumanNpc||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
        if(player!=null)player.discard();force(level,false);
        var failed=checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
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
    private int money() {return player.getInventory().items.stream().filter(s->s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();}
    private void checks(ServerLevel l) {
        check("Fixture has a real shopkeeper",shop.job()==HumanNpc.Job.SHOPKEEPER);
        var b=animal.pet();
        check("Companion fixture is a registered pet owned by the player",b.isPet()&&b.ownedBy(player.getUUID()));
        check("Blank gold token cannot grant qualification",!b.attach(player,new ItemStack(HumanWorld.GOLD_BADGE.get()))&&b.grade()==BadgeGrade.NONE);
        b.issue(BadgeGrade.SILVER,3,shop.getUUID());
        check("Issued silver records source, issuer and a serial",b.grade()==BadgeGrade.SILVER&&b.source()==3&&shop.getUUID().equals(b.issuer())&&b.serial()!=null&&b.worn());
        var token=YukkuriWorldData.get(l).capture(animal);var stored=YukkuriWorldData.get(l).captiveSnapshot(token);
        check("Capture keeps the certificate and original individual",stored.getUUID("UUID").equals(animal.getUUID())&&stored.getCompound("YukkuriPet").getInt("Grade")==2);
        var released=YukkuriWorldData.get(l).release(token,l,new net.minecraft.world.phys.Vec3(X+8,65,5),0);
        check("Release keeps qualification and wearing state",released!=null&&released.pet().grade()==BadgeGrade.SILVER&&released.pet().worn());
        if(released!=null){animal.discard();animal=released;b=animal.pet();}
        check("Pet state survives save and load",roundTrip(b));
        check("Learning applies bounded multiplier",b.learn(Ability.WITS,10)>0&&b.ability(Ability.WITS)<=100);
        double chance=b.successChance(Ability.WITS,BadgeGrade.GOLD);check("Success chance stays within bounds",chance>=.05&&chance<=.95);
        var legacy=new CompoundTag();legacy.putIntArray("Skills",new int[]{40,50,60});legacy.putIntArray("Routines",new int[]{3,3,2});legacy.putInt("Source",2);
        var migrated=create(l,X+10.5);migrated.pet().migrateLegacy(legacy,BadgeGrade.GOLD);var mp=migrated.pet();
        check("Legacy skills migrate to academics, wits and poise",mp.ability(Ability.ACADEMICS)==40&&mp.ability(Ability.WITS)==50&&mp.ability(Ability.POISE)==60&&mp.ability(Ability.MANNERS)==80);
        check("Legacy shop stock keeps source and default pedigree",mp.grade()==BadgeGrade.GOLD&&mp.source()==2&&mp.pedigree()==60);migrated.discard();
        HumanCommerce.assign(animal,UUID.randomUUID(),"companion");
        check("Wrong owner cannot detach",!b.detach(player));
        HumanCommerce.assign(animal,player.getUUID(),"companion");
        var menu=new BadgeMenu(2,player.getInventory(),animal.getId());
        check("Forged menu action rejected",!menu.clickMenuButton(player,99));
        check("Certified silver can be detached",b.detach(player)&&b.grade()==BadgeGrade.SILVER&&b.visibleGrade()==BadgeGrade.NONE);
        var badge=player.getInventory().items.stream().filter(s->s.is(HumanWorld.SILVER_BADGE.get())).findFirst().orElseThrow();var old=badge.copy();
        var other=create(l,X+6.5);other.pet().issue(BadgeGrade.SILVER,3,shop.getUUID());other.pet().detach(player);
        check("Physical badge cannot transfer qualification to another identity",!other.pet().attach(player,badge));other.discard();
        var fresh=b.reissueToken();
        check("Reissue invalidates old serial",!b.attach(player,old)&&!b.worn());
        check("Current identity-bound token can be reattached",b.attach(player,fresh)&&b.visibleGrade()==BadgeGrade.SILVER);
        var duplicate=fresh.copy();check("Copied token cannot attach twice",!b.attach(player,duplicate));
        var accessory=animal.life().takeAccessory(player.getUUID());check("Removing accessory hides badge, keeps certification",b.visibleGrade()==BadgeGrade.NONE&&b.grade()==BadgeGrade.SILVER);
        check("Returning exact accessory restores wearing state",animal.life().returnAccessory(animal.getUUID(),accessory,player.getUUID())&&b.visibleGrade()==BadgeGrade.SILVER);
        var detachedAccessory=new ItemStack(ModItems.RIBBON.get());b.markAccessory(detachedAccessory);
        var accessoryTag=detachedAccessory.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA).copyTag();
        check("Accessory item retains its attached badge appearance",accessoryTag.getInt("BadgeTier")==2&&accessoryTag.getBoolean("BadgeGradeV2"));
        accessory=animal.life().takeAccessory(player.getUUID());b.reissueToken();
        animal.life().returnAccessory(animal.getUUID(),accessory,player.getUUID());
        check("Returning an obsolete accessory cannot re-equip its invalid badge",b.visibleGrade()==BadgeGrade.NONE);
        b.issue(BadgeGrade.GOLD,3,shop.getUUID());
        other=create(l,X+6.5);other.life().takeAccessory(player.getUUID());
        check("Gold recognition survives detached badge",b.detach(player)&&animal.life().canRecognize(other));other.discard();
        var peer=create(l,X+6.5);peer.pet().initializeStock(BadgeGrade.SILVER,shop.getUUID(),60);
        var envious=create(l,X+7.5);var kind=create(l,X+8.5);
        temperament(envious,20,80);temperament(kind,80,20);
        var relations=YukkuriWorldData.get(l);
        envious.pet().reactTo(peer);kind.pet().reactTo(peer);
        check("Higher badges provoke envy or admiration according to personality",relations.affinity(envious.getUUID(),peer.getUUID())<0&&relations.affinity(kind.getUUID(),peer.getUUID())>0);
        check("Repeated meetings have a badge reaction cooldown",!envious.pet().reactTo(peer));
        peer.pet().detach(player);var bystander=create(l,X+9.5);
        check("A hidden badge does not advertise social rank",!bystander.pet().reactTo(peer));
        peer.discard();envious.discard();kind.discard();bystander.discard();
        animal.discard();animal=create(l,X+5.5);
    }
    private boolean roundTrip(PetLife b) {
        var grade=b.grade();var serial=b.serial();var saved=b.save();b.load(saved);return b.grade()==grade&&java.util.Objects.equals(serial,b.serial());
    }
    private void temperament(YukkuriEntity y,int care,int bold) {
        var life=y.life().save();var p=life.getCompound("Personality");PersonalityFixtures.preferences(p,new int[]{50,50,bold,care,50,50});
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
