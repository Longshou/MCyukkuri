package io.github.longshou.mcyukkuri.p0;

import io.github.longshou.mcyukkuri.entity.*;
import io.github.longshou.mcyukkuri.human.*;
import io.github.longshou.mcyukkuri.life.*;
import io.github.longshou.mcyukkuri.predator.PredatorLife.Order;
import io.github.longshou.mcyukkuri.registry.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.*;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class PredatorGameTests {
    private record Check(String name,boolean pass) {}
    private final List<Check> checks=new ArrayList<>();
    private static final int X=3008;
    private static final AABB AREA=new AABB(X,63,0,X+64,80,30);
    private FakePlayer player;
    private YukkuriEntity hunter,prey,flier,scavenger;
    private net.minecraft.world.entity.item.ItemEntity portion;
    private double startX,maxY,startDistance,maxHuntX,minHuntDistance;
    private int approachTick=-1;
    private boolean flew,frightened,bit,flandreFlew,crossedObstacle,approached,flandreReleased,flandreSpentFlightEnergy;
    private float flandreFlightEnergy=Float.NaN;
    private final List<Map<String,Object>> trace=new ArrayList<>();
    private final List<Map<String,Object>> flandreTrace=new ArrayList<>();
    private void check(String name,boolean pass){checks.add(new Check(name,pass));}
    @GameTest(templateNamespace=P0Mod.ID,template="empty",timeoutTicks=1000,batch="predators")
    public static void predatorAcceptance(GameTestHelper h){
        var t=new PredatorGameTests();var l=h.getLevel();
        for(int x=X>>4;x<=(X+64)>>4;x++)for(int z=0;z<=1;z++)GameTestChunks.setForced(l,x,z,true);
        h.startSequence().thenWaitUntil(()->GameTestChunks.assertTicking(h,X>>4,(X+64)>>4,0,1))
            .thenExecute(()->{try{t.setup(l);t.rules(l);t.live(l);}catch(Throwable e){t.check("Setup: "+e,false);}})
            .thenExecuteFor(400,()->{if(t.hunter!=null){
                if(!t.flandreReleased&&t.flier.tickCount>=160){
                    t.check("Flandre patrol starts from the prepared clear launch area",
                        t.flier.position().distanceToSqr(new Vec3(X+36,65,20))<.01
                        &&t.flier.family().adult()&&!t.flier.life().hasUrgentNeed()
                        &&t.flier.life().energy()>45&&!l.isNight()&&!l.isRaining());
                    t.flier.setNoAi(false);t.flandreReleased=true;
                }
                if(t.flier.predatorFlying()){
                    if(Float.isNaN(t.flandreFlightEnergy))t.flandreFlightEnergy=t.flier.life().energy();
                    else t.flandreSpentFlightEnergy|=t.flier.life().energy()<t.flandreFlightEnergy;
                }
                t.flew|=t.hunter.predatorFlying();t.maxY=Math.max(t.maxY,t.hunter.getY());
                t.crossedObstacle|=t.hunter.predatorFlying()&&t.hunter.getX()>X+9&&t.hunter.getY()>65.5;
                // A successful bite ends the pursuit and may send the hunter back past its
                // starting point. Require actual forward progress AND reduced separation
                // during chase/dive, not its unrelated position at the end of the window.
                String task=t.hunter.predator().activity();
                if(task.equals("predator_chase")||task.equals("predator_dive")){
                    double distance=t.hunter.distanceTo(t.prey);
                    t.maxHuntX=Math.max(t.maxHuntX,t.hunter.getX());t.minHuntDistance=Math.min(t.minHuntDistance,distance);
                    if(t.hunter.getX()>t.startX+1&&distance<t.startDistance-1){
                        if(!t.approached)t.approachTick=t.hunter.tickCount;
                        t.approached=true;
                    }
                }
                t.frightened|=t.prey.isPanicking();t.bit|=t.prey.getHealth()<t.prey.getMaxHealth();t.flandreFlew|=t.flier.predatorFlying();
                if(t.flier.tickCount%20==0)t.flandreTrace.add(t.flandreState(l));
                if(t.hunter.tickCount%20==0)t.trace.add(Map.of("tick",t.hunter.tickCount,"hunter",t.hunter.position().toString(),"prey",t.prey.position().toString(),"task",task,"distance",t.hunter.distanceTo(t.prey),"health",t.prey.getHealth(),"energy",t.hunter.life().energy(),"lineOfSight",t.hunter.hasLineOfSight(t.prey),"crossedObstacle",t.crossedObstacle,"approached",t.approached));}})
            .thenExecute(()->{
                try{
                    t.check("Hungry adult uses real flying navigation",t.flew&&t.maxY>65.5);
                    t.check("Hunt moves toward prey",t.approached);
                    t.check("Real flight crosses the low physical obstacle",t.crossedObstacle);
                    t.check("Nearby wild prey receives an escape response",t.frightened);
                    t.check("Actual pursuit reaches a bite",t.bit);
                    t.check("Flight consumes finite energy",t.hunter.life().energy()<90);
                    t.check("Flandre also uses finite flight (observed="+t.flandreFlew+", state="+t.flandreState(l)+")",t.flandreFlew&&t.flier.getY()<75);
                    t.check("Flandre real flight consumes finite energy",t.flandreSpentFlightEnergy);
                    t.check("Scavenging consumes one actual portion and grants its nutrition (removed="+t.portion.isRemoved()+", food="+t.scavenger.life().nutrition()+", meal="+t.scavenger.life().mealTicks()+")",t.portion.isRemoved()&&t.scavenger.life().nutrition()>25);
                    t.check("Prey physically escapes away from its start",t.prey.position().distanceToSqr(new Vec3(X+12,65,20))>1);
                    t.hunter.predator().stop();t.check("Interrupt restores gravity and ground navigation",!t.hunter.predatorFlying()&&!t.hunter.isNoGravity()&&t.hunter.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation);
                }catch(Throwable e){t.check("Live: "+e,false);}
                P0Control.write("predator-checks",t.checks);P0Control.write("predator-trace",t.trace);
                P0Control.write("predator-flandre-trace",t.flandreTrace);
                P0Control.write("predator-live",Map.of("flew",t.flew,"maxY",t.maxY,"bit",t.bit,"frightened",t.frightened,
                    "hunter",t.hunter==null?"missing":t.hunter.position().toString(),"prey",t.prey==null?"missing":t.prey.position().toString(),
                    "approached",t.approached,"approachTick",t.approachTick,"maxHuntX",t.maxHuntX,"minHuntDistance",t.minHuntDistance));
                l.getEntitiesOfClass(Entity.class,AREA,e->e instanceof YukkuriEntity||e instanceof HumanNpc||e instanceof net.minecraft.world.entity.item.ItemEntity).forEach(Entity::discard);
                if(t.player!=null)t.player.discard();
                for(int x=X>>4;x<=(X+64)>>4;x++)for(int z=0;z<=1;z++)GameTestChunks.setForced(l,x,z,false);
                var failures=t.checks.stream().filter(c->!c.pass).toList();if(failures.isEmpty())h.succeed();else h.fail(failures.toString());
            });
    }
    private Map<String,Object> flandreState(ServerLevel l){
        var state=new LinkedHashMap<String,Object>();
        state.put("tick",flier.tickCount);state.put("position",flier.position().toString());
        state.put("flying",flier.predatorFlying());state.put("task",flier.predator().activity());
        state.put("lifeTask",flier.life().activity());state.put("familyTask",flier.family().activity());
        state.put("brood",flier.family().brood()!=null);state.put("adult",flier.family().adult());
        state.put("energy",flier.life().energy());state.put("nutrition",flier.life().nutrition());
        state.put("wetness",flier.life().wetness());state.put("urgentNeed",flier.life().hasUrgentNeed());
        state.put("panicking",flier.isPanicking());state.put("inWater",flier.isInWaterOrBubble());
        state.put("sleeping",flier.life().isSleeping());state.put("mealTicks",flier.life().mealTicks());
        state.put("night",l.isNight());state.put("raining",l.isRaining());
        state.put("entityTicking",l.isPositionEntityTicking(flier.blockPosition()));
        state.put("noAi",flier.isNoAi());state.put("alive",flier.isAlive());
        state.put("onGround",flier.onGround());state.put("noGravity",flier.isNoGravity());
        return state;
    }
    private void setup(ServerLevel l){
        l.setDayTime(6000);l.updateSkyBrightness();l.setWeatherParameters(60000,0,false,false);
        l.getEntitiesOfClass(Entity.class,AREA,e->!(e instanceof net.minecraft.world.entity.player.Player)).forEach(Entity::discard);
        for(int x=X;x<X+64;x++)for(int z=0;z<30;z++){
            l.setBlock(new BlockPos(x,64,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=65;y<78;y++)l.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
        }
        player=FakePlayerFactory.get(l,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"PredatorTest"));l.addNewPlayer(player);
        player.moveTo(X+5,65,5);player.getInventory().clearContent();player.getInventory().add(new ItemStack(Items.EMERALD,64));player.getInventory().add(new ItemStack(Items.EMERALD,64));
    }
    private YukkuriEntity create(ServerLevel l,YukkuriVariant variant,double x,double z,int nutrition){
        var y=ModEntities.forVariant(variant).create(l);y.moveTo(X+x,65,z,0,0);y.setOnGround(true);y.setPersistenceRequired();
        var life=y.life().save();life.putFloat("Nutrition",nutrition);life.putFloat("Energy",90);y.life().load(life);l.addFreshEntity(y);return y;
    }
    private void rules(ServerLevel l){
        var r=create(l,YukkuriVariant.REMILIA,5,5,25);var ordinary=create(l,YukkuriVariant.REIMU,7,5,80);
        HumanCommerce.assign(r,player.getUUID(),"companion");HumanCommerce.assign(ordinary,player.getUUID(),"companion");
        check("Purchased unbadged predator retains wild hunting",r.predator().wantsHunt()&&r.predator().mayHunt(ordinary));
        check("Unbadged predator rejects commands",!r.predator().command(player,Order.PEACEFUL));
        r.pet().issue(io.github.longshou.mcyukkuri.pet.BadgeGrade.SILVER,1,null);check("Badge without education is insufficient",!r.predator().obedient());
        for(int i=0;i<3;i++)r.life().personality().lessonCompleted(player.getUUID());
        r.pet().setAbility(io.github.longshou.mcyukkuri.pet.Ability.WITS,70);
        check("Educated silver predator respects protected companions",r.predator().obedient()&&!r.predator().mayHunt(ordinary));
        check("Owner can stop hunting",r.predator().command(player,Order.PEACEFUL)&&!r.predator().wantsHunt());
        var stored=r.predator().save();r.predator().load(stored);check("Owner order survives reload",r.predator().order()==Order.PEACEFUL);
        HumanCommerce.assign(r,UUID.randomUUID(),"companion");check("Owner transfer invalidates old command",r.predator().order()==Order.NATURAL&&!r.predator().command(player,Order.HUNT));
        HumanCommerce.assign(r,player.getUUID(),"companion");
        check("Return command requires an assigned nest",!r.predator().command(player,Order.RETURN));
        var nest=new BlockPos(X+9,65,5);l.setBlock(nest,ModBlocks.PREDATOR_NEST.get().defaultBlockState(),3);
        for(int x=X+8;x<=X+11;x++)for(int z=3;z<=7;z++)l.setBlock(new BlockPos(x,68,z),Blocks.OAK_PLANKS.defaultBlockState(),2);
        check("Predator claims a real covered twig nest",r.life().assignHome(GlobalPos.of(l.dimension(),nest)));
        check("Ordinary species cannot claim predator nest",!ordinary.life().assignHome(GlobalPos.of(l.dimension(),nest)));
        check("Educated predator accepts return command",r.predator().command(player,Order.RETURN));
        r.setPredatorFlying(true);var airborne=new CompoundTag();r.saveWithoutId(airborne);
        var reloaded=ModEntities.REMILIA.get().create(l);reloaded.load(airborne);
        check("Loading an airborne save restores safe gravity",!reloaded.isNoGravity()&&!reloaded.predatorFlying());
        var wild=create(l,YukkuriVariant.FLANDRE,22,6,80);
        for(int x=X+21;x<=X+23;x++)for(int z=5;z<=7;z++)l.setBlock(new BlockPos(x,66,z),Blocks.STONE.defaultBlockState(),2);
        for(int x=X+20;x<X+25;x++)for(int z=4;z<9;z++)l.setBlock(new BlockPos(x,70,z),Blocks.OAK_PLANKS.defaultBlockState(),2);
        check("Wild predator weaves and claims a covered elevated nest",wild.predator().findNest(true)&&wild.life().homeStand()!=null);
        wild.discard();
        r.predator().stop();r.discard();ordinary.discard();
        var npc=HumanWorld.BROTHER.get().create(l);npc.moveTo(X+5,65,5);npc.setJob(HumanNpc.Job.SHOPKEEPER);npc.setNoAi(true);l.addFreshEntity(npc);
        var stock=new ArrayList<YukkuriEntity>();
        for(var variant:List.of(YukkuriVariant.REIMU,YukkuriVariant.MARISA,YukkuriVariant.CHEN,YukkuriVariant.ALICE,YukkuriVariant.REMILIA,YukkuriVariant.FLANDRE)){
            var y=create(l,variant,6+stock.size()*.7,7,80);y.setNoAi(true);HumanCommerce.assign(y,npc.getUUID(),"stock");stock.add(y);
        }
        var menu=new HumanMenu(1,player.getInventory(),npc.getId());player.containerMenu=menu;
        check("Six actual animals produce two sale pages",menu.value(53)==2);
        check("Second page lists both predators",menu.clickMenuButton(player,21)&&menu.value(6)==YukkuriVariant.REMILIA.ordinal()+1&&menu.value(12)==YukkuriVariant.FLANDRE.ordinal()+1);
        var sold=stock.get(4);var uuid=sold.getUUID();int price=HumanMenu.price(sold);int before=money();
        check("Predator purchase transfers the real individual at quoted price",menu.clickMenuButton(player,4)&&sold.isRemoved()&&money()==before-price);
        var carrier=player.getInventory().items.stream().filter(s->s.is(ModItems.CARRIER.get())).findFirst().orElse(ItemStack.EMPTY);
        check("Purchased predator keeps species and UUID in authoritative custody",HumanCommerce.heldData(l,carrier).getUUID("UUID").equals(uuid)&&HumanCommerce.heldData(l,carrier).getString("id").equals("mcyukkuri:remilia"));
        before=money();check("Repeated stale purchase cannot debit twice",!menu.clickMenuButton(player,4)&&money()==before);
        stock.forEach(Entity::discard);npc.discard();
    }
    private int money(){return player.getInventory().items.stream().filter(s->s.is(Items.EMERALD)).mapToInt(ItemStack::getCount).sum();}
    private void live(ServerLevel l){
        hunter=create(l,YukkuriVariant.REMILIA,5,20,25);prey=create(l,YukkuriVariant.REIMU,12,20,80);startX=maxHuntX=hunter.getX();maxY=65;
        startDistance=minHuntDistance=hunter.distanceTo(prey);
        // First verify that the full wall blocks detection. Do not depend on random strolling
        // to climb that wall before the hunt can even start.
        for(int z=15;z<27;z++)l.setBlock(new BlockPos(X+8,65,z),Blocks.STONE.defaultBlockState(),2);
        for(int x=X;x<X+28;x++)for(int y=65;y<68;y++)for(int z:new int[]{14,28})l.setBlock(new BlockPos(x,y,z),Blocks.GLASS.defaultBlockState(),2);
        for(int y=65;y<68;y++)for(int z=14;z<=28;z++)l.setBlock(new BlockPos(X+27,y,z),Blocks.GLASS.defaultBlockState(),2);
        check("Opaque wall prevents hunting a hidden prey",!hunter.hasLineOfSight(prey)&&!hunter.predator().mayHunt(prey));
        // A closed bottom trapdoor is a real collision obstacle below both eye heights.
        // The hungry predator now sees the prey and must start its own takeoff/chase goal.
        for(int z=15;z<27;z++)l.setBlock(new BlockPos(X+8,65,z),Blocks.OAK_TRAPDOOR.defaultBlockState(),2);
        check("Low obstacle keeps a real collision surface",!l.getBlockState(new BlockPos(X+8,65,20)).getCollisionShape(l,new BlockPos(X+8,65,20)).isEmpty());
        check("Live pursuit starts with visible eligible prey",hunter.hasLineOfSight(prey)&&hunter.predator().wantsHunt()&&hunter.predator().mayHunt(prey));
        flier=create(l,YukkuriVariant.FLANDRE,36,20,80);
        // Patrol has an initial 160-tick wait. Keep its starting prerequisites fixed
        // during that wait, rather than letting unrelated ground AI wander before
        // the first random flight destination is selected. PredatorLife's clock
        // still advances with no AI; after release the real goal owns all movement.
        flier.setNoAi(true);
        scavenger=create(l,YukkuriVariant.FLANDRE,55,20,25);
        portion=new net.minecraft.world.entity.item.ItemEntity(l,X+55.6,65,20,io.github.longshou.mcyukkuri.body.FillingResource.portion(prey,"predation"));
        portion.setNoPickUpDelay();l.addFreshEntity(portion);
        // Full predator patrol uses the same real goal clock after its initial wait.
    }
}
