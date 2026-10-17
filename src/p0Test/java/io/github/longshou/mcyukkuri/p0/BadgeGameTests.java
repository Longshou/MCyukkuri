package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.badge.*;
import io.github.longshou.mcyukkuri.pet.*;
import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.life.*;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcPolicy;
import io.github.longshou.mcyukkuri.pet.social.BadgeArcs;
import io.github.longshou.mcyukkuri.pet.social.BadgeBudget;
import io.github.longshou.mcyukkuri.pet.social.BadgeLedger;
import io.github.longshou.mcyukkuri.pet.social.BadgePraise;
import io.github.longshou.mcyukkuri.pet.social.BadgeSocial;
import io.github.longshou.mcyukkuri.settlement.Rumours;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
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
                try { t.setup(l);t.checks(l);t.social(l);t.routines(l);t.routineStarted=l.getGameTime(); }
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
        // The deliberate-greeting path goes through the visible-reaction budget; give this block a roomy one so only the personalities are under test.
        BadgeSocial.installBudget(new BadgeBudget(l::getGameTime,new BadgeBudget.Settings(6000,8,0,0,0,64,6000,8)));
        envious.pet().reactTo(peer);kind.pet().reactTo(peer);
        check("Higher badges provoke envy or admiration according to personality",relations.affinity(envious.getUUID(),peer.getUUID())<0&&relations.affinity(kind.getUUID(),peer.getUUID())>0);
        check("Badge reactions move the relation by one small step",relations.affinity(envious.getUUID(),peer.getUUID())==-1&&relations.affinity(kind.getUUID(),peer.getUUID())==1);
        check("Repeated meetings have a badge reaction cooldown",!envious.pet().reactTo(peer));
        BadgeSocial.reset();
        peer.pet().detach(player);var bystander=create(l,X+9.5);
        check("A hidden badge does not advertise social rank",!bystander.pet().reactTo(peer));
        peer.discard();envious.discard();kind.discard();bystander.discard();
        animal.discard();animal=create(l,X+5.5);
    }
    private void nutrition(YukkuriEntity y,float value) {
        var life=y.life().save();life.putFloat("Nutrition",value);y.life().load(life);
    }
    private int relationsOf(ServerLevel l,YukkuriEntity from,YukkuriEntity to) {return YukkuriWorldData.get(l).affinity(from.getUUID(),to.getUUID());}
    /** Badge social redesign (docs/PET_SHOP.md section 7): ledger, budget, policy, guard rails, real food transfer, scene lifecycle. Deterministic, no AI timing. */
    private void social(ServerLevel l) {
        long game=l.getGameTime();
        // ledger
        var ledger=new BadgeLedger();UUID a=UUID.randomUUID();
        var first=ledger.note(a,2,0);
        check("A first sighting is new",first.first()&&!first.changed()&&!first.lapsed()&&first.entry().lastGrade()==2);
        ledger.impress(a,3,0);ledger.flag(a,BadgeLedger.ENVIED,0);
        var again=ledger.note(a,2,100);
        check("Seeing the same badge again is old news",!again.first()&&!again.changed()&&!again.lapsed()&&again.entry().seenCount()==2);
        var lapse=ledger.note(a,0,200);
        check("A badge that is gone is a new fact",lapse.lapsed()&&lapse.changed()&&lapse.previousGrade()==2);
        check("Impression fades one step per game day",ledger.get(a,200+2*24000L).impression()==1);
        check("An entry unseen for five game days is forgotten",ledger.get(a,200+6*24000L)==null);
        var full=new BadgeLedger();UUID keep=UUID.randomUUID();full.note(keep,1,0);full.impress(keep,3,0);
        for(int i=0;i<12;i++)full.note(UUID.randomUUID(),1,10+i);
        check("The ledger holds at most 12 and forgets the least memorable first",full.size()==BadgeLedger.CAPACITY&&full.get(keep,100)!=null);
        var copy=new BadgeLedger();copy.load(full.save());
        check("The ledger survives save and load",copy.size()==full.size()&&copy.get(keep,100)!=null&&copy.get(keep,100).impression()==3);
        var holder=create(l,X+12.5);UUID subjectId=UUID.randomUUID();
        holder.pet().badgeLedger().note(subjectId,2,game);holder.pet().badgeLedger().flag(subjectId,BadgeLedger.FED_BY,game);
        var reborn=create(l,X+13.5);reborn.pet().load(holder.pet().save());
        var remembered=reborn.pet().badgeLedger().get(subjectId,game);
        check("The ledger is saved with the individual",remembered!=null&&remembered.has(BadgeLedger.FED_BY)&&remembered.lastGrade()==2);
        holder.discard();reborn.discard();
        // budget
        long[] clock={0};var budget=new BadgeBudget(()->clock[0],BadgeBudget.Settings.defaults());
        UUID viewer=UUID.randomUUID(),o1=UUID.randomUUID(),o2=UUID.randomUUID(),o3=UUID.randomUUID(),s1=UUID.randomUUID(),s2=UUID.randomUUID();
        check("A fresh bucket stores two tokens near a player and one far from any",Math.abs(budget.tokens(viewer)-2)<1e-6&&Math.abs(budget.tokens(null)-1)<1e-6);
        check("A fresh bucket admits an arc",budget.check(viewer,2,o1,s1)==BadgeBudget.Verdict.OK);
        budget.commit(viewer,2,o1,s1);
        check("The same observer waits ten minutes",budget.check(viewer,1,o1,s2)==BadgeBudget.Verdict.OBSERVER_COOLDOWN);
        check("An empty bucket admits nothing",budget.check(viewer,1,o2,s2)==BadgeBudget.Verdict.NO_TOKENS);
        clock[0]=6000;
        check("One token returns after five minutes, not two",budget.check(viewer,1,o2,s2)==BadgeBudget.Verdict.OK&&budget.check(viewer,2,o2,s2)==BadgeBudget.Verdict.NO_TOKENS);
        budget.commit(viewer,1,o2,s2);
        check("A subject is not the object of two reactions within five minutes",budget.check(viewer,1,o3,s2)==BadgeBudget.Verdict.TARGET_COOLDOWN);
        clock[0]=12000;
        check("A pair waits a game day after an arc",budget.check(viewer,2,o1,s1)==BadgeBudget.Verdict.PAIR_COOLDOWN);
        check("The world bucket is independent of the bucket near a player",budget.check(null,1,o3,s1)==BadgeBudget.Verdict.OK);
        check("A glance is free but at most once a minute",budget.microAllowed(o3)&&!budget.microAllowed(o3));
        clock[0]=13300;check("The glance allowance returns after a minute",budget.microAllowed(o3));
        long[] day={0};var busy=new BadgeBudget(()->day[0],new BadgeBudget.Settings(6000,99,0,0,0,2,6000,99));UUID target=UUID.randomUUID();
        for(int i=0;i<2;i++){UUID observer=UUID.randomUUID();check("Reaction "+(i+1)+" at one subject is admitted",busy.check(viewer,1,observer,target)==BadgeBudget.Verdict.OK);busy.commit(viewer,1,observer,target);}
        check("A subject is the object of at most targetPerDay reactions a game day",busy.check(viewer,1,UUID.randomUUID(),target)==BadgeBudget.Verdict.TARGET_DAILY);
        day[0]=24000;check("The daily count resets the next game day",busy.check(viewer,1,UUID.randomUUID(),target)==BadgeBudget.Verdict.OK);
        // policy (pure)
        var wild=new BadgeArcPolicy.Observer(false,false,false,false,50,50,50,50,50,50,70,10,0,100);
        var petSubject=new BadgeArcPolicy.Subject(2,true,false,true,false,false,false,true,100);
        var firstSight=new BadgeArcPolicy.Relation(0,false,true,false,false,false,0,0,1);
        var context=new BadgeArcPolicy.Context(true,false,false,false,true);
        int arcs=0;for(int i=0;i<1000;i++)if(!BadgeArcPolicy.choose(wild,petSubject,firstSight,context,i/1000.0).silent())arcs++;
        check("A neutral first sighting becomes an arc for a minority of rolls only",arcs>0&&arcs<450);
        check("A high roll leaves a sighting a silent record",BadgeArcPolicy.choose(wild,petSubject,firstSight,context,.95).silent());
        var known=new BadgeArcPolicy.Relation(0,false,false,false,false,false,0,0,3);
        arcs=0;for(int i=0;i<1000;i++)if(!BadgeArcPolicy.choose(wild,petSubject,known,context,i/1000.0).silent())arcs++;
        check("An old acquaintance with no new fact is almost always silent",arcs<50);
        var cooling=new BadgeArcPolicy.Relation(0,false,true,true,false,true,0,0,1);
        check("An arc that just played blocks every new one",BadgeArcPolicy.candidates(wild,petSubject,cooling,context).isEmpty());
        var hungry=new BadgeArcPolicy.Observer(false,false,false,false,50,40,50,50,50,50,40,10,0,100);
        var eating=new BadgeArcPolicy.Subject(1,true,true,true,false,false,false,true,100);
        check("A hungry wild one begs a bite while the pet is really eating",BadgeArcPolicy.candidates(hungry,eating,known,context).stream().anyMatch(c->c.arc()==BadgeArcPolicy.Arc.BEG_BITE));
        check("It does not beg when nothing is being chewed",BadgeArcPolicy.candidates(hungry,petSubject,known,context).stream().noneMatch(c->c.arc()==BadgeArcPolicy.Arc.BEG_BITE));
        var voided=new BadgeArcPolicy.Subject(0,true,false,true,false,false,false,true,100);
        check("A wild individual takes a void or missing badge for no badge at all",BadgeArcPolicy.candidates(wild,voided,firstSight,context).isEmpty());
        var bronze=new BadgeArcPolicy.Subject(1,true,false,true,false,false,false,true,100);
        check("A wild individual reacts to any visible badge without comparing ranks",BadgeArcPolicy.candidates(wild,bronze,firstSight,context).stream().anyMatch(c->c.arc()==BadgeArcPolicy.Arc.GLANCE));
        var petObserver=new BadgeArcPolicy.Observer(true,false,false,false,50,60,50,50,50,50,70,10,2,100);
        var higher=new BadgeArcPolicy.Subject(3,true,false,true,false,false,false,true,100);
        check("A pet boasts to a lower badge",BadgeArcPolicy.candidates(petObserver,bronze,firstSight,context).stream().anyMatch(c->c.arc()==BadgeArcPolicy.Arc.BOAST)
                &&BadgeArcPolicy.candidates(petObserver,bronze,firstSight,context).stream().noneMatch(c->c.arc()==BadgeArcPolicy.Arc.ENVY));
        check("A pet envies a higher badge and does not boast",BadgeArcPolicy.candidates(petObserver,higher,firstSight,context).stream().anyMatch(c->c.arc()==BadgeArcPolicy.Arc.ENVY)
                &&BadgeArcPolicy.candidates(petObserver,higher,firstSight,context).stream().noneMatch(c->c.arc()==BadgeArcPolicy.Arc.BOAST));
        var lapsedRelation=new BadgeArcPolicy.Relation(0,false,false,true,true,false,0,0,3);
        var gentle=new BadgeArcPolicy.Observer(false,false,false,false,50,50,30,30,50,50,70,10,0,100);
        var pity=BadgeArcPolicy.candidates(gentle,voided,lapsedRelation,context);
        check("A remembered badge that is gone calls for pity from a gentle one",pity.size()==1&&pity.getFirst().arc()==BadgeArcPolicy.Arc.PITY&&pity.getFirst().variant().equals("pity"));
        var trusted=new BadgeArcPolicy.Subject(1,true,false,true,true,false,false,true,100);
        double calm=BadgeArcPolicy.candidates(wild,trusted,firstSight,context).stream().filter(c->c.arc()==BadgeArcPolicy.Arc.PLEA).mapToDouble(BadgeArcPolicy.Candidate::probability).sum();
        double wary=BadgeArcPolicy.candidates(wild,trusted,firstSight,new BadgeArcPolicy.Context(true,true,false,false,true)).stream().filter(c->c.arc()==BadgeArcPolicy.Arc.PLEA).mapToDouble(BadgeArcPolicy.Candidate::probability).sum();
        check("A trusted player invites pleas and a wary community makes them rarer",calm>0&&wary>0&&wary<calm);
        // affinity guard rails
        var world=YukkuriWorldData.get(l);UUID x=UUID.randomUUID(),y=UUID.randomUUID();
        for(int i=0;i<30;i++)BadgeSocial.nudge(world,x,y,-1);
        check("Repeated envy stops far short of the bully threshold",world.affinity(x,y)==BadgeSocial.FLOOR&&BadgeSocial.FLOOR>-20);
        for(int i=0;i<30;i++)BadgeSocial.nudge(world,y,x,1);
        check("Repeated admiration stops short of the nuzzle threshold",world.affinity(y,x)==BadgeSocial.CEILING&&BadgeSocial.CEILING<10);
        world.relate(x,y,-15);BadgeSocial.nudge(world,x,y,1);
        check("A step toward zero is always allowed",world.affinity(x,y)==-24);
        world.rollbackEncounter(Set.of(x,y));
        // real food transfer
        var giver=create(l,X+14.5);var taker=create(l,X+15.5);nutrition(giver,50);nutrition(taker,35);
        boolean chewing=giver.life().beginMeal(new ItemStack(ModItems.GRAIN_REGULAR.get()),null,true);
        check("Fixture: the giver is chewing a real bite",chewing&&!giver.life().mouth().isEmpty()&&giver.life().mealTicks()>0);
        check("A begging peer can hold the bite mid-chew",giver.life().holdMeal(100)&&giver.life().mealHeld());
        check("A bite is handed over for real",giver.life().handOverMouth(taker,"badge_share_offer")&&giver.life().mouth().isEmpty()&&!taker.life().mouth().isEmpty()&&taker.life().mealTicks()>0);
        check("Nothing can be handed over twice",!giver.life().handOverMouth(taker,"badge_share_offer")&&!giver.life().mealHeld());
        giver.discard();taker.discard();
        // praise window (B7)
        var praised=create(l,X+16.5);praised.pet().addStress(10);BadgePraise.start(praised,player);
        check("A report opens a praise window",BadgePraise.open(praised));
        praised.pet().contact(UUID.randomUUID(),true);
        check("Care from someone else is not praise",BadgePraise.open(praised)&&praised.pet().stress()==10);
        praised.pet().contact(player.getUUID(),true);
        check("Gentle care from the owner inside the window is praise: stress relief and the window closes",!BadgePraise.open(praised)&&praised.pet().stress()==7);
        praised.discard();
        // rumours
        var settlements=SettlementData.get(l);UUID human=UUID.randomUUID();
        var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,new BlockPos(X,65,0),null);
        Rumours.add(settlements,group,Rumours.WARY_HUMAN,human,1,0);Rumours.add(settlements,group,Rumours.WARY_HUMAN,human,1,0);
        check("Repeated reports strengthen one rumour",group.rumours.size()==1&&Rumours.strength(group,Rumours.WARY_HUMAN,0)==2);
        check("A rumour fades one point a day and lapses after three",Rumours.strength(group,Rumours.WARY_HUMAN,24000)==1&&Rumours.strength(group,Rumours.WARY_HUMAN,Rumours.LIFETIME+1)==0);
        for(int i=0;i<9;i++)Rumours.add(settlements,group,Rumours.BULLY,UUID.randomUUID(),1,0);
        check("A community keeps at most six rumours",group.rumours.size()<=Rumours.MAX);
        // scene lifecycle
        var wildOne=create(l,X+18.5);var badged=create(l,X+20.5);badged.pet().issue(BadgeGrade.SILVER,3,shop.getUUID());
        String why=BadgeSocial.force(l,BadgeArcPolicy.Arc.ENVY,wildOne,badged,player);
        var scene=WildEventData.get(l).forMember(wildOne.getUUID());
        check("An arc starts as a real scene that claims both ("+why+")",why==null&&scene!=null&&scene.kind==WildEventData.Kind.BADGE_ARC&&WildEvents.reserved(wildOne)&&WildEvents.reserved(badged));
        if(scene!=null){
            WildEvents.finish(l,scene,WildEventData.Phase.INTERRUPTED,"test_interrupted");
            var trace=wildOne.pet().badgeLedger().get(badged.getUUID(),l.getGameTime());
            check("An interrupted arc changes no relation, flag or impression",trace!=null&&trace.flags()==0&&trace.impression()==0&&relationsOf(l,wildOne,badged)==0);
            check("The participants are released",!WildEvents.reserved(wildOne)&&!WildEvents.reserved(badged));
        }
        var settled=new WildEventData.Scene(UUID.randomUUID(),WildEventData.Kind.BADGE_ARC,"envy",net.minecraft.core.GlobalPos.of(l.dimension(),wildOne.blockPosition()),List.of(wildOne.getUUID(),badged.getUUID()));
        settled.result="sulk|cold";
        BadgeArcs.settle(l,settled,WildEventData.Phase.COMPLETE,"envy_cold");
        var envied=wildOne.pet().badgeLedger().get(badged.getUUID(),l.getGameTime());
        check("A settled envy arc is remembered: flag, impression and a small step down in affinity",envied!=null&&envied.has(BadgeLedger.ENVIED)&&envied.impression()==-1&&relationsOf(l,wildOne,badged)==-1
                &&"envy".equals(envied.lastArc()));
        var sharing=new WildEventData.Scene(UUID.randomUUID(),WildEventData.Kind.BADGE_ARC,"beg_bite",net.minecraft.core.GlobalPos.of(l.dimension(),wildOne.blockPosition()),List.of(wildOne.getUUID(),badged.getUUID()));
        sharing.result="shared";
        BadgeArcs.settle(l,sharing,WildEventData.Phase.COMPLETE,"shared");
        var fed=wildOne.pet().badgeLedger().get(badged.getUUID(),l.getGameTime());
        check("A shared bite is remembered by both sides",fed!=null&&fed.has(BadgeLedger.FED_BY)&&badged.pet().badgeLedger().get(wildOne.getUUID(),l.getGameTime()).has(BadgeLedger.HELPED));
        wildOne.discard();badged.discard();
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
