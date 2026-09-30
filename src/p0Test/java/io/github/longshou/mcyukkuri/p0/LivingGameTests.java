package io.github.longshou.mcyukkuri.p0;

import com.mojang.authlib.GameProfile;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.item.NotebookItem;
import io.github.longshou.mcyukkuri.item.WashClothItem;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.social.YukkuriPersonality.Trait;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Stateful integration checks: real goals and native item/block/save APIs, accelerated game ticks. */
@PrefixGameTestTemplate(false)
public final class LivingGameTests {
    private static final AABB AREA = new AABB(80,60,0,136,85,56);
    private static final BlockPos BOWL = new BlockPos(99,65,24);
    private static final BlockPos HOME = new BlockPos(104,65,20);
    private static final BlockPos OTHER_HOME = new BlockPos(110,65,20);
    private static final BlockPos TOILET = new BlockPos(97,65,27);
    private record Check(String name, boolean pass, String detail) {}
    private final List<Check> checks = new ArrayList<>();
    private GameTestHelper helper;
    private ServerLevel level;
    private net.minecraft.gametest.framework.GameTestSequence sequence;
    private YukkuriEntity a, b;
    private FakePlayer alice, bob;
    private ItemEntity item;
    private UUID identity, token, accessoryToken;
    private ItemStack carrier;
    private CompoundTag before;
    private Vec3 position;

