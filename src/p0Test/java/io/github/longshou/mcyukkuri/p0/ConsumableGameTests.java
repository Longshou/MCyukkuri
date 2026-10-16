package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Consumables.Kind;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class ConsumableGameTests {
    private record Check(String name,boolean pass){}
    private final List<Check> checks=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    private static final AABB AREA=new AABB(1900,63,0,1980,75,16);
    private static void chunksReady(GameTestHelper helper){
        for(int chunk=118;chunk<=123;chunk++)helper.assertTrue(helper.getLevel().isPositionEntityTicking(new BlockPos(chunk*16+8,65,8)),"waiting for every consumable actor and drop chunk");
    }
    private static void floor(ServerLevel level){
        for(int c=118;c<=123;c++)GameTestChunks.setForced(level,c,0,true);
        level.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
        for(int x=1900;x<1980;x++)for(int z=0;z<16;z++){
            for(int y=65;y<75;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState());
        }
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
    }
    private static YukkuriEntity spawn(ServerLevel level,double x){
        return spawn(level,x,io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU);
    }
    private static YukkuriEntity spawn(ServerLevel level,double x,io.github.longshou.mcyukkuri.entity.YukkuriVariant variant){
        var e=ModEntities.forVariant(variant).create(level);e.moveTo(x,65,8.5,0,0);e.setPersistenceRequired();e.setOnGround(true);level.addFreshEntity(e);
        var t=e.life().save();t.putFloat("Nutrition",100);t.putFloat("Energy",90);t.putFloat("Hygiene",100);e.life().load(t);
        var f=e.family().save();f.putBoolean("Initialized",true);e.family().load(f);return e;
    }
    private static ItemStack dose(Kind kind){return new ItemStack(Consumables.ITEMS.get(kind).get());}
    private static ItemStack water(){var s=new ItemStack(Items.POTION);s.set(DataComponents.POTION_CONTENTS,new PotionContents(Potions.WATER));return s;}
    private static void tick(YukkuriEntity e,int n){for(int i=0;i<n;i++)e.life().tick();}
    private static int drops(ServerLevel level,net.minecraft.world.item.Item item){return level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(item)).stream().mapToInt(e->e.getItem().getCount()).sum();}
    private void finish(GameTestHelper helper,String name){
        P0Control.write(name,checks);helper.getLevel().getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof ItemEntity).forEach(Entity::discard);
        for(int c=118;c<=123;c++)GameTestChunks.setForced(helper.getLevel(),c,0,false);
        var failed=checks.stream().filter(c->!c.pass).toList();if(failed.isEmpty())helper.succeed();else helper.fail(failed.toString());
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="consumable_rules")
    public static void rules(GameTestHelper helper){
        var t=new ConsumableGameTests();var level=helper.getLevel();floor(level);
        helper.startSequence().thenWaitUntil(()->chunksReady(helper))
        .thenExecute(()->{try{t.rules(level);}catch(Throwable e){t.check("unexpected "+e,false);P0Control.write("consumable-rule-error",java.util.Arrays.toString(e.getStackTrace()));}t.finish(helper,"consumable-rule-checks");});
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="fertility_rules")
    public static void fertilityRules(GameTestHelper helper){
        var test=new ConsumableGameTests();var level=helper.getLevel();floor(level);
        helper.startSequence().thenWaitUntil(()->chunksReady(helper)).thenExecute(()->{
            try{test.fertilityRules(level);}catch(Throwable error){test.check("unexpected "+error,false);}
            test.finish(helper,"fertility-checks");
        });
    }
    private void fertilityRules(ServerLevel level){
        var data=io.github.longshou.mcyukkuri.family.FamilyData.get(level);
        for(int x=1935;x<=1972;x++)for(int z=3;z<=13;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
        var first=spawn(level,1940.5);var second=spawn(level,1942.5,io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA);
        check("fertility fixture claims covered homes",first.life().assignNaturalHome(first.blockPosition())&&second.life().assignNaturalHome(second.blockPosition()));
        var relations=io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level);
        relations.relate(first.getUUID(),second.getUUID(),30);relations.relate(second.getUUID(),first.getUUID(),30);
        check("ordinary pair is eligible before changing congenital form",first.family().canReproduceWith(second)&&data.canAssist(first));
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"Fertility"));
        player.moveTo(1941.5,65,8.5);player.getAbilities().instabuild=false;level.addNewPlayer(player);
        try{
            for(var actor:List.of(first,second))for(var shape:io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.values()){
                if(shape==io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL)continue;
                actor.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.NORMAL,shape);
                String label=actor.getVariant()+" "+shape;
                float nutrition=actor.life().nutrition();int conceptions=actor.family().conceptions();
                player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Consumables.ITEMS.get(Kind.REPRODUCTIVE).get(),2));
                check(label+" rejects either parental role",!first.family().canReproduceWith(second)&&!second.family().canReproduceWith(first)
                        &&data.conceive(first,second,io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.STEM,net.minecraft.core.GlobalPos.of(level.dimension(),first.blockPosition()))==null
                        &&data.conceive(second,first,io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.LIVE,net.minecraft.core.GlobalPos.of(level.dimension(),second.blockPosition()))==null);
                check(label+" rejects assistance without consuming a dose",!data.canAssist(actor)&&data.assist(actor)==null
                        &&!actor.operations().beginAssistance(player,net.minecraft.world.InteractionHand.MAIN_HAND)&&player.getMainHandItem().getCount()==2);
                check(label+" rejects stimulation without charging nutrition or a conception",actor.family().sterile()&&!actor.family().takeStimulant()&&!actor.family().stimulate()
                        &&actor.life().nutrition()==nutrition&&actor.family().conceptions()==conceptions&&actor.family().brood()==null);
                var stored=actor.subspecies().save();actor.subspecies().load(stored);actor.family().load(actor.family().save());
                check(label+" infertility survives save without setting surgical sterile flag",actor.family().sterile()&&!actor.family().save().getBoolean("Sterile"));
                actor.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.NORMAL,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);
            }
            first.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.WASA_REIMU,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);
            second.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.SHELL_MARISA,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);
            check("Wasa and shell retain natural reproduction eligibility",first.family().canReproduceWith(second)&&!first.family().sterile()&&!second.family().sterile());
            var selected=first.family().save();int serial=0;
            while(io.github.longshou.mcyukkuri.family.FamilyData.offspringCount(first.family().adultScale(),first.getUUID().getMostSignificantBits()^serial)!=2)serial++;
            selected.putInt("Conceptions",serial);first.family().load(selected);
            var brood=data.assist(first);check("Wasa can actually conceive with assistance",brood!=null);
            if(brood!=null){
                // Simulate an old invalid mother after one real child has already been born.
                brood.elapsed=brood.duration+200;first.syncFamily();
                var born=data.birth(brood,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(first,0));
                check("eligible mother can deliver a real child",born!=null);
                first.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.NORMAL,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.BEAN_BODY);
                first.setNoAi(true);first.family().tick();
                check("legacy invalid maternal gestation stops even with NoAI",first.family().brood()==null&&(brood.complete()||brood.cancelled));
                check("legacy repair retains born identity and retires only unborn reservations",brood.children.stream().allMatch(c->data.person(c.id).status==(c.born?io.github.longshou.mcyukkuri.family.FamilyData.Status.ALIVE:io.github.longshou.mcyukkuri.family.FamilyData.Status.LOST))
                        &&born!=null&&level.getEntity(born.getUUID())==born);
            }
            var direct=spawn(level,1970.5);direct.life().assignNaturalHome(direct.blockPosition());var invalid=data.assist(direct);
            check("direct birth guard fixture conceived before legacy form restore",invalid!=null);
            if(invalid!=null){
                invalid.elapsed=invalid.duration+200;
                direct.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.NORMAL,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.SPARSE_HAIR);
                check("direct birth cannot bypass invalid maternal form",data.birth(invalid,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(direct,0))==null&&invalid.cancelled
                        &&invalid.children.stream().allMatch(c->level.getEntity(c.id)==null));
            }
        }finally{player.discard();}
    }
    private void rules(ServerLevel level){
        var e=spawn(level,1904.5);e.setHealth(6);float nutrition=e.life().nutrition();
        check("water rejects a redundant serving",!e.life().beginDose(water(),null,true));
        var state=e.life().save();state.putFloat("Dryness",85);state.putFloat("Bladder",95);e.life().load(state);
        check("dry grain state causes real constipation",e.life().constipated());
        check("full stomach accepts a needed drink",e.life().beginDose(water(),null,true));
        tick(e,20);check("held water has no early effect or empty bottle",e.life().dryness()==85&&drops(level,Items.GLASS_BOTTLE)==0&&e.life().mouth().getCount()==1);
        e.life().load(e.life().save());tick(e,24);
        check("saved dose commits once on swallowing",e.life().dryness()==55&&e.life().mouth().isEmpty()&&drops(level,Items.GLASS_BOTTLE)==1);
        check("water relieves constipation without healing or nutrition",!e.life().constipated()&&e.getHealth()==6&&e.life().nutrition()==nutrition);
        tick(e,25);check("post-swallow reload cannot consume again",drops(level,Items.GLASS_BOTTLE)==1);
        check("yogurt can begin independently of hunger",e.life().beginDose(dose(Kind.YOGURT),null,true));tick(e,12);e.life().interrupt();
        check("interrupted yogurt returns one full serving",drops(level,Consumables.ITEMS.get(Kind.YOGURT).get())==1&&e.life().dryness()==55&&drops(level,Items.GLASS_BOTTLE)==1);
        check("another serving can follow interruption",e.life().beginDose(dose(Kind.YOGURT),null,true));tick(e,44);
        check("yogurt has stronger digestive effect and no healing",e.life().dryness()==0&&e.getHealth()==6&&drops(level,Items.GLASS_BOTTLE)==2);tick(e,25);
        check("stimulant starts as a held dose",e.life().beginDose(dose(Kind.STIMULANT),null,true));tick(e,20);
        check("stimulant does not activate before swallow",e.family().sexDrive()==0);tick(e,24);
        check("stimulant changes reproductive drive without immediate conception",e.family().sexDrive()==1200&&e.family().brood()==null&&e.getHealth()==6);tick(e,25);
        check("repeat active stimulant is refused",!e.life().beginDose(dose(Kind.STIMULANT),null,true));
        e.family().load(e.family().save());check("drive survives save/load",e.family().sexDrive()==1200);
        check("baby talk medicine can begin",e.life().beginDose(dose(Kind.SITATARAN),null,true));tick(e,44);
        check("sitataran selects young speech, not physical healing",e.family().babyTalk()&&e.family().youngVoice()&&e.getHealth()==6);tick(e,25);
        e.family().load(e.family().save());check("baby talk persists",e.family().babyTalk());check("duplicate baby talk is refused",!e.life().beginDose(dose(Kind.SITATARAN),null,true));
        var young=spawn(level,1912.5);var f=young.family().save();f.putString("Stage","JUVENILE");young.family().load(f);
        check("juvenile cannot take reproductive stimulant",!young.life().beginDose(dose(Kind.STIMULANT),null,true));
        young.setNoAi(true);check("NoAI does not accept a dose that cannot advance",!young.life().beginDose(dose(Kind.YOGURT),null,true));
        check("spray begins delayed onset",e.life().spray()&&!e.life().isSleeping()&&e.life().sprayDelay()==90);
        check("repeat spray cannot reset onset",!e.life().spray());tick(e,45);e.life().load(e.life().save());
        check("onset remaining survives save/load",e.life().sprayDelay()==45);tick(e,45);
        check("spray creates sleep without a claimed home",e.life().drugSleep()==400&&e.life().home()==null);
        e.load(e.saveWithoutId(new net.minecraft.nbt.CompoundTag())); // the spray starts the doze-off clip as a running action; this entity is not ticked, so a reload ends it as its expiry would
        for(int i=0;i<25;i++){e.life().plan();e.life().tickGoal();}
        check("actual sleep begins and spends loaded duration",e.life().isSleeping()&&e.life().drugSleep()==375);
        e.life().load(e.life().save());check("sleep remains resumable after load",e.life().drugSleep()==375&&e.life().plan());
        e.hurt(level.damageSources().generic(),.5f);check("external danger interrupts remaining drug sleep",e.life().drugSleep()==0&&!e.life().isSleeping());
        var spicy=spawn(level,1920.5);state=spicy.life().save();state.putFloat("Nutrition",40);spicy.life().load(state);spicy.setHealth(5);
        check("spicy feed is a real meal",spicy.life().beginMeal(dose(Kind.SPICY),null,true));tick(spicy,43);
        check("unconsumed spicy feed does not harm",spicy.life().spicyTicks()==0&&spicy.body().filling()==12&&spicy.getHealth()==5);tick(spicy,1);
        check("spicy feed does not receive generic healing",spicy.getHealth()==5&&spicy.life().spicyTicks()==240&&spicy.life().nutrition()==52);
        tick(spicy,80);check("spice expels one finite portion and causes internal damage",spicy.body().filling()==11&&spicy.getHealth()==4&&spicy.body().wound()==0);
        var physical=level.getEntitiesOfClass(ItemEntity.class,AREA,it->it.getItem().is(ModItems.FILLING.get()));
        if(physical.isEmpty()){
            var debug=new ArrayList<String>();for(var it:level.getAllEntities())if(it instanceof ItemEntity drop)debug.add(it.position()+" "+drop.getItem());
            P0Control.write("consumable-drop-debug",debug);P0Control.write("consumable-mouth-debug",io.github.longshou.mcyukkuri.body.MouthPlacement.position(spicy).toString());
        }
        var vomit=physical.isEmpty()?null:physical.getFirst();
        check("vomit uses actual mouth position and origin identity",vomit!=null&&vomit.getY()>spicy.getY()+.05&&io.github.longshou.mcyukkuri.body.FillingResource.data(vomit.getItem()).getUUID("Source").equals(spicy.getUUID()));
        spicy.life().load(spicy.life().save());check("spicy consequence remains saved",spicy.life().spicyTicks()==160);spicy.life().drink(80);
        check("drink can remove remaining spicy irritation",spicy.life().spicyTicks()==0);
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=900,batch="consumable_birth")
    public static void assistedBirth(GameTestHelper helper){
        var test=new ConsumableGameTests();var level=helper.getLevel();floor(level);
        helper.startSequence().thenWaitUntil(()->chunksReady(helper))
                .thenExecute(()->{try{test.birthRules(level);}catch(Throwable error){test.check("unexpected birth "+error,false);P0Control.write("consumable-birth-error",java.util.Arrays.toString(error.getStackTrace()));}
                    test.finish(helper,"consumable-birth-checks");});
    }
    private void birthRules(ServerLevel level){
        var data=io.github.longshou.mcyukkuri.family.FamilyData.get(level);
        var world=io.github.longshou.mcyukkuri.life.YukkuriWorldData.get(level);
        var e=spawn(level,1940.5);
        var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"DoseTest"));
        player.moveTo(1941.5,65,8.5);player.getAbilities().instabuild=false;level.addNewPlayer(player);
        try{
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Consumables.ITEMS.get(Kind.REPRODUCTIVE).get(),4));
            check("assistance requires a usable home",!data.canAssist(e));
            for(int x=1935;x<=1970;x++)for(int z=3;z<=13;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
            check("birth fixture claims real natural shelter",e.life().assignNaturalHome(e.blockPosition()));
            var base=e.family().save();var altered=base.copy();altered.putString("Stage","JUVENILE");e.family().load(altered);
            check("juvenile cannot receive reproductive filling",!data.canAssist(e));e.family().load(base);
            altered=base.copy();altered.putBoolean("Sterile",true);e.family().load(altered);check("sterile mother cannot receive dose",!data.canAssist(e));e.family().load(base);
            altered=base.copy();altered.putLong("Age",e.family().lifespan()-1);e.family().load(altered);check("elder mother cannot receive dose",!data.canAssist(e));e.family().load(base);
            e.setNoAi(true);check("NoAI cannot reserve an unadvanceable birth",!data.canAssist(e));e.setNoAi(false);
            var human=new net.minecraft.nbt.CompoundTag();human.putUUID("Owner",java.util.UUID.randomUUID());human.putString("Use","breeder");io.github.longshou.mcyukkuri.human.HumanCommerce.set(e,human);
            check("unloaded factory owner cannot bypass factory budget",!data.canAssist(e));io.github.longshou.mcyukkuri.human.HumanCommerce.set(e,new net.minecraft.nbt.CompoundTag());
            float nutrition=e.life().nutrition();var life=e.life().save();var hungry=life.copy();hungry.putFloat("Nutrition",60);e.life().load(hungry);
            check("insufficient nutrition refuses without consuming the dose",!e.operations().beginAssistance(player,net.minecraft.world.InteractionHand.MAIN_HAND)&&player.getMainHandItem().getCount()==4);e.life().load(life);
            check("dose reserves one real portion",e.operations().beginAssistance(player,net.minecraft.world.InteractionHand.MAIN_HAND)&&player.getMainHandItem().getCount()==3&&e.operations().inserted().getCount()==1);
            for(int i=0;i<20;i++)e.operations().tick();check("partial operation has no pregnancy or nutrition charge",e.family().brood()==null&&e.life().nutrition()==nutrition);
            var pending=e.operations().save();e.operations().load(pending);e.operations().tick();int returned=drops(level,Consumables.ITEMS.get(Kind.REPRODUCTIVE).get());
            check("mid-operation reload cancels and returns the original portion",returned==1&&!e.operations().active());e.operations().tick();check("pending return clears exactly once",drops(level,Consumables.ITEMS.get(Kind.REPRODUCTIVE).get())==returned);
            check("dose can restart after cancellation",e.operations().beginAssistance(player,net.minecraft.world.InteractionHand.MAIN_HAND));
            player.moveTo(1950,65,8.5);e.operations().tick();check("leaving contact range returns dose without conception",e.family().brood()==null&&!e.operations().active()&&drops(level,Consumables.ITEMS.get(Kind.REPRODUCTIVE).get())==returned+1);
            player.moveTo(1941.5,65,8.5);
            int serial=0;while(data.offspringCount(e.family().adultScale(),e.getUUID().getMostSignificantBits()^serial)!=2)serial++;
            var selected=e.family().save();selected.putInt("Conceptions",serial);e.family().load(selected);
            check("eligible adult starts committed assistance",e.operations().beginAssistance(player,net.minecraft.world.InteractionHand.MAIN_HAND));
            for(int i=0;i<39;i++)e.operations().tick();check("dose waits for the final contact tick",e.family().brood()==null);
            e.operations().tick();var brood=e.family().brood();
            check("completion creates one live litter with unknown father",brood!=null&&brood.kind==io.github.longshou.mcyukkuri.family.FamilyData.BirthKind.LIVE&&brood.father==null&&brood.children.size()==2);
            if(brood==null)throw new IllegalStateException("Assistance refused: "+e.family().save()+" "+e.life().save());
            var ids=brood.children.stream().map(c->c.id).toList();var broodId=brood.id;
            check("birth charges existing nutritional budget once",e.life().nutrition()==nutrition-14&&e.family().conceptions()==serial+1&&e.operations().inserted().isEmpty());
            int remaining=player.getMainHandItem().getCount();
            check("repeat dose cannot reset existing litter or take an item",!e.operations().beginAssistance(player,net.minecraft.world.InteractionHand.MAIN_HAND)&&player.getMainHandItem().getCount()==remaining&&e.family().brood().id.equals(broodId));
            e.family().load(e.family().save());var savedData=io.github.longshou.mcyukkuri.family.FamilyData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
            check("entity and family save preserve litter child IDs and unknown father",e.family().brood().id.equals(broodId)&&savedData.brood(broodId).father==null&&savedData.brood(broodId).children.stream().map(c->c.id).toList().equals(ids));
            check("birth cannot commit before full emergence",data.birth(brood,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(e,0))==null);
            int elapsed=brood.elapsed;e.setNoAi(true);for(int i=0;i<30;i++)e.family().tick();check("NoAI and unloaded-style pause never advances gestation",brood.elapsed==elapsed);e.setNoAi(false);
            var position=e.position();var uuid=e.getUUID();var token=world.capture(e);
            check("carrier reserves the real maternal identity",token!=null&&world.isCaptured(uuid));
            var restored=world.release(token,level,position,0);check("carrier restore keeps pregnancy and child IDs",restored!=null&&restored.getUUID().equals(uuid)&&restored.family().brood().id.equals(broodId)&&brood.elapsed==elapsed);
            check("duplicate carrier token cannot create a second mother",world.release(token,level,position.add(2,0,0),0)==null);e=restored;e.setOnGround(true);
            e.setYRot(20);e.yBodyRot=20;e.setYHeadRot(20);
            brood.elapsed=brood.duration;e.syncFamily();e.family().tick();
            var target=BlockPos.containing(io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(e,0));
            level.setBlockAndUpdate(target,Blocks.STONE.defaultBlockState());elapsed=brood.elapsed;for(int i=0;i<10;i++)e.family().tick();
            check("blocked front outlet pauses delivery without birth",brood.elapsed==elapsed&&brood.unborn()==2);level.setBlockAndUpdate(target,Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(target.below(),Blocks.AIR.defaultBlockState());for(int i=0;i<10;i++)e.family().tick();check("missing ground support pauses delivery",brood.elapsed==elapsed&&brood.unborn()==2);level.setBlockAndUpdate(target.below(),Blocks.STONE.defaultBlockState());
            e.setPos(e.position().add(.1,0,0));e.family().tick();check("maternal movement pauses active emergence",brood.elapsed==elapsed);e.setPos(position);e.setOnGround(true);e.family().tick();
            for(int i=0;i<50&&!brood.children.getFirst().born;i++)e.family().tick();
            var first=level.getEntity(ids.getFirst()) instanceof YukkuriEntity child?child:null;
            check("first live child is a real small baby at front outlet",first!=null&&first.family().stage()==io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY&&Math.abs(first.family().scale()-io.github.longshou.mcyukkuri.family.LiveBirthGeometry.BABY_SCALE)<.0001&&first.getZ()>e.getZ());
            check("child retains real mother unknown father and birth identity",first!=null&&first.family().mother().equals(uuid)&&first.family().father()==null&&first.family().birthId().equals(broodId));
            check("real newborn retains the emergence facing",first!=null&&first.getYRot()==20&&first.yBodyRot==20&&first.getYHeadRot()==20);
            var cognition=first==null?null:io.github.longshou.mcyukkuri.cognition.Cognition.get(first);
            check("birth initializes innate cognition with real ancestry and zero learned points",cognition!=null&&cognition.origin().equals("mc_birth_v1")&&cognition.learned()==0&&uuid.equals(cognition.mother())&&cognition.father()==null);
            check("repeated cognition birth cannot reroll the newborn",first!=null&&!io.github.longshou.mcyukkuri.cognition.Cognition.born(first,uuid,null)&&io.github.longshou.mcyukkuri.cognition.Cognition.get(first).equals(cognition));
            check("already born child cannot spawn twice",data.birth(brood,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(e,0))==null);
            elapsed=brood.elapsed;for(int i=0;i<80;i++)e.family().tick();check("stationary first sibling occupies the exit and delays the next",brood.unborn()==1&&brood.elapsed==elapsed);
            if(first!=null)first.moveTo(1945,65,11,0,0);
            for(int i=0;i<100&&!brood.complete();i++)e.family().tick();
            check("second child completes the original litter",brood.complete()&&level.getEntity(ids.get(1)) instanceof YukkuriEntity&&e.family().brood()==null);
            check("postpartum body returns gradually instead of creating a new litter",e.family().liveBelly()>0&&e.family().conceptions()==serial+1);for(int i=0;i<71;i++)e.family().tick();check("postpartum recovery reaches ordinary body dimensions",e.family().liveBelly()==0);
            var mother2=spawn(level,1965.5);mother2.life().assignNaturalHome(mother2.blockPosition());var orphaned=data.assist(mother2);
            check("second eligible mother can conceive separately",orphaned!=null);mother2.hurt(level.damageSources().generic(),100);
            check("maternal death cancels only unborn children and releases their reservation",orphaned!=null&&orphaned.cancelled&&orphaned.children.stream().allMatch(c->data.person(c.id).status==io.github.longshou.mcyukkuri.family.FamilyData.Status.LOST));
            check("original live siblings survive another maternal death",brood.children.stream().allMatch(c->data.person(c.id).status==io.github.longshou.mcyukkuri.family.FamilyData.Status.ALIVE));
            var other=spawn(level,1938.5);check("natural birth choice is stable before conception",e.family().naturalBirthKind(other)==e.family().naturalBirthKind(other));
        }finally{player.discard();}
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="consumable_birth_live")
    public static void birthOnServerTicks(GameTestHelper helper){
        var test=new ConsumableGameTests();var level=helper.getLevel();floor(level);
        var actor=new YukkuriEntity[1];var batch=new io.github.longshou.mcyukkuri.family.FamilyData.Brood[1];
        var caregiver=new net.minecraft.server.level.ServerPlayer[1];
        var trace=new ArrayList<String>();
        helper.startSequence().thenWaitUntil(()->chunksReady(helper)).thenExecute(()->{
            for(int x=1935;x<=1950;x++)for(int z=3;z<=13;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
            var e=spawn(level,1940.5);actor[0]=e;e.life().assignNaturalHome(e.blockPosition());
            var family=e.family().save();int serial=0;while(io.github.longshou.mcyukkuri.family.FamilyData.offspringCount(e.family().adultScale(),e.getUUID().getMostSignificantBits()^serial)!=2)serial++;
            family.putInt("Conceptions",serial);e.family().load(family);
            var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(),"LiveDose"));
            player.moveTo(1941.5,65,8.5);player.getAbilities().instabuild=false;level.addNewPlayer(player);caregiver[0]=player;
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new ItemStack(Consumables.ITEMS.get(Kind.REPRODUCTIVE).get(),2));
            test.check("outer Player.interactOn enters a real dose operation",player.interactOn(e,net.minecraft.world.InteractionHand.MAIN_HAND).consumesAction());
        }).thenExecuteFor(45,()->{
            var e=actor[0];trace.add("tick="+e.tickCount+" pose="+e.operations().pose()+" pos="+e.position()+" ground="+e.onGround()+" ready="+e.family().readyForAssistance()+" daily="+e.dailyLife().active()+" player="+caregiver[0].position()+" nutrition="+e.life().nutrition()+" pregnancy="+(e.family().brood()!=null));
        }).thenExecute(()->{
            P0Control.write("consumable-operation-trace",trace);
            batch[0]=actor[0].family().brood();test.check("real server ticks commit exactly one reserved dose",batch[0]!=null&&caregiver[0].getMainHandItem().getCount()==1&&!actor[0].operations().active());
            if(batch[0]!=null){batch[0].duration=200;io.github.longshou.mcyukkuri.family.FamilyData.get(level).setDirty();}
            caregiver[0].discard();
        }).thenExecuteFor(650,()->{}).thenExecute(()->{
            var brood=batch[0];
            test.check("real loaded family AI completes live gestation and birth",brood!=null&&brood.complete()&&level.getEntity(brood.children.getFirst().id) instanceof YukkuriEntity);
            if(brood!=null){
                var newborn=level.getEntity(brood.children.getFirst().id) instanceof YukkuriEntity child?child:null;
                test.check("both real children land awake on support",brood.children.size()==2&&brood.children.stream().allMatch(c->level.getEntity(c.id) instanceof YukkuriEntity baby&&baby.onGround()&&!baby.family().awaitingLanding()));
                test.check("live ticking preserves parent identity and nutritional debit",newborn!=null&&newborn.family().mother().equals(actor[0].getUUID())&&newborn.family().father()==null&&actor[0].life().nutrition()<88);
                P0Control.write("consumable-live-birth-state",java.util.Map.of("elapsed",brood.elapsed,"duration",brood.duration,"born",brood.complete(),"mother",actor[0].family().save().toString(),"life",actor[0].life().save().toString()));
                io.github.longshou.mcyukkuri.family.FamilyData.get(level).cancel(brood);
            }
            test.finish(helper,"consumable-live-birth-checks");
        });
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=700,batch="consumable_morph_birth")
    public static void morphBirths(GameTestHelper helper){
        var test=new ConsumableGameTests();var level=helper.getLevel();floor(level);
        helper.startSequence().thenWaitUntil(()->chunksReady(helper)).thenExecute(()->{
            try{test.morphBirths(level);}catch(Throwable error){test.check("unexpected morph birth failure "+error,false);P0Control.write("consumable-morph-error",java.util.Arrays.toString(error.getStackTrace()));}
            test.finish(helper,"consumable-morph-checks");
        });
    }
    private void morphBirths(ServerLevel level){
        for(int x=1934;x<=1967;x++)for(int z=3;z<=13;z++)level.setBlockAndUpdate(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState());
        var data=io.github.longshou.mcyukkuri.family.FamilyData.get(level);
        int[] shapes={48,2,1};String[] assets={"reimu_bean","reimu_wasa","marisa_tsumuri"};
        for(int scene=0;scene<shapes.length;scene++){
            var variant=scene==2?io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA:io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU;
            var mother=spawn(level,1940.5+scene*10,variant);mother.life().assignNaturalHome(mother.blockPosition());
            var family=mother.family().save();int serial=0;while(io.github.longshou.mcyukkuri.family.FamilyData.offspringCount(mother.family().adultScale(),mother.getUUID().getMostSignificantBits()^serial)!=1)serial++;
            family.putInt("Conceptions",serial);mother.family().load(family);var brood=data.assist(mother);
            if(brood==null)throw new IllegalStateException("Morph mother rejected");var child=brood.children.getFirst();
            check("conception fixes UUID-derived morph before birth "+assets[scene],child.subspeciesFlags==io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.birthFlags(child.id,variant,mother.subspecies().kind(),null));
            // Force rare input flags in this fixture instead of depending on a one-in-hundreds random spawn.
            child.subspeciesFlags=shapes[scene];brood.elapsed=brood.duration+36;data.setDirty();mother.syncFamily();
            var saved=io.github.longshou.mcyukkuri.family.FamilyData.load(data.save(new net.minecraft.nbt.CompoundTag(),level.registryAccess()),level.registryAccess());
            check("unborn morph survives save and selects the matching model "+assets[scene],saved.brood(brood.id).children.getFirst().subspeciesFlags==shapes[scene]&&mother.liveChildAssetId(0).equals(assets[scene]));
            if(scene==0){
                child.subspeciesFlags=0;var normal=io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(mother,0);
                double normalTop=normal.y+io.github.longshou.mcyukkuri.family.LiveBirthGeometry.childDimensions(child,mother.getScale()).height();
                child.subspeciesFlags=48;var bean=io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(mother,0);
                double beanTop=bean.y+io.github.longshou.mcyukkuri.family.LiveBirthGeometry.childDimensions(child,mother.getScale()).height();
                check("fixture lip can separate the current ordinary and bean newborn heights",beanTop>normalTop);
                var overhang=net.minecraft.world.entity.EntityType.SHULKER.create(level);
                overhang.setNoAi(true);overhang.setNoGravity(true);overhang.moveTo(bean.x,(normalTop+beanTop)*.5,bean.z+.45,0,0);level.addFreshEntity(overhang);
                // Shulkers snap their location to a block on addition; the fixture needs an exact low lip.
                double lip=(normalTop+beanTop)*.5;
                overhang.setBoundingBox(new AABB(bean.x-.5,lip,bean.z-.05,bean.x+.5,lip+1,bean.z+.95));
                try{
                    child.subspeciesFlags=0;
                    check("ordinary newborn fits the low solid-entity overhang",io.github.longshou.mcyukkuri.family.LiveBirthGeometry.clear(mother,child,0));
                    child.subspeciesFlags=48;mother.syncFamily();
                    check("taller bean is rejected before addition under the same overhang",!io.github.longshou.mcyukkuri.family.LiveBirthGeometry.clear(mother,child,0)
                            &&data.birth(brood,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(mother,0))==null&&level.getEntity(child.id)==null);
                }finally{overhang.discard();child.subspeciesFlags=48;}
                mother.subspecies().set(io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.WASA_REIMU,io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Congenital.NORMAL);
            }
            var born=data.birth(brood,0,level,io.github.longshou.mcyukkuri.family.LiveBirthGeometry.position(mother,0));
            check("real birth uses the saved morph rather than rerolling current parents "+assets[scene],born!=null&&born.subspecies().assetId().equals(assets[scene])&&born.family().father()==null);
            if(scene==0)check("bean collision height is already correct on world insertion",born!=null&&Math.abs(born.getBbHeight()-ModEntities.REIMU.get().getDimensions().height()*io.github.longshou.mcyukkuri.family.LiveBirthGeometry.BABY_SCALE*1.55f)<.0001);
            if(scene==0&&born!=null){
                var growth=born.family().save();growth.putLong("Nourishment",born.family().maturityAge()/4);growth.putLong("Age",0);born.family().load(growth);
                float grown=io.github.longshou.mcyukkuri.family.GrowthSize.scale(io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY,born.family().maturityAge()/4,born.family().maturityAge(),1,false);
                var roof=net.minecraft.world.entity.EntityType.SHULKER.create(level);roof.setNoAi(true);roof.setNoGravity(true);
                roof.moveTo(born.getX(),born.getY()+ModEntities.REIMU.get().getDimensions().height()*grown*(1+1.55)*.5,born.getZ()+.45,0,0);level.addFreshEntity(roof);
                double lip=born.getY()+ModEntities.REIMU.get().getDimensions().height()*grown*(1+1.55)*.5;
                roof.setBoundingBox(new AABB(born.getX()-.5,lip,born.getZ()-.05,born.getX()+.5,lip+1,born.getZ()+.95));
                try{check("bean growth checks its taller target shape under a low overhang",!born.family().woke()&&Math.abs(born.family().scale()-io.github.longshou.mcyukkuri.family.LiveBirthGeometry.BABY_SCALE)<.0001);}
                finally{roof.discard();}
                check("bean growth resumes after real clearance becomes available",born.family().woke()&&Math.abs(born.family().scale()-grown)<.0001);
            }
        }
    }
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=800,batch="consumable_live")
    public static void naturalDrinking(GameTestHelper helper){
        var test=new ConsumableGameTests();var level=helper.getLevel();floor(level);var actor=new YukkuriEntity[1];
        long[] readyTick={-1};
        helper.startSequence().thenWaitUntil(()->chunksReady(helper))
        .thenExecute(()->{
            readyTick[0]=helper.getTick();P0Control.write("consumable-drink-readiness",java.util.Map.of("readyTick",readyTick[0]));
            var e=spawn(level,1904.5);var state=e.life().save();state.putFloat("Dryness",85);state.putFloat("Bladder",80);e.life().load(state);actor[0]=e;
            level.setBlockAndUpdate(new BlockPos(1908,64,8),Blocks.WATER.defaultBlockState());
            test.check("thirst plans a real water route",e.life().plan()&&e.life().activity().equals("seek_water"));
        }).thenExecuteFor(300,()->{}).thenExecute(()->{
            var e=actor[0];P0Control.write("consumable-drink-debug",e.position()+" "+e.life().activity()+" dryness="+e.life().dryness()+"; readyTick="+readyTick[0]+"; endTick="+helper.getTick()+"; entityTicks="+e.tickCount);test.check("natural AI approaches water and drinks",e.life().dryness()<70&&!e.life().constipated());
            test.check("natural drinking stays on dry ground",!e.isInWater()&&e.getHealth()==e.getMaxHealth());
            test.check("natural water provides no nutrition",e.life().nutrition()<=100);
            test.finish(helper,"consumable-live-checks");
        });
    }
}
