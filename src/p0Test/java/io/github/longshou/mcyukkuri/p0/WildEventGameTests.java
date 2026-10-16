package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.event.WildCards;
import io.github.longshou.mcyukkuri.event.WildEventData;
import io.github.longshou.mcyukkuri.event.WildEventData.Kind;
import io.github.longshou.mcyukkuri.event.WildEventData.Phase;
import io.github.longshou.mcyukkuri.event.WildEventData.Scene;
import io.github.longshou.mcyukkuri.event.WildEvents;
import io.github.longshou.mcyukkuri.event.RiverEncounter;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.settlement.SettlementData;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterPlacement;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class WildEventGameTests {
    private record Check(String name,boolean pass,String detail) {}
    private final List<Check> checks=new ArrayList<>();
    private final List<YukkuriEntity> actors=new ArrayList<>();
    private ServerLevel level;
    private FakePlayer player,otherPlayer;
    private BlockPos center;
    private int slot;
    private YukkuriEntity first,second;
    private Scene scene;
    private HatBoatEntity liveBoat;
    private int fixtureAbility=100;
    private static final int X=6144, SLOTS=56;
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=12000,batch="wild_events")
    public static void acceptance(GameTestHelper helper) {
        var t=new WildEventGameTests();t.level=helper.getLevel();t.setup();
        var sequence=helper.startSequence().thenWaitUntil(() -> {
            for(int i=0;i<SLOTS;i++)GameTestChunks.assertTicking(helper,(X+i*48)/16,(X+i*48)/16+1,0,0);
        }).thenExecute(() -> t.run("data",t::dataRules))
                .thenExecute(() -> t.run("kinship",t::kinshipRules))
                .thenExecute(() -> t.run("feeding",t::feedingRules))
                .thenExecute(() -> t.run("single parent",t::singleParentRules))
                .thenExecute(() -> t.run("ownership",t::ownershipRules))
                .thenExecute(() -> t.run("interruption",t::interruptionRules))
                .thenExecute(() -> t.run("cards",t::cardRules))
                .thenExecute(() -> t.run("boat",t::boatRules))
                .thenExecute(() -> t.run("finite food",t::finiteFoodRules))
                .thenExecute(() -> t.run("card boundaries",t::cardBoundaryRules))
                .thenExecute(() -> t.run("natural setup",t::naturalSetup)).thenIdle(100).thenExecute(() -> t.debug("natural-100")).thenIdle(220)
                .thenExecute(() -> t.run("natural result",t::naturalResult))
                .thenExecute(() -> t.run("concert setup",t::concertSetup)).thenIdle(5).thenExecute(() -> t.run("concert start",t::concertStart))
                .thenIdle(1).thenExecute(() -> t.debug("concert-1")).thenIdle(3).thenExecute(() -> t.debug("concert-4")).thenIdle(236)
                .thenExecute(() -> t.run("concert result",t::concertResult))
                .thenExecute(() -> t.run("dialect setup",t::dialectSetup)).thenIdle(5)
                .thenExecute(() -> t.run("dialect start",t::dialectStart)).thenIdle(350)
                .thenExecute(() -> t.run("dialect result",t::dialectResult))
                .thenExecute(() -> t.run("expedition setup",t::expeditionSetup)).thenIdle(5)
                .thenExecute(() -> t.run("expedition start",t::expeditionStart)).thenIdle(700)
                .thenExecute(() -> t.run("expedition result",t::expeditionResult))
                .thenExecute(() -> t.run("teaching setup",t::teachingSetup)).thenIdle(5)
                .thenExecute(() -> t.run("teaching start",t::teachingStart)).thenIdle(160)
                .thenExecute(() -> t.run("teaching result",t::teachingResult));
        sequence.thenExecute(()->t.run("river living setup",t::riverLivingSetup)).thenIdle(5)
                .thenExecute(()->t.run("river living start",t::riverLivingStart)).thenIdle(160)
                .thenExecute(()->t.run("river living shore",t::riverLivingShore)).thenIdle(120)
                .thenExecute(()->t.run("river living result",t::riverLivingResult));
        for(String card:List.of("weak-escort","little-gap","crowd","territory","plea","lookout","sibling-game","mate-touch","young-call","tank-corner","tank-doll","forest-family","park-family","park-race","rain-close","heat-rest","reconcile","reconcile-refused","scold-after","street-scraps"))
            sequence.thenExecute(()->t.run(card+" setup",()->t.ambientSetup(card))).thenIdle(360).thenExecute(()->t.run(card+" result",()->t.ambientResult(card)));
        sequence.thenExecute(() -> {
                    t.cleanup();P0Control.write("wild-event-checks",t.checks);
                    var failed=t.checks.stream().filter(c -> !c.pass).toList();
                    if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
                });
    }
    private void check(String name,boolean pass,Object detail){checks.add(new Check(name,pass,String.valueOf(detail)));}
    private void run(String name,Runnable action){try{action.run();}catch(Throwable error){check(name+" unexpected exception",false,error);P0Control.write("wild-event-error-"+name.replace(' ','-'),java.util.Arrays.toString(error.getStackTrace()));}P0Control.write("wild-event-checks",checks);}
    private void setup() {
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());
        level.setDayTime(6000);level.setWeatherParameters(24000,0,false,false);
        for(int i=0;i<SLOTS;i++) {
            int x=X+i*48;GameTestChunks.setForced(level,x/16,0,true);GameTestChunks.setForced(level,x/16+1,0,true);
            for(int dx=0;dx<24;dx++)for(int z=0;z<16;z++) {
                level.setBlockAndUpdate(new BlockPos(x+dx,64,z),Blocks.STONE.defaultBlockState());
                for(int y=65;y<71;y++)level.setBlockAndUpdate(new BlockPos(x+dx,y,z),Blocks.AIR.defaultBlockState());
            }
        }
        player=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"EventCare"));level.addNewPlayer(player);
        otherPlayer=FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"EventOther"));level.addNewPlayer(otherPlayer);
        player.getAbilities().instabuild=false;otherPlayer.getAbilities().instabuild=false;
    }
    private void next() {
        for(var a:actors){Scene s=WildEventData.get(level).forMember(a.getUUID());if(s!=null&&s.active())WildEvents.finish(level,s,Phase.INTERRUPTED,"fixture_cleanup");a.discard();}actors.clear();
        center=new BlockPos(X+slot++*48+8,65,8);player.moveTo(center.getX()+.5,65,center.getZ()+2.5);otherPlayer.moveTo(center.getX()+.5,65,center.getZ()+3.5);
        player.getInventory().clearContent();otherPlayer.getInventory().clearContent();scene=null;
    }
    private YukkuriEntity spawn(boolean marisa,double dx,double dz) {
        var e=(marisa?ModEntities.MARISA:ModEntities.REIMU).get().create(level);
        e.moveTo(center.getX()+dx,65,center.getZ()+dz,0,0);e.setOnGround(true);e.setPersistenceRequired();
        var family=e.family().save();family.putBoolean("Initialized",true);family.putString("Stage","ADULT");family.putLong("Age",20000);family.putLong("Nourishment",20000);family.putLong("Lifespan",72000);e.family().load(family);
        life(e,80,90);var personality=e.life().personality().save();PersonalityFixtures.preferences(personality,new int[]{50,60,50,70,50,70});e.life().personality().load(personality);
        var cognition=new CompoundTag();cognition.putInt("Innate",fixtureAbility);cognition.putString("Origin","gametest_fixture");e.getPersistentData().put(io.github.longshou.mcyukkuri.cognition.Cognition.KEY,cognition);
        level.addFreshEntity(e);FamilyData.get(level).register(e);actors.add(e);return e;
    }
    private void life(YukkuriEntity e,float food,float energy) {
        var tag=e.life().save();tag.putFloat("Nutrition",food);tag.putFloat("Energy",energy);tag.putFloat("Hygiene",100);tag.putFloat("Bladder",0);
        tag.putInt("RestWait",1000);tag.putInt("SocialWait",1000);e.life().load(tag);
    }
    private void siblings() {
        var parent=spawn(false,6,.5);first=spawn(false,.5,.5);second=spawn(true,1.4,.5);
        first.family().generatedChild(parent,null);second.family().generatedChild(parent,null);
        FamilyData.get(level).register(first);FamilyData.get(level).register(second);
        YukkuriWorldData.get(level).relate(first.getUUID(),second.getUUID(),20);YukkuriWorldData.get(level).relate(second.getUUID(),first.getUUID(),20);
        parent.setNoAi(true);life(first,35,90);life(second,35,90);
    }
    private void shelter(YukkuriEntity e) {
        var at=center.offset(5,0,-4);
        var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(at,ShelterBlock.Kind.CARDBOARD,Direction.SOUTH));
        if(placed==null||!e.habitat().choose(placed.shelter()))throw new IllegalStateException("Actual cardboard shelter could not be assigned");
        e.habitat().stop();e.family().stop();e.social().stop();
    }
    private void dataRules() {
        var data=new WildEventData();var a=UUID.randomUUID();var b=UUID.randomUUID();var c=UUID.randomUUID();var d=UUID.randomUUID();
        var p=GlobalPos.of(level.dimension(),new BlockPos(31,65,31));
        var s=data.start(Kind.SISTERS,"sisters-wait",p,List.of(a,b),player.getUUID(),100);
        check("first event claims distinct identities",s!=null&&data.forMember(a)==s,"claims");
        check("mirrored pair cannot duplicate",data.start(Kind.SISTERS,"sisters-wait",p,List.of(b,a),null,100)==null,"same identities");
        check("region boundary cannot bypass nearby budget",!data.canStart(GlobalPos.of(level.dimension(),new BlockPos(33,65,33)),List.of(c,d),100),"real distance, not cell key");
        check("distant players have independent event budgets",data.canStart(GlobalPos.of(level.dimension(),new BlockPos(100,65,100)),List.of(c,d),100),"distance >32");
        s.fed.add(a);s.ticks=90;s.phaseTicks=20;s.phase=Phase.RESPONDING;
        var saved=data.save(new CompoundTag(),level.registryAccess());var restored=WildEventData.load(saved,level.registryAccess());var copy=restored.forMember(a);
        check("saved event restores stage, identity and only actual fed member",copy!=null&&copy.id.equals(s.id)&&copy.ticks==90&&copy.phase==Phase.RESPONDING&&copy.fed.equals(java.util.Set.of(a)),saved);
        restored.finish(copy,Phase.INTERRUPTED,"test",100);
        check("interruption releases both identities",restored.forMember(a)==null&&restored.forMember(b)==null,"released");
        check("reload retains participant and region cooldown",!WildEventData.load(restored.save(new CompoundTag(),level.registryAccess()),level.registryAccess()).canStart(p,List.of(a,b),101),"cooldown");
        check("cooldown eventually expires",restored.canStart(p,List.of(a,b),1301),"time advanced");
    }
    private void kinshipRules() {
        next();siblings();var stranger=spawn(true,2.7,.5);
        check("shared real mother qualifies juvenile siblings",WildEvents.siblings(first,second),"mother="+first.family().mother());
        check("nearby stranger is not a sister",!WildEvents.siblings(first,stranger),stranger.getUUID());
        var parent=actors.getFirst();
        check("unknown father remains null and permits a true single parent",second.family().father()==null&&WildEvents.singleParent(parent,second),"no fabricated father");
        var father=spawn(true,4,.5);second.family().generatedChild(parent,father);var person=FamilyData.get(level).person(second.getUUID());person.father=father.getUUID();
        check("living second parent rejects single-parent story",!WildEvents.singleParent(parent,second),"alive");
        UUID captured=YukkuriWorldData.get(level).capture(father);
        check("captured second parent is not dead",captured!=null&&!WildEvents.singleParent(parent,second),"captured");
        person.father=UUID.randomUUID();check("unknown record for named father is not inferred dead",!WildEvents.singleParent(parent,second),"no record");
        person.father=null;
        shelter(first);check("unrelated hungry pair cannot start N03",WildEvents.begin(Kind.SISTERS,first,List.of(stranger),player)==null,"rejected");
        check("no scene was minted for an invalid identity",WildEventData.get(level).forMember(first.getUUID())==null,"no claim");
    }
    private void feedingRules() {
        next();siblings();shelter(first);scene=WildEvents.begin(Kind.SISTERS,first,List.of(second),player);
        check("N03 starts from real sheltered hungry siblings",scene!=null,"available="+WildEvents.available(first));if(scene==null)return;
        check("begging does not generate food or fed credit",scene.fed.isEmpty()&&first.life().mouth().isEmpty()&&second.life().mouth().isEmpty(),scene.fed);
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,3));first.interact(player,InteractionHand.MAIN_HAND);
        check("real hand feed transfers exactly one item",player.getMainHandItem().getCount()==2&&first.life().mouth().getCount()==1,player.getMainHandItem());
        for(int i=0;i<20;i++)first.life().tick();
        check("before swallow the request is not settled",scene.fed.isEmpty(),scene.fed);
        first.life().interrupt();
        check("interrupted meal grants no event food credit",scene.fed.isEmpty(),scene.fed);
        first.interact(player,InteractionHand.MAIN_HAND);for(int i=0;i<70;i++)first.life().tick();
        check("actual swallow credits only the eater",scene.fed.equals(java.util.Set.of(first.getUUID()))&&scene.active(),scene.fed);
        int count=scene.fed.size();WildEvents.fed(first,player.getUUID());
        check("duplicate notification does not add another result",scene.fed.size()==count,"set membership");
        second.interact(player,InteractionHand.MAIN_HAND);for(int i=0;i<70;i++)second.life().tick();
        for(int i=0;i<85;i++)WildEvents.advance(level,scene);
        check("both real swallows complete N03",scene.phase==Phase.COMPLETE&&scene.fed.size()==2,scene.phase+":"+scene.result);
        check("all hand food was actually consumed from survival inventory",player.getMainHandItem().isEmpty(),player.getMainHandItem());
        next();siblings();shelter(first);scene=WildEvents.begin(Kind.SISTERS,first,List.of(second),player);
        check("owner-specific decline rejects unrelated player",scene!=null&&!WildEvents.decline(first,otherPlayer),"identity");
        check("actual menu decline grants no feeding outcome",new CareMenu(1,player.getInventory(),first.getId()).clickMenuButton(player,CareAction.DECLINE_EVENT.ordinal())&&scene.phase==Phase.REFUSED&&scene.fed.isEmpty(),scene.phase);
    }
    private void singleParentRules() {
        next();first=spawn(false,.5,.5);second=spawn(true,1.5,.5);second.family().generatedChild(first,null);
        FamilyData.get(level).register(second);life(first,35,90);life(second,35,90);shelter(first);
        scene=WildEvents.begin(Kind.SINGLE_PARENT,first,List.of(second),player);
        check("N04 requires an actual parent and recorded child",scene!=null&&WildEvents.singleParent(first,second),scene);
        if(scene!=null){
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,2));
            for(var e:List.of(first,second)){e.interact(player,InteractionHand.MAIN_HAND);for(int i=0;i<70;i++)e.life().tick();}
            for(int i=0;i<85;i++)WildEvents.advance(level,scene);
            check("N04 two actual swallows settle without generating food",scene.phase==Phase.COMPLETE&&scene.fed.size()==2&&player.getMainHandItem().isEmpty(),scene.phase+":"+scene.fed);
        }
        next();first=spawn(false,.5,.5);second=spawn(true,1.5,.5);second.family().generatedChild(first,null);
        FamilyData.get(level).register(second);life(second,35,90);shelter(first);
        scene=WildEvents.begin(Kind.SINGLE_PARENT,first,List.of(second),player);
        check("N04 actual menu refusal preserves empty feeding outcome",scene!=null&&new CareMenu(6,player.getInventory(),first.getId()).clickMenuButton(player,CareAction.DECLINE_EVENT.ordinal())&&scene.phase==Phase.REFUSED&&scene.fed.isEmpty(),scene==null?"null":scene.phase);
    }
    private void ownershipRules() {
        next();first=spawn(false,.5,.5);first.life().personality().fed(player.getUUID());first.life().personality().fed(player.getUUID());
        var tag=HumanCommerce.data(first).copy();tag.putUUID("Owner",otherPlayer.getUUID());tag.putString("Use","stock");HumanCommerce.set(first,tag);
        check("shop stock cannot be adopted around owner",!WildEvents.adopt(first,player),"owned stock");HumanCommerce.set(first,new CompoundTag());
        var menu=new CareMenu(2,player.getInventory(),first.getId());check("normal interaction menu adopts trusted unowned individual",menu.clickMenuButton(player,CareAction.ADOPT.ordinal())&&HumanCommerce.ownedBy(first,player.getUUID()),HumanCommerce.data(first));
        check("other player cannot abandon it",!WildEvents.abandon(first,otherPlayer),"owner protected");
        UUID id=first.getUUID();UUID token=YukkuriWorldData.get(level).capture(first);
        var released=YukkuriWorldData.get(level).release(token,level,new Vec3(center.getX()+.5,65,center.getZ()+.5),0);actors.add(released);first=released;
        check("ordinary carrier release preserves owner and never abandons",released!=null&&released.getUUID().equals(id)&&HumanCommerce.ownedBy(released,player.getUUID())&&WildEventData.get(level).forMember(id)==null,"same UUID");
        first.setOnGround(true);for(int i=0;i<21;i++)first.operations().tick();var baseline=first.pet().save();var newMenu=new CareMenu(3,player.getInventory(),first.getId());
        check("explicit abandonment menu removes only actual ownership",newMenu.clickMenuButton(player,CareAction.ABANDON.ordinal())&&!HumanCommerce.data(first).hasUUID("Owner"),HumanCommerce.data(first));
        scene=WildEventData.get(level).forMember(id);for(int i=0;i<205;i++)WildEvents.advance(level,scene);
        check("remembered former owner produces completed lament",scene.phase==Phase.COMPLETE&&scene.result.equals("former_owner_lament"),scene.result);
        check("abandonment keeps badge record intact",baseline.equals(first.pet().save()),"badge NBT");
        check("repeat abandonment cannot replay without new ownership",!WildEvents.abandon(first,player),"no owner");
    }
    private void interruptionRules() {
        next();siblings();shelter(first);scene=WildEvents.begin(Kind.SISTERS,first,List.of(second),player);
        if(scene==null)throw new IllegalStateException("interruption fixture refused");
        UUID secondId=second.getUUID();CompoundTag snapshot=new CompoundTag();second.save(snapshot);second.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        int ticks=scene.ticks;for(int i=0;i<80;i++)WildEvents.advance(level,scene);
        check("unloaded participant pauses without ghost replacement",scene.active()&&scene.ticks==ticks&&level.getEntity(secondId)==null,scene.phase);
        var restored=net.minecraft.world.entity.EntityType.loadEntityRecursive(snapshot,level,e -> e);level.addFreshEntity(restored);second=(YukkuriEntity)restored;actors.add(second);second.setOnGround(true);
        WildEvents.advance(level,scene);check("same restored identity resumes saved event",scene.ticks>ticks&&scene.members.contains(second.getUUID()),scene.ticks);
        first.hurt(level.damageSources().generic(),1);WildEvents.advance(level,scene);
        check("actual damage interrupts and releases all participant claims",scene.phase==Phase.INTERRUPTED&&!WildEvents.reserved(second),scene.result);
        next();first=spawn(false,.5,.5);second=spawn(true,1.5,.5);second.family().generatedChild(first,null);FamilyData.get(level).register(second);
        first.life().personality().fed(player.getUUID());first.life().personality().fed(player.getUUID());
        scene=WildEvents.begin(Kind.ADOPTION,first,List.of(second),player);
        check("real family can request adoption without gaining owner",scene!=null&&!HumanCommerce.data(first).hasUUID("Owner"),scene==null?"null":scene.phase);
        if(scene!=null){YukkuriWorldData.get(level).capture(second);WildEvents.advance(level,scene);check("actual capture produces family separation",scene.phase==Phase.SEPARATED,scene.phase);}
    }
    private void cardRules() {
        next();first=spawn(false,.5,.5);second=spawn(true,1.5,.5);
        check("recent conflict required for reconciliation",WildCards.begin("reconcile",first,List.of(second),null)==null,"no fabricated history");
        YukkuriWorldData.get(level).relate(first.getUUID(),second.getUUID(),-20);YukkuriWorldData.get(level).relate(second.getUUID(),first.getUUID(),-20);
        scene=WildCards.begin("rivalry",first,List.of(second),null);check("hostile real pair starts rivalry",scene!=null,"hostility");
        if(scene!=null){WildEvents.advance(level,scene);check("successful speech follows actual conflict",first.social().talk(player,true)&&scene.result.equals("voice-peace"),scene.result);
            for(int i=0;i<75;i++){first.social().tickGoal();WildEvents.advance(level,scene);}check("speech response finishes before peaceful continuation settles",scene.phase==Phase.COMPLETE&&scene.result.equals("actual_voice-peace"),scene.result);}
        next();first=spawn(false,.5,.5);second=spawn(true,1.4,.5);
        first.life().personality().fed(player.getUUID());first.life().personality().fed(player.getUUID());first.social().teach(player);
        check("event cannot hijack active instruction",!WildEvents.available(first)&&WildCards.begin("weak-escort",first,List.of(second),null)==null,first.social().activity());
    }
    private void boatRules() {
        next();first=spawn(false,.5,.5);second=spawn(true,3.5,.5);var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FAMILY,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        BlockPos water=center.offset(4,0,0);level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());
        second.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);
        var boat=HatBoatEntity.createFor(second,water);check("actual hat is transferred into one real boat",boat!=null&&second.getVehicle()==boat&&!second.life().hasAccessory(),boat);
        if(boat==null)return;scene=RiverEncounter.begin(first,boat);
        check("N05 binds existing boat owner and true group companion",scene!=null&&scene.target.equals(boat.getUUID())&&scene.members.get(1).equals(second.getUUID()),scene);
        if(scene==null)return;
        check("event does not generate an extra boat or rider",level.getEntitiesOfClass(HatBoatEntity.class,boat.getBoundingBox().inflate(5)).size()==1,boat.getPassengers().size());
        boat.capsize();WildEvents.advance(level,scene);
        check("real capsize interrupts instead of granting rescue",scene.phase==Phase.INTERRUPTED&&scene.result.equals("real_boat_capsized"),scene.result);boat.discard();
        next();first=spawn(false,.5,.5);second=spawn(true,3.5,.5);
        group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FAMILY,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        water=center.offset(4,0,0);level.setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());second.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);boat=HatBoatEntity.createFor(second,water);scene=boat==null?null:RiverEncounter.begin(first,boat);
        check("N05 rescue fixture binds real boat",scene!=null,scene);
        if(scene!=null){check("actual boat rescue returns original hat",boat.rescue(center.offset(2,0,2),player.getUUID())&&second.life().hasAccessory()&&!second.isPassenger(),boat.state());
            for(int i=0;i<105;i++)WildEvents.advance(level,scene);
            check("only real rescued boat yields rescued continuation",scene.phase==Phase.COMPLETE&&scene.result.equals("actual_boat_rescue_observed"),scene.phase+":"+scene.result);boat.discard();}
    }
    private void naturalSetup() {next();siblings();shelter(first);first.life().deferSocial(1200);second.life().deferSocial(1200);}
    private void cardBoundaryRules(){
        next();first=spawn(false,.5,.5);second=spawn(true,1.4,.5);
        check("crowd requires three distinct actual members",WildCards.begin("crowd",first,List.of(second),null)==null,"only two");
        check("grave scene requires a real grave block entity",WildCards.begin("grave-quiet",first,List.of(second),null)==null,"no grave");
        level.setBlockAndUpdate(center.offset(2,0,1),io.github.longshou.mcyukkuri.registry.ModBlocks.GRAVE.get().defaultBlockState());
        scene=WildCards.begin("grave-quiet",first,List.of(second),null);check("real nearby grave enables quiet observation",scene!=null,scene);
        next();first=spawn(false,.5,.5);second=spawn(true,3.5,.5);life(second,40,45);
        for(int z=-3;z<=3;z++)for(int y=0;y<4;y++)level.setBlockAndUpdate(center.offset(2,y,z),Blocks.STONE.defaultBlockState());
        check("solid inaccessible wall rejects companionship scene",WildCards.begin("weak-escort",first,List.of(second),null)==null,"actual collision wall");
        next();first=spawn(false,.5,.5);second=spawn(true,1.4,.5);first.pet().issue(io.github.longshou.mcyukkuri.pet.BadgeGrade.GOLD,1,null);
        UUID token=second.life().takeAccessory(player.getUUID());
        var stack=new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.accessoryFor(second));
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA,stack,tag->{tag.putUUID("Owner",second.getUUID());tag.putUUID("Token",token);});
        var item=new net.minecraft.world.entity.item.ItemEntity(level,second.getX(),65,second.getZ(),stack);item.setNoPickUpDelay();level.addFreshEntity(item);
        scene=WildCards.begin("ornament",first,List.of(second),null);check("ornament scene binds actual matching token",scene!=null&&scene.target.equals(item.getUUID()),scene);
        if(scene!=null){WildEvents.advance(level,scene);check("contact restores original ornament and consumes only its real item",scene.phase==Phase.COMPLETE&&second.life().hasAccessory()&&!item.isAlive(),scene.phase+":"+scene.result);}
        next();first=spawn(false,.5,.5);second=spawn(true,3.5,.5);
        check("normal companion does not trigger deficient form waiting",WildCards.begin("deficient-wait",first,List.of(second),null)==null,"normal form");
        second.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.NORMAL,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.BEAN_BODY);
        check("actual congenital form enables distant waiting",WildCards.begin("deficient-wait",first,List.of(second),null)!=null,second.subspecies().congenital());
        next();first=spawn(false,.5,.5);second=spawn(true,1.4,.5);life(first,35,90);shelter(first);
        level.setBlockAndUpdate(center.offset(-1,0,0),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(center.offset(0,0,-1),Blocks.STONE.defaultBlockState());
        check("narrow alley needs physical waste rather than only a place label",WildCards.begin("alley-sniff",first,List.of(second),null)==null,"no item");
        item=new net.minecraft.world.entity.item.ItemEntity(level,first.getX()+.1,65,first.getZ(),new ItemStack(io.github.longshou.mcyukkuri.registry.ModItems.WASTE.get()));item.setNoPickUpDelay();level.addFreshEntity(item);
        check("real walls shelter and waste enable alley observation",WildCards.begin("alley-sniff",first,List.of(second),null)!=null,item.getItem());
    }
    private net.minecraft.world.entity.item.ItemEntity groundFood(int count){
        var item=new net.minecraft.world.entity.item.ItemEntity(level,first.getX()+.1,65,first.getZ(),new ItemStack(Items.APPLE,count));item.setNoPickUpDelay();item.setDeltaMovement(Vec3.ZERO);level.addFreshEntity(item);return item;
    }
    private void eatTicks(YukkuriEntity e,int ticks){for(int i=0;i<ticks;i++){if(e.life().plan())e.life().tickGoal();e.life().tick();if(scene!=null)WildEvents.advance(level,scene);}}
    private void finiteFoodRules(){
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
        next();first=spawn(false,.5,.5);second=spawn(true,.8,.5);life(first,35,90);life(second,35,90);
        var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FAMILY,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        var food=groundFood(2);scene=WildCards.begin("group-turn",first,List.of(second),null);
        check("same group queue binds one finite food stack",scene!=null&&scene.target.equals(food.getUUID()),scene);
        if(scene!=null){eatTicks(second,30);check("later member waits without consuming the shared stack",food.getItem().getCount()==2&&second.life().mouth().isEmpty(),food.getItem());
            eatTicks(first,170);check("first actual swallow releases the next turn",scene.fed.contains(first.getUUID())&&food.getItem().getCount()==1,scene.fed);
            eatTicks(second,170);check("two actual turn swallows consume exactly two portions",scene.phase==Phase.COMPLETE&&scene.fed.size()==2&&!food.isAlive(),scene.phase+":"+scene.result);}
        next();fixtureAbility=1000;first=spawn(false,.5,.5);fixtureAbility=100;second=spawn(true,1.5,.5);life(first,35,90);
        group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        food=groundFood(1);scene=WildCards.begin("forest-find",first,List.of(second),null);
        check("forest recognition reads independent cognition",scene!=null&&scene.sourceCount==1,scene);
        if(scene!=null){eatTicks(first,170);check("recognized forest food is really consumed once",scene.phase==Phase.COMPLETE&&scene.fed.size()==1&&!food.isAlive(),scene.phase+":"+scene.result);}
        next();first=spawn(false,.5,.5);second=spawn(true,.8,.5);life(first,35,90);life(second,35,90);
        group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FAMILY,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        food=groundFood(2);scene=WildCards.begin("group-turn",first,List.of(second),null);
        if(scene!=null){for(int i=0;i<150&&first.life().mouth().isEmpty();i++)eatTicks(first,1);first.life().interrupt();check("interrupting a claimed mouthful never credits another meal",scene.phase==Phase.INTERRUPTED&&scene.fed.isEmpty(),scene.phase+":"+scene.result);}
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());
    }
    private void naturalResult() {
        scene=WildEventData.get(level).scenes().stream().filter(s -> s.kind==Kind.SISTERS&&s.members.contains(first.getUUID())).findFirst().orElse(null);
        check("normal server ticks naturally discover N03 without command",scene!=null,scene==null?"none":scene.phase+":"+scene.result);
        check("natural request still grants no food for watching",scene!=null&&scene.fed.isEmpty(),scene==null?"none":scene.fed);
        if(scene==null){debug("natural-end");P0Control.write("wild-natural-history",WildEventData.get(level).scenes().stream().filter(s -> s.members.contains(first.getUUID())).map(s -> s.kind+":"+s.phase+":"+s.result).toList());var probe=WildEvents.begin(Kind.SISTERS,first,List.of(second),player);
            P0Control.write("wild-natural-probe",java.util.Map.of("manualBegin",probe!=null,"siblings",WildEvents.siblings(first,second),
                    "friendly",WildEvents.friendly(first,second),"path",first.life().reachable(second.blockPosition(),false),
                    "nearPlayer",WildEvents.playerNear(first,player),"firstStage",first.family().stage().name(),"secondStage",second.family().stage().name()));}
    }
    private void concertSetup() {
        next();first=spawn(false,.5,.5);second=spawn(true,1.5,.5);
        var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        YukkuriWorldData.get(level).relate(first.getUUID(),second.getUUID(),20);YukkuriWorldData.get(level).relate(second.getUUID(),first.getUUID(),20);
        for(var e:List.of(first,second)){var cooldown=new CompoundTag();cooldown.putInt("Cooldown",1200);e.groupInteraction().load(cooldown);}
    }
    private void concertStart(){check("N01 existing DailyLife starts bound song",first.dailyLife().singTo(second)&&first.dailyLife().active()&&second.dailyLife().active(),first.dailyLife().activity());}
    private void concertResult() {
        scene=WildEventData.get(level).scenes().stream().filter(s -> s.kind==Kind.CONCERT&&s.members.contains(first.getUUID())).findFirst().orElse(null);
        check("N01 naturally attaches to existing song without duplicate scheduler",scene!=null,scene);
        check("song and audience finish through real AI ticks",scene!=null&&scene.phase==Phase.COMPLETE&&first.dailyLife().songs()==1&&second.dailyLife().songs()==1,scene==null?"none":scene.phase+":"+scene.result);
        check("audience responded without double song reward",scene!=null&&scene.responses>=3&&first.dailyLife().songs()==1,scene==null?"none":scene.responses);
    }
    private void dialectSetup(){next();first=spawn(false,.5,.5);second=spawn(true,1.5,.5);var tag=first.life().personality().save();PersonalityFixtures.preferences(tag,new int[]{75,60,50,70,50,70});first.life().personality().load(tag);settlePair();}
    private void settlePair(){for(var e:List.of(first,second)){var tag=new CompoundTag();tag.putInt("Cooldown",1200);e.groupInteraction().load(tag);e.life().deferSocial(1200);}}
    private void dialectStart(){scene=WildCards.begin("imitate",first,List.of(second),null);check("different species begin actual dialect imitation",scene!=null,scene);}
    private void dialectResult(){
        var learned=first.getPersistentData().getCompound("LearnedDialect");
        check("imitation finishes after actual synchronized speech",scene!=null&&scene.phase==Phase.COMPLETE,scene==null?"null":scene.phase+":"+scene.result);
        check("learned phrase records the actual teacher without changing species",learned.hasUUID("Teacher")&&learned.getUUID("Teacher").equals(second.getUUID())&&first.getVariant().dialogueSpecies().equals("reimu")&&learned.getString("Species").equals("marisa"),learned);
        var saved=new CompoundTag();first.saveWithoutId(saved);var reloaded=ModEntities.REIMU.get().create(level);reloaded.load(saved);
        check("teacher and phrase survive entity save reload",reloaded.getPersistentData().getCompound("LearnedDialect").equals(learned),learned);
    }
    private void expeditionSetup(){
        next();first=spawn(true,.5,.5);second=spawn(true,1.5,.5);settlePair();
        var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        var personality=first.life().personality().save();PersonalityFixtures.preferences(personality,new int[]{75,60,75,70,50,70});first.life().personality().load(personality);
        level.setBlockAndUpdate(center.offset(7,-1,0),Blocks.GRASS_BLOCK.defaultBlockState());level.setBlockAndUpdate(center.offset(7,0,0),Blocks.SHORT_GRASS.defaultBlockState());
        check("ordinary same group does not grant expedition roles",io.github.longshou.mcyukkuri.event.Expedition.role(first).equals("none"),first.settlement().groupId());
    }
    private void expeditionStart(){
        scene=io.github.longshou.mcyukkuri.event.Expedition.form(first,List.of(second),center.offset(7,0,0));
        check("scouting forms real captain roster and reachable destination",scene!=null&&io.github.longshou.mcyukkuri.event.Expedition.role(first).equals("captain")&&io.github.longshou.mcyukkuri.event.Expedition.role(second).equals("member"),scene);
        if(scene!=null){var restored=WildEventData.load(WildEventData.get(level).save(new CompoundTag(),level.registryAccess()),level.registryAccess()).scene(scene.id);
            check("expedition route and exact captain order survive save",restored.destination.equals(scene.destination)&&restored.members.equals(scene.members),restored.destination);}
    }
    private void expeditionResult(){check("actual squad reports travels and returns",scene!=null&&scene.phase==Phase.COMPLETE&&scene.result.equals("real_squad_reported_and_returned"),scene==null?"null":scene.phase+":"+scene.result+":"+scene.sourceCount+":"+first.position());}
    private void teachingSetup(){
        next();fixtureAbility=500;first=spawn(false,.5,.5);fixtureAbility=400;second=spawn(true,1.5,.5);fixtureAbility=100;
        second.family().generatedChild(first,null);FamilyData.get(level).register(second);settlePair();
    }
    private void teachingStart(){scene=WildCards.begin("teach",first,List.of(second),null);check("real adult teacher and lower capability child begin lesson",scene!=null,scene);}
    private void teachingResult(){
        var cognition=io.github.longshou.mcyukkuri.cognition.Cognition.get(second);
        check("both physical jumps precede capability gain",scene!=null&&scene.responses==3&&scene.sourceCount==1&&scene.phase==Phase.COMPLETE&&cognition.ability()==401,scene==null?"null":scene.phase+":"+scene.result+":"+scene.responses+":"+cognition);
        if(scene!=null)check("same successful classroom cannot pay twice",io.github.longshou.mcyukkuri.cognition.Cognition.commitTeaching(first,second,scene.id)==io.github.longshou.mcyukkuri.cognition.Cognition.TeachingResult.DUPLICATE&&io.github.longshou.mcyukkuri.cognition.Cognition.get(second).ability()==401,cognition);
    }
    private void riverLivingSetup(){
        next();first=spawn(false,.5,.5);second=spawn(true,2.5,.5);settlePair();
        var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FAMILY,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);second.settlement().join(group);
        for(int x=4;x<10;x++)for(int z=-2;z<3;z++){level.setBlockAndUpdate(center.offset(x,-2,z),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(center.offset(x,-1,z),Blocks.WATER.defaultBlockState());}
    }
    private void riverLivingStart(){second.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);liveBoat=HatBoatEntity.createFor(second,center.offset(4,-1,0));scene=liveBoat==null?null:RiverEncounter.begin(first,liveBoat);check("real flowing water fixture starts N05",scene!=null,scene);}
    private void riverLivingShore(){
        check("actual shoreline arrival is not mistaken for a failed path",scene!=null&&scene.active()&&liveBoat.isAfloat(),scene==null?"null":scene.phase+":"+scene.result+":"+first.position());
        check("real moving boat accepts actual dry-land rescue",liveBoat!=null&&liveBoat.rescue(center.offset(2,0,2),player.getUUID()),liveBoat);
    }
    private void riverLivingResult(){check("actual server ticks finish N05 only after true rescue",scene!=null&&scene.phase==Phase.COMPLETE&&scene.result.equals("actual_boat_rescue_observed"),scene==null?"null":scene.phase+":"+scene.result);if(liveBoat!=null)liveBoat.discard();}
    private void ambientSetup(String card){
        next();level.setWeatherParameters(24000,0,false,false);level.setRainLevel(0);level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());
        first=spawn(false,.5,.5);second=spawn(true,1.4,.5);settlePair();
        YukkuriWorldData.get(level).relate(first.getUUID(),second.getUUID(),20);YukkuriWorldData.get(level).relate(second.getUUID(),first.getUUID(),20);
        List<YukkuriEntity> peers=new ArrayList<>();peers.add(second);
        switch(card){
            case "weak-escort" -> {second.moveTo(center.getX()+3.5,65,center.getZ()+.5);life(second,80,45);}
            case "little-gap" -> {var parent=spawn(false,5,4);second.family().generatedChild(parent,null);FamilyData.get(level).register(second);parent.setNoAi(true);}
            case "crowd" -> peers.add(spawn(false,.5,1.4));
            case "territory" -> {var tag=first.life().personality().save();PersonalityFixtures.preferences(tag,new int[]{50,60,85,10,50,70});tag.putIntArray("DispositionInnate",new int[]{90,85,60,60});first.life().personality().load(tag);YukkuriWorldData.get(level).relate(first.getUUID(),second.getUUID(),-20);}
            case "plea" -> life(first,35,90);
            case "lookout" -> {first.life().personality().hurt(player.getUUID());first.life().personality().hurt(player.getUUID());}
            case "sibling-game" -> {var parent=spawn(false,5,4);first.family().generatedChild(parent,null);second.family().generatedChild(parent,null);FamilyData.get(level).register(first);FamilyData.get(level).register(second);parent.setNoAi(true);}
            case "mate-touch" -> {first.family().introduce(second);first.family().stop();second.family().stop();}
            case "young-call" -> {first.family().generatedChild(second,null);FamilyData.get(level).register(first);second.moveTo(center.getX()+4.5,65,center.getZ()+.5);}
            case "tank-corner" -> {first.family().introduce(second);first.family().stop();second.family().stop();var mouth=center.offset(4,0,-2);NaturalShelterFixture.cave(level,mouth,Direction.SOUTH);
                var placed=ShelterPlacement.place(level,new ShelterPlacement.Plan(mouth,ShelterBlock.Kind.BURROW,Direction.SOUTH));
                if(placed==null||!first.habitat().choose(placed.shelter())||!second.habitat().choose(placed.shelter()))throw new IllegalStateException("actual shared family cave refused");
                first.habitat().stop();second.habitat().stop();first.moveTo(mouth.getX()+.5,65,mouth.getZ()+1.5);second.moveTo(first.getX()+.9,65,first.getZ());}
            case "tank-doll" -> {var tag=first.life().personality().save();PersonalityFixtures.preferences(tag,new int[]{85,60,50,70,50,70});first.life().personality().load(tag);var toy=new net.minecraft.world.entity.item.ItemEntity(level,center.getX()+.95,65,center.getZ()+.5,new ItemStack(io.github.longshou.mcyukkuri.life.Facilities.ITEMS.get("reimu_doll").get()));toy.setNoPickUpDelay();toy.setDeltaMovement(Vec3.ZERO);level.addFreshEntity(toy);}
            case "forest-family" -> {second.family().generatedChild(first,null);FamilyData.get(level).register(second);var third=spawn(false,.5,1.5);third.family().generatedChild(first,null);FamilyData.get(level).register(third);peers.add(third);var group=new SettlementData.Group(UUID.randomUUID(),SettlementData.Kind.FOREST,center,first.getUUID());SettlementData.get(level).add(group);first.settlement().join(group);}
            case "park-family" -> {second.family().generatedChild(first,null);FamilyData.get(level).register(second);}
            case "rain-close" -> {second.moveTo(center.getX()+3.5,65,center.getZ()+.5);level.setWeatherParameters(0,6000,true,false);level.setRainLevel(1);}
            case "heat-rest" -> {level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withLevel(level).withPermission(4),"fillbiome "+new BlockPos(center.getX()-8,60,0).toShortString().replace(",","")+" "+new BlockPos(center.getX()+15,80,15).toShortString().replace(",","")+" minecraft:desert");life(second,80,45);}
            case "reconcile","reconcile-refused" -> {WildCards.competing(first,second);if(card.endsWith("refused"))YukkuriWorldData.get(level).relate(second.getUUID(),first.getUUID(),-40);
                check("recent dispute evidence outlives event member cooldown",first.getPersistentData().getCompound("WildConflict").getLong("Until")>level.getGameTime()+1280,"allows a later reconciliation");}
            case "scold-after" -> {YukkuriWorldData.get(level).relate(first.getUUID(),second.getUUID(),-40);YukkuriWorldData.get(level).relate(second.getUUID(),first.getUUID(),-40);}
            case "street-scraps" -> {life(first,35,90);shelter(first);groundFood(1);}
        }
        scene=WildCards.begin(card.equals("reconcile-refused")?"reconcile":card.equals("scold-after")?"rivalry":card,first,peers,player);check(card+" qualifies actual actors and place",scene!=null,scene==null?"first="+first.getBehaviorId()+",second="+second.getBehaviorId()+",distance="+first.distanceToSqr(second)+",temp="+level.getBiome(first.blockPosition()).value().getBaseTemperature()+",homeA="+first.life().home()+",homeB="+second.life().home():scene.id);
        if(card.equals("scold-after")&&scene!=null){WildEvents.advance(level,scene);check("real reprimand starts conflict continuation",first.social().talk(player,false)&&scene.result.equals("scold-after"),scene.result);}
        if(card.equals("street-scraps"))level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
    }
    private void ambientResult(String card){
        check(card+" completes through actual server goals",scene!=null&&scene.phase==(card.equals("reconcile-refused")?Phase.REFUSED:Phase.COMPLETE),scene==null?"null":scene.phase+":"+scene.result+":"+scene.responses+":"+first.distanceToSqr(second));
        if(card.equals("reconcile-refused"))check("refusing reconciliation preserves actual old conflict",first.getPersistentData().contains("WildConflict")&&YukkuriWorldData.get(level).affinity(second.getUUID(),first.getUUID())<0,scene==null?"null":scene.result);
        if(card.equals("sibling-game")||card.equals("park-race"))check(card+" records two actual jumps from each participant",scene!=null&&scene.responses==15,scene==null?"null":scene.responses);
        if(card.equals("tank-doll"))check("both actors touched one existing doll",scene!=null&&scene.responses>=2,scene==null?"null":scene.responses);
        if(card.equals("forest-family"))check("each of the three actual relatives answered",scene!=null&&scene.responses==3,scene==null?"null":scene.responses);
    }
    private void debug(String name){
        var values=new ArrayList<Object>();for(var e:List.of(first,second))values.add(java.util.Map.ofEntries(
            java.util.Map.entry("id",e.getUUID()),java.util.Map.entry("pos",e.position().toString()),java.util.Map.entry("ground",e.onGround()),
            java.util.Map.entry("safe",WildEvents.safe(e)),java.util.Map.entry("available",WildEvents.available(e)),java.util.Map.entry("behavior",e.getBehaviorId()),
            java.util.Map.entry("life",e.life().activity()),java.util.Map.entry("family",e.family().activity()),java.util.Map.entry("social",e.social().activity()),
            java.util.Map.entry("daily",e.dailyLife().activity()),java.util.Map.entry("dailyValid",e.dailyLife().valid()),java.util.Map.entry("hasShelter",WildEvents.inhabited(e)),
            java.util.Map.entry("food",e.life().nutrition()),java.util.Map.entry("energy",e.life().energy()),java.util.Map.entry("home",String.valueOf(e.life().home())),
            java.util.Map.entry("scene",WildEvents.activity(e)),java.util.Map.entry("noai",e.isNoAi()),java.util.Map.entry("day",level.isDay())));
        P0Control.write("wild-debug-"+name,values);
    }
    private void cleanup(){for(var a:actors)if(a!=null)a.discard();player.discard();otherPlayer.discard();for(int i=0;i<SLOTS;i++){GameTestChunks.setForced(level,(X+i*48)/16,0,false);GameTestChunks.setForced(level,(X+i*48)/16+1,0,false);}}
}