    @GameTest(templateNamespace=P0Mod.ID, template="empty", timeoutTicks=14000, batch="living")
    public static void livingAcceptance(GameTestHelper helper) {
        LivingGameTests test=new LivingGameTests(); test.helper=helper; test.level=helper.getLevel(); test.setup();
        test.sequence=helper.startSequence().thenWaitUntil(() -> {
            for (int x=5;x<=8;x++) for(int z=0;z<=3;z++) helper.assertTrue(
                    test.level.isPositionEntityTicking(new BlockPos(x*16+8,65,z*16+8)),
                    "Waiting for living fixture chunk " + x + "," + z + "; forced="
                            + test.level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(x,z)));
        }).thenIdle(1);
        test.resources(); test.meals(); test.facilities(); test.environment(); test.transport(); test.social();
        test.later(1,"finish",()-> {
            test.clear();
            test.level.setWeatherParameters(6000,0,false,false);
            test.level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,test.level.getServer());
        });
        test.sequence.thenIdle(1).thenExecute(()-> {
            P0Control.write("living-checks",test.checks);
            var failures=test.checks.stream().filter(check->!check.pass).toList();
            if (failures.isEmpty()) helper.succeed();
            else helper.fail(failures.size()+" living checks failed; first: "+failures.getFirst().name);
        });
    }
    private void later(int delay,String name,Runnable step) {
        sequence.thenIdle(delay).thenExecute(()-> {
            try { step.run(); } catch (Throwable error) { check(name,false,error.toString()); }
            P0Control.write("living-checks",checks);
        });
    }
    private void check(String name,boolean success,Object detail) { checks.add(new Check(name,success,String.valueOf(detail))); }
    private String state() { return a==null?"no subject":a.life().save()+"; behavior="+a.getBehaviorId()+"; position="+a.position(); }
    private void setup() {
        for (int x=5;x<=8;x++) for(int z=0;z<=3;z++) GameTestChunks.setForced(level,x,z,true);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0,level.getServer());
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
        level.setDayTime(6000);level.setWeatherParameters(6000,0,false,false);
        for(int x=80;x<136;x++)for(int z=0;z<56;z++) {
            for(int y=65;y<=70;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.SMOOTH_STONE.defaultBlockState());
        }
        alice=FakePlayerFactory.get(level,new GameProfile(UUID.fromString("5799948f-75cc-43bd-94d3-6169b65b56bc"),"LivingAlice"));
        bob=FakePlayerFactory.get(level,new GameProfile(UUID.fromString("16d559d6-8582-43dd-bdc8-601a112b1524"),"LivingBob"));
        alice.setGameMode(GameType.SURVIVAL);bob.setGameMode(GameType.SURVIVAL);
    }
    private void clear() {
        level.getEntitiesOfClass(Entity.class,AREA,e->e.getTags().contains("living_case")||e instanceof ItemEntity).forEach(Entity::discard);
        alice.getInventory().clearContent();bob.getInventory().clearContent();
        level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(true,level.getServer());
    }
    private YukkuriEntity spawn(EntityType<YukkuriEntity> type,double x,double y,double z) {
        YukkuriEntity entity=type.create(level);if(entity==null)throw new IllegalStateException("create failed");
        entity.moveTo(x,y,z,0,0);entity.addTag("living_case");entity.setPersistenceRequired();entity.getRandom().setSeed(57);
        CompoundTag life=entity.life().save();
        CompoundTag personality=life.getCompound("Personality");PersonalityFixtures.preferences(personality,new int[]{20,20,20,20,20,20});life.put("Personality",personality);
        entity.life().load(life);
        if(!level.addFreshEntity(entity))throw new IllegalStateException("spawn failed");return entity;
    }
    private void fresh() {
        clear();a=spawn(ModEntities.REIMU.get(),96.5,65,24.5);b=null;
        alice.moveTo(96.5,65,25.5);bob.moveTo(95.5,65,25.5);
    }
    private void life(YukkuriEntity entity,Consumer<CompoundTag> edit) {
        CompoundTag state=entity.life().save();edit.accept(state);entity.life().load(state);
    }
    private void born(YukkuriEntity entity,int curiosity,int social,int bold,int care,int order,int patience) {
        life(entity,data->{CompoundTag person=data.getCompound("Personality");PersonalityFixtures.preferences(person,new int[]{curiosity,social,bold,care,order,patience});data.put("Personality",person);});
    }
    private void trust(YukkuriEntity entity,UUID player,int amount) {
        life(entity,data->{CompoundTag person=data.getCompound("Personality");ListTag memories=person.getList("Players",10);
            CompoundTag memory=new CompoundTag();memory.putUUID("Player",player);memory.putInt("Trust",amount);memories.add(memory);person.put("Players",memories);data.put("Personality",person);});
    }
    private ItemEntity drop(Item value,int count,double x,double y,double z) {
        ItemEntity result=new ItemEntity(level,x,y,z,new ItemStack(value,count));result.setDeltaMovement(Vec3.ZERO);result.setNoPickUpDelay();result.addTag("living_case");level.addFreshEntity(result);return result;
    }
    private int ground(Item value) { return level.getEntitiesOfClass(ItemEntity.class,AREA,e->e.getItem().is(value)).stream().mapToInt(e->e.getItem().getCount()).sum(); }
    private UseOnContext useAt(FakePlayer player,ItemStack stack,BlockPos pos) {
        player.setItemInHand(InteractionHand.MAIN_HAND,stack);
        return new UseOnContext(player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));
    }
    private CareBlockEntity place(BlockPos pos,Block block) {
        level.setBlockAndUpdate(pos,block.defaultBlockState());return (CareBlockEntity)level.getBlockEntity(pos);
    }
    private void roof(BlockPos center) {
        for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)level.setBlockAndUpdate(center.offset(x,3,z),Blocks.OAK_PLANKS.defaultBlockState());
    }

    private void resources() {
        later(1,"resources",()-> {
            fresh();
            for(String name:List.of("field_notebook","food_bowl","yukkuri_carrier","yukkuri_nest","yukkuri_toilet","wash_cloth","yarn_ball","teaching_whistle"))
                check("recipe decoded "+name,level.getRecipeManager().byKey(MCYukkuri.id(name)).isPresent(),name);
            var biome=level.registryAccess().registryOrThrow(Registries.BIOME).get(Biomes.PLAINS);
            var spawns=biome.getMobSettings().getMobs(MobCategory.CREATURE).unwrap();
            check("plains discovers both ordinary variants",spawns.stream().anyMatch(s->s.type==ModEntities.REIMU.get())
                    && spawns.stream().anyMatch(s->s.type==ModEntities.MARISA.get()),spawns);
            for (var type : List.of(ModEntities.CHEN, ModEntities.ALICE, ModEntities.PATCHOULI))
                check("new species biome spawn " + type.getId(), spawns.stream().anyMatch(s -> s.type == type.get()), spawns);
            check("house variants are not normal biome spawns",spawns.stream().noneMatch(s->s.type==ModEntities.HOUSE_REIMU.get()||s.type==ModEntities.HOUSE_MARISA.get()),spawns);
            level.setBlockAndUpdate(new BlockPos(82,64,3),Blocks.GRASS_BLOCK.defaultBlockState());
        });
        later(25,"spawn cap",()-> {
            clear();BlockPos spot=new BlockPos(82,65,3);
            AABB populationArea=new AABB(spot).inflate(32);
            int cap=YukkuriConfig.LOCAL_SPAWN_CAP.get();
            int emptyCount=level.getEntitiesOfClass(YukkuriEntity.class,populationArea).size();
            check("spawn cap fixture starts empty",emptyCount==0,"expected=0; observed="+emptyCount+"; spot="+spot);
            check("ordinary spawn predicate accepts dry daylight",SpawnPlacements.checkSpawnRules(ModEntities.REIMU.get(),level,MobSpawnType.NATURAL,spot,level.random),"observed="+emptyCount+"; spot="+spot);
            for(int i=0;i<YukkuriConfig.LOCAL_SPAWN_CAP.get();i++)spawn(ModEntities.REIMU.get(),83+i,65,4).setNoAi(true);
            int population=level.getEntitiesOfClass(YukkuriEntity.class,populationArea).size();
            String detail="cap="+cap+"; observed="+population+"; entityTicking="+level.isPositionEntityTicking(spot)+"; tick="+helper.getTick();
            check("spawn cap fixture population is query-visible",population==cap,detail);
            check("local population cap rejects another spawn",!SpawnPlacements.checkSpawnRules(ModEntities.REIMU.get(),level,MobSpawnType.NATURAL,spot,level.random),detail);
        });
    }
    private void meals() {
        later(1,"hand feed starts",()-> {
            fresh();a.setHealth(6);alice.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE,3));a.interact(alice,InteractionHand.MAIN_HAND);
            check("hand feed owns one real item before nutrition",alice.getMainHandItem().getCount()==2&&a.life().mouth().getCount()==1&&a.getHealth()==6&&a.life().nutrition()==60,state());
            a.interact(alice,InteractionHand.MAIN_HAND);
            check("second hand feed cannot reserve another item",alice.getMainHandItem().getCount()==2,state());
        });
        later(20,"chew",()->check("chewing holds food without healing",a.life().mouth().getCount()==1&&a.getHealth()==6&&a.getBehaviorId().equals("chew"),state()));
        later(55,"swallow",()-> {
            check("swallow consumes held food and grants nutrition once",a.life().mouth().isEmpty()&&a.life().nutrition()==80&&a.getHealth()==8,state());
            a.interact(alice,InteractionHand.MAIN_HAND);check("full nutrition rejects food even when injured",alice.getMainHandItem().getCount()==2,state());
        });
        later(1,"meal interruption",()-> {
            fresh();alice.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE));a.interact(alice,InteractionHand.MAIN_HAND);
            a.hurt(level.damageSources().generic(),1);
            check("injury returns the one unswallowed item",a.life().mouth().isEmpty()&&a.life().nutrition()==60&&ground(Items.APPLE)==1,state());
            a.life().interrupt();check("repeated interruption cannot duplicate food",ground(Items.APPLE)==1,state());
        });
        later(1,"meal NBT",()-> {
            fresh();a.setHealth(6);alice.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE));a.interact(alice,InteractionHand.MAIN_HAND);
        });
        later(20,"save chewing",()-> {
            identity=a.getUUID();before=new CompoundTag();a.save(before);a.discard();
            a=(YukkuriEntity)EntityType.loadEntityRecursive(before,level,e->e);level.addFreshEntity(a);
            check("NBT preserves identity and unswallowed ownership",a.getUUID().equals(identity)&&a.life().mouth().getCount()==1&&a.life().nutrition()==60,state());
        });
        later(60,"restored swallow",()->check("restored meal settles once",a.life().mouth().isEmpty()&&a.life().nutrition()==80&&a.getHealth()==8&&ground(Items.APPLE)==0,state()));
        later(1,"ground stack",()-> {fresh();item=drop(Items.APPLE,3,99.5,65.1,24.5);});
        later(180,"ground stack reached",()->check("distant stack loses one item after complete meal",item.isAlive()&&item.getItem().getCount()==2&&a.life().nutrition()==80&&a.life().mouth().isEmpty(),state()));
        later(1,"bowl",()-> {fresh();place(BOWL,ModBlocks.FOOD_BOWL.get()).setItem(0,new ItemStack(Items.APPLE,3));});
        later(180,"bowl eaten",()-> {
            CareBlockEntity bowl=(CareBlockEntity)level.getBlockEntity(BOWL);
            check("bowl transfers only one serving",bowl.foodCount()==2&&a.life().nutrition()==80,state()+"; bowl="+bowl.foodCount());
            CompoundTag saved=bowl.saveWithFullMetadata(level.registryAccess());
            CareBlockEntity copy=new CareBlockEntity(BOWL,bowl.getBlockState());copy.loadWithComponents(saved,level.registryAccess());
            check("bowl inventory survives native NBT",copy.foodCount()==bowl.foodCount(),saved);
            a.setNoAi(true);level.destroyBlock(BOWL,true);
            check("breaking bowl drops its remaining food once",ground(Items.APPLE)==2,ground(Items.APPLE));
        });
        later(1,"bowl empty resumes search",()-> {
            fresh();place(BOWL,ModBlocks.FOOD_BOWL.get());item=drop(Items.APPLE,1,100.5,65.1,25.5);
        });
        later(180,"empty bowl fallback",()->check("empty bowl does not trap food search",!item.isAlive()&&a.life().nutrition()==80,state()));
        later(1,"unreachable nearest bowl",()-> {
            fresh();place(BOWL,ModBlocks.FOOD_BOWL.get()).setItem(0,new ItemStack(Items.APPLE));
            for(int x=98;x<=100;x++)for(int z=23;z<=25;z++)if(x!=99||z!=24)
                for(int y=65;y<=67;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.STONE.defaultBlockState());
            place(new BlockPos(102,65,27),ModBlocks.FOOD_BOWL.get()).setItem(0,new ItemStack(Items.APPLE));
        });
        later(250,"reachable alternative bowl",()-> {
            check("unreachable nearest bowl does not starve a consumer",((CareBlockEntity)level.getBlockEntity(BOWL)).foodCount()==1
                    &&((CareBlockEntity)level.getBlockEntity(new BlockPos(102,65,27))).foodCount()==0&&a.life().nutrition()>=79,state());
            ((CareBlockEntity)level.getBlockEntity(BOWL)).clearContent();
            level.setBlockAndUpdate(new BlockPos(102,65,27),Blocks.AIR.defaultBlockState());
            for(int x=98;x<=100;x++)for(int z=23;z<=25;z++)if(x!=99||z!=24)
                for(int y=65;y<=67;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
        });
        later(1,"food competition",()-> {
            fresh();b=spawn(ModEntities.MARISA.get(),96.7,65,24.8);life(a,d->d.putFloat("Nutrition",40));life(b,d->d.putFloat("Nutrition",40));
            item=drop(Items.APPLE,1,96.6,65.1,24.6);
        });
        later(140,"competition result",()-> {
            check("two consumers cannot both eat one item",!item.isAlive()&&Math.abs(a.life().nutrition()+b.life().nutrition()-100)<.1,state()+"; other="+b.life().nutrition());
            var data=YukkuriWorldData.get(level);
            check("competition affects an actual nearby observer",data.affinity(a.getUUID(),b.getUUID())<0||data.affinity(b.getUUID(),a.getUUID())<0,"A->B="+data.affinity(a.getUUID(),b.getUUID())+" B->A="+data.affinity(b.getUUID(),a.getUUID()));
        });
        later(1,"sharing",()-> {
            fresh();b=spawn(ModEntities.MARISA.get(),97.2,65,24.5);life(b,d->d.putFloat("Nutrition",20));born(a,20,20,50,100,80,80);
            alice.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.APPLE));a.interact(alice,InteractionHand.MAIN_HAND);
        });
        later(100,"share result",()-> {
            check("sharing transfers existing food instead of copying it",a.life().nutrition()==60&&b.life().nutrition()==40&&a.life().mouth().isEmpty()&&b.life().mouth().isEmpty(),state()+"; other="+b.life().nutrition());
            check("recipient remembers the donor directionally",YukkuriWorldData.get(level).affinity(b.getUUID(),a.getUUID())>=8,YukkuriWorldData.get(level).affinity(b.getUUID(),a.getUUID()));
        });
        later(1,"mob griefing",()-> {fresh();place(BOWL,ModBlocks.FOOD_BOWL.get()).setItem(0,new ItemStack(Items.APPLE,2));level.getGameRules().getRule(GameRules.RULE_MOBGRIEFING).set(false,level.getServer());});
        later(100,"protected bowl",()->check("mobGriefing protects facility food",((CareBlockEntity)level.getBlockEntity(BOWL)).foodCount()==2&&a.life().mouth().isEmpty(),state()));
    }

    private void facilities() {
        later(1,"home assignment",()-> {
            fresh();((CareBlockEntity)level.getBlockEntity(BOWL)).clearContent();level.setBlockAndUpdate(BOWL,Blocks.AIR.defaultBlockState());roof(HOME);roof(OTHER_HOME);
            place(HOME,ModBlocks.NEST.get());place(OTHER_HOME,ModBlocks.NEST.get());
            a.moveTo(104.5,65,17.5,0,0);b=spawn(ModEntities.MARISA.get(),105.5,65,17.5);
        });
        later(5,"claim settled home",()-> {
            CareBlockEntity first=(CareBlockEntity)level.getBlockEntity(HOME);
            check("reachable sheltered nest can be claimed",a.life().assignHome(GlobalPos.of(level.dimension(),HOME)),state());
            check("nest capacity prevents a second resident",!b.life().assignHome(GlobalPos.of(level.dimension(),HOME))&&a.getUUID().equals(first.resident()),first.resident());
            b.discard();
            life(a,d->{d.putFloat("Nutrition",90);d.putFloat("Energy",80);});
            level.setBlockAndUpdate(HOME,Blocks.AIR.defaultBlockState());
        });
        later(240,"home replacement",()->check("destroyed home is replaced by another nest",a.life().home()!=null&&a.life().home().pos().equals(OTHER_HOME)&&!a.life().naturalHome(),state()));
        later(1,"rain shelter",()-> {
            b.discard();a.moveTo(110.5,65,15.5,0,0);level.setWeatherParameters(0,600,true,false);position=a.position();
        });
        later(160,"shelter reached",()->check("rain sends resident to actual cover",!level.canSeeSky(a.blockPosition())&&a.position().distanceTo(position)>1&&a.life().wetness()<60,state()));
        later(1,"sleep setup",()-> {
            level.setWeatherParameters(6000,0,false,false);a.moveTo(110.5,65,19.5,0,0);life(a,d->{d.putFloat("Energy",5);d.putInt("RestWait",0);});
        });
        later(45,"sleep enters",()->check("sleep has an enter phase then recovers energy",a.life().isSleeping()&&a.life().energy()>5,state()));
        later(260,"sleep wakes",()->check("sleep recovers and wakes",!a.life().isSleeping()&&a.life().energy()>=90,state()));
        later(1,"toilet setup",()-> {
            fresh();place(TOILET,ModBlocks.TOILET.get());life(a,d->{d.putFloat("Nutrition",90);d.putFloat("Bladder",80);});
        });
        later(180,"toilet used",()-> {
            CareBlockEntity toilet=(CareBlockEntity)level.getBlockEntity(TOILET);
            check("toilet accounts for actual need",toilet.waste()==2&&a.life().bladder()==0,state()+" waste="+toilet.waste());
            while(toilet.relieve()){}
            check("toilet capacity is finite",toilet.waste()==8,toilet.waste());
            ItemStack cloth=new ItemStack(ModItems.WASH_CLOTH.get());CustomData.update(DataComponents.CUSTOM_DATA,cloth,data->data.putInt("Water",2));
            var context=useAt(alice,cloth,TOILET);ModItems.WASH_CLOTH.get().useOn(context);
            int waste=alice.getInventory().countItem(ModItems.WASTE.get());
            check("cleaning transfers accumulated waste once",toilet.waste()==0&&waste==8&&WashClothItem.water(cloth)==1,waste);
            ModItems.WASH_CLOTH.get().useOn(context);
            check("cleaning an empty tray creates no more waste",alice.getInventory().countItem(ModItems.WASTE.get())==8&&WashClothItem.water(cloth)==1,WashClothItem.water(cloth));
        });
        later(1,"washing",()-> {
            fresh();life(a,d->{d.putFloat("Hygiene",30);d.putFloat("Wetness",0);});
            ItemStack cloth=new ItemStack(ModItems.WASH_CLOTH.get());CustomData.update(DataComponents.CUSTOM_DATA,cloth,data->data.putInt("Water",1));
            ModItems.WASH_CLOTH.get().interactLivingEntity(cloth,alice,a,InteractionHand.MAIN_HAND);
            check("brief wash cleans without immersion damage",a.life().hygiene()==70&&a.life().wetness()==5&&a.getHealth()==10&&WashClothItem.water(cloth)==0,state());
            ModItems.WASH_CLOTH.get().interactLivingEntity(cloth,alice,a,InteractionHand.MAIN_HAND);
            check("dry cloth cannot clean or gain more trust",a.life().hygiene()==70&&a.life().personality().trust(alice.getUUID())==4,state());
        });
    }

    private void environment() {
        later(1,"water avoidance",()-> {
            fresh();a.moveTo(84.5,65,42.5,0,0);
            for(int x=82;x<=93;x++)for(int y=65;y<=67;y++) {
                level.setBlockAndUpdate(new BlockPos(x,y,41),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x,y,43),Blocks.STONE.defaultBlockState());
            }
            for(int y=65;y<=67;y++){level.setBlockAndUpdate(new BlockPos(82,y,42),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(new BlockPos(93,y,42),Blocks.STONE.defaultBlockState());}
            for(int x=87;x<=89;x++){level.setBlockAndUpdate(new BlockPos(x,63,42),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(new BlockPos(x,64,42),Blocks.WATER.defaultBlockState());}
            check("normal navigation rejects a water crossing",!a.life().reachable(new BlockPos(91,65,42),false),state());
            item=drop(Items.APPLE,1,91.5,65.1,42.5);
        });
        later(180,"water crossing refused",()->check("food does not lure ordinary species across water",item.isAlive()&&a.getX()<87&&!a.isInWaterOrBubble(),state()));
        later(1,"escape pool",()-> {
            fresh();
            for(int x=116;x<=118;x++)for(int z=39;z<=41;z++){level.setBlockAndUpdate(new BlockPos(x,63,z),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(new BlockPos(x,64,z),Blocks.WATER.defaultBlockState());}
            a.moveTo(117.5,64.2,40.5,0,0);life(a,d->d.putFloat("Nutrition",90));
        });
        later(180,"escape reached shore",()->check("fallen individual tries and reaches dry shore",!a.isInWaterOrBubble()&&a.getY()>=65,state()));
        later(1,"prolonged rain",()-> {
            fresh();life(a,d->{d.putFloat("Wetness",58);d.putLong("AgeTicks",80);d.putFloat("Nutrition",90);});
            // Covered facilities are distant; block path access to them for this exposure probe.
            a.moveTo(130.5,65,50.5,0,0);level.setWeatherParameters(0,600,true,false);
        });
        later(45,"rain exposure damages",()->check("continued rain differs from brief washing",a.getHealth()<10&&a.life().wetness()>=60,state()));
        later(1,"fire interruption",()-> {
            level.setWeatherParameters(6000,0,false,false);fresh();a.playAction("taiso");a.setRemainingFireTicks(80);
        });
        later(5,"fire interrupts",()->check("fire cancels leisure and starts escape",a.isPanicking()&&!a.isCommandPlaying()&&List.of("startle","flee").contains(a.getAnimationId()),state()));
    }

    private void transport() {
        later(1,"capture",()-> {
            fresh();life(a,d->{d.putFloat("Nutrition",43);d.putFloat("Energy",62);d.putFloat("Hygiene",71);});
            identity=a.getUUID();carrier=new ItemStack(ModItems.CARRIER.get());
            alice.setItemInHand(InteractionHand.MAIN_HAND,carrier);
            ModItems.CARRIER.get().interactLivingEntity(carrier,alice,a,InteractionHand.MAIN_HAND);token=CarrierItem.token(carrier);
            check("capture gives custody of one UUID",a.isRemoved()&&token!=null&&YukkuriWorldData.get(level).hasToken(token),token);
            before=YukkuriWorldData.get(level).save(new CompoundTag(),level.registryAccess());
            check("custody survives SavedData serialization",YukkuriWorldData.load(before,level.registryAccess()).hasToken(token),before);
            level.setBlockAndUpdate(new BlockPos(96,65,25),Blocks.STONE.defaultBlockState());
            check("blocked release keeps the captive",YukkuriWorldData.get(level).release(token,level,new Vec3(96.5,65,25.5),0)==null&&YukkuriWorldData.get(level).hasToken(token),token);
            level.setBlockAndUpdate(new BlockPos(96,65,25),Blocks.AIR.defaultBlockState());
        });
        later(300,"release without offline metabolism",()-> {
            a=YukkuriWorldData.get(level).release(token,level,new Vec3(96.5,65,24.5),0);
            check("release preserves identity and paused physiology",a!=null&&a.getUUID().equals(identity)&&a.life().nutrition()==43&&a.life().energy()==62&&a.life().hygiene()==71,state());
            check("copied used token cannot duplicate an entity",YukkuriWorldData.get(level).release(token,level,new Vec3(97.5,65,24.5),0)==null&&!YukkuriWorldData.get(level).hasToken(token),token);
        });
    }

    private void social() {
        later(1,"separate player memories",()-> {
            fresh();a.social().talk(alice,true);a.social().talk(bob,false);
            check("players have separate trust and fear",a.life().personality().trust(alice.getUUID())==3&&a.life().personality().fear(alice.getUUID())==0
                    &&a.life().personality().trust(bob.getUUID())==-6&&a.life().personality().fear(bob.getUUID())==8,state());
            a.social().talk(alice,true);
            check("repeated talk respects a per-player cooldown",a.life().personality().trust(alice.getUUID())==3,state());
            before=a.life().personality().save();
            for(int i=0;i<20;i++)NotebookItem.observe(a,alice);
            check("observation does not alter experience or memories",before.equals(a.life().personality().save()),before);
            b=spawn(ModEntities.REIMU.get(),97.5,65,24.5);born(a,90,90,90,90,90,90);born(b,10,10,10,10,10,10);
            check("same species has different approach willingness",a.life().personality().approachScore(alice.getUUID())>b.life().personality().approachScore(alice.getUUID())+50,state());
        });
        later(1,"directed identity",()-> {
            fresh();b=spawn(ModEntities.MARISA.get(),97.5,65,24.5);var relations=YukkuriWorldData.get(level);
            relations.relate(a.getUUID(),b.getUUID(),35);
            check("acquaintances are directed",relations.affinity(a.getUUID(),b.getUUID())==35&&relations.affinity(b.getUUID(),a.getUUID())==0,relations.save(new CompoundTag(),level.registryAccess()));
            accessoryToken=b.life().takeAccessory(alice.getUUID());
            check("missing accessory blocks recognition without erasing relation",!a.life().canRecognize(b)&&relations.affinity(a.getUUID(),b.getUUID())==35,state());
            life(b,d->d.putBoolean("GoldMedal",true));
            check("target medal does not grant observer recognition",!a.life().canRecognize(b),b.life().save());
            life(a,d->d.putBoolean("GoldMedal",true));
            check("observer medal grants recognition",a.life().canRecognize(b),state());
            life(a,d->d.putBoolean("GoldMedal",false));
            check("wrong owner cannot take returned accessory",!a.life().returnAccessory(b.getUUID(),accessoryToken,alice.getUUID()),state());
            check("return restores identity recognition",b.life().returnAccessory(b.getUUID(),accessoryToken,alice.getUUID())&&a.life().canRecognize(b),b.life().save());
            var next=b.life().takeAccessory(alice.getUUID());
            check("old accessory copy is invalid after a later removal",!b.life().returnAccessory(b.getUUID(),accessoryToken,alice.getUUID())&&b.life().returnAccessory(b.getUUID(),next,alice.getUUID()),b.life().save());
            CompoundTag saved=relations.save(new CompoundTag(),level.registryAccess());
            check("relationships survive world data reload",YukkuriWorldData.load(saved,level.registryAccess()).affinity(a.getUUID(),b.getUUID())==35,saved);
        });
        later(1,"peer response",()-> {
            fresh();b=spawn(ModEntities.MARISA.get(),97.1,65,24.5);born(a,0,100,80,20,50,60);born(b,0,100,80,20,50,60);
            life(a,d->d.putInt("SocialWait",0));life(b,d->d.putInt("SocialWait",0));
        });
        later(100,"peer greeted",()->check("nearby peers trigger actual greeting and relationship",YukkuriWorldData.get(level).affinity(a.getUUID(),b.getUUID())>0,a.getDialogueKey()+"; "+state()));
        later(1,"toy response",()-> {
            fresh();born(a,100,0,50,20,50,80);life(a,d->d.putInt("SocialWait",0));item=drop(ModItems.TOY.get(),1,97.2,65.1,24.5);
        });
        later(180,"toy explored",()->check("toy is explored without being consumed",item.isAlive()&&item.getItem().getCount()==1&&a.life().personality().experience(Trait.CURIOSITY)>0,state()));
        later(1,"lesson refused",()-> {
            fresh();check("stranger cannot immediately teach",!a.social().teach(alice)&&a.life().personality().lessons()==0,state());
            trust(a,alice.getUUID(),20);born(a,50,40,60,50,70,80);
            check("trusted player starts a lesson",a.social().teach(alice),state());
        });
        later(200,"first lesson",()->check("completed lesson grants one experience",a.life().personality().lessons()==1,state()));
        later(65,"second lesson starts",()->{alice.moveTo(a.getX(),a.getY(),a.getZ()+1);check("second lesson starts after cooldown",a.social().teach(alice),state());});
        later(200,"second lesson",()->check("second completed lesson is retained",a.life().personality().lessons()==2,state()));
        later(65,"third lesson starts",()->{alice.moveTo(a.getX(),a.getY(),a.getZ()+1);check("third lesson starts",a.social().teach(alice),state());});
        later(200,"learning complete",()->check("three completed lessons teach waiting",a.life().personality().knowsWait(),state()));
        later(65,"learned wait starts",()-> {
            alice.moveTo(a.getX()+10,a.getY(),a.getZ());check("distant command rejects without cooldown",!a.social().teach(alice),state());
            alice.moveTo(a.getX(),a.getY(),a.getZ()+1);position=a.position();check("known instruction starts waiting",a.social().teach(alice),state());});
        later(30,"learned wait holds",()-> {
            check("learned waiting holds position",a.getBehaviorId().equals("wait")&&a.position().distanceTo(position)<.1,state());
            a.hurt(level.damageSources().generic(),1);
            check("danger interrupts learned waiting",!a.social().active()&&a.isPanicking(),state());
            before=new CompoundTag();a.save(before);identity=a.getUUID();a.discard();a=(YukkuriEntity)EntityType.loadEntityRecursive(before,level,e->e);level.addFreshEntity(a);
            check("personality memories and learning survive entity reload",a.getUUID().equals(identity)&&a.life().personality().knowsWait()&&a.life().personality().trust(alice.getUUID())>=20,state());
        });
    }
}
