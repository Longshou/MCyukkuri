package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.season.AutumnStock;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A real resident, never a hidden duplicate: carries one resource, maintains stores and clears waste. */
public final class HabitatLife {
    private enum Task { NONE, FOOD, PICKUP, HARVEST, DEPOSIT, CLEAN, SHARE, STAY, SIESTA, NEST_PICKUP, NEST_HARVEST, NEST_BUILD }
    private final YukkuriEntity entity;
    private GlobalPos home;
    private ItemStack carrying=ItemStack.EMPTY;
    /** The second real portion for a personal nest. Unlike household work, its item identity need not match the first. */
    private ItemStack nestMaterial=ItemStack.EMPTY;
    /** The home that supplied reserved nest material. A move or lost bed cancels this small project in the current world. */
    private GlobalPos nestHome;
    private ItemEntity targetItem;
    private BlockPos targetBlock;
    private Task task=Task.NONE;
    private int wait,ticks,buildTicks;
    // Seasonal state is transient: it restarts with the entity and never changes the save format.
    private BlockPos shareTo;
    private int shareCooldown,quarrelCooldown;
    private long siestaDay=-1;
    private boolean arrived;
    public HabitatLife(YukkuriEntity entity) { this.entity=entity; }
    private ServerLevel level() { return (ServerLevel)entity.level(); }
    public boolean hasMemory() { return home!=null; }
    public boolean unavailable() { return home!=null&&(!home.dimension().equals(entity.level().dimension())||!entity.level().hasChunkAt(home.pos())); }
    public ShelterBlockEntity shelter() { return home==null||unavailable()?null:ShelterBlock.shelter(entity.level(),home.pos()); }
    public ItemStack carrying() { return carrying; }
    public boolean active() { return task!=Task.NONE; }
    public String activity() { return task==Task.CLEAN?"shelter_clean":task==Task.NEST_BUILD?"nest_build":task==Task.NEST_PICKUP||task==Task.NEST_HARVEST?"nest_gather":task==Task.DEPOSIT||task==Task.SHARE?"shelter_carry":task==Task.STAY||task==Task.SIESTA?"idle":task==Task.HARVEST||task==Task.PICKUP?"shelter_gather":"shelter_stock"; }
    public BlockPos homeStand() { var value=shelter();return value!=null&&value.usable(entity)?value.bed(entity.getUUID()):null; }
    public boolean leaking() {
        var value=shelter();
        return value!=null&&value.moisture()>=60&&value.contains(entity.blockPosition());
    }
    public boolean choose(ShelterBlockEntity value) {
        if(roaming()||entity.getVariant().isHouse()||entity.getVariant().isPredator()||!value.sound()||value.moisture()>=85||value.dirt()>=90||entity.distanceToSqr(Vec3.atBottomCenterOf(value.getBlockPos()))>32*32)return false;
        if(hasNestProject()&&!sameNestHome(value)) { cancelNestProject();return false; }
        boolean owned=value.owns(entity.getUUID());
        if(!value.claim(entity))return false;
        BlockPos bed=value.bed(entity.getUUID());
        if(!entity.life().dryStand(bed)||!entity.life().reachable(bed,false)){if(!owned)value.release(entity.getUUID());return false;}
        var old=shelter();
        if(!entity.life().assignNaturalHome(bed)){if(!owned)value.release(entity.getUUID());return false;}
        if(old!=null&&old!=value)old.release(entity.getUUID());
        home=GlobalPos.of(entity.level().dimension(),value.getBlockPos());entity.setPersistenceRequired();return true;
    }
    public boolean inherit(YukkuriEntity parent) { var value=parent.habitat().shelter();return value!=null&&choose(value); }
    public void release() { var value=shelter();if(value!=null)value.release(entity.getUUID());home=null;cancelNestProject();stop(); }
    /** Wandering bands never settle: no burrow is claimed or dug for them. */
    private boolean roaming() { var group=entity.settlement().group();return group!=null&&group.kind==SettlementData.Kind.BANDITS; }
    public boolean findHome() {
        if(roaming()||entity.getVariant().isHouse()||entity.getVariant().isPredator()||unavailable())return false;
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-12,-2,-12),entity.blockPosition().offset(12,2,12)))
            if(level().hasChunkAt(pos)&&level().getBlockEntity(pos) instanceof ShelterBlockEntity value&&choose(value))return true;
        if(!EventHooks.canEntityGrief(level(),entity))return false;
        for(BlockPos pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8))) {
            if(!NaturalShelter.enclosed(level(),pos))continue;
            for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var plan=new ShelterPlacement.Plan(pos.immutable(),ShelterBlock.Kind.BURROW,direction);
                if(!ShelterPlacement.canPlace(level(),plan)||!entity.life().reachable(pos,false))continue;
                var placed=ShelterPlacement.place(level(),plan);
                if(placed!=null){if(choose(placed.shelter()))return true;placed.rollback(level());}
            }
        }
        return false;
    }
    public void tick() {
        if(entity.level().isClientSide||entity.isNoAi()||entity.getVariant().isHouse())return;
        if(hasNestProject()&&!nestProjectValid())cancelNestProject();
        if(wait>0)wait--;
        if(shareCooldown>0)shareCooldown--;
        if(quarrelCooldown>0)quarrelCooldown--;
        if(entity.tickCount%100!=0)return;
        var value=shelter();boolean inside=value!=null&&value.contains(entity.blockPosition());
        int wet=inside&&value.moisture()>=60?3:0;
        int hygiene=inside&&value.dirt()>=50?2:0;
        entity.life().applyEnvironment(wet,hygiene,0);
        if(value!=null&&quarrelCooldown==0&&Seasons.is(entity,Season.WINTER)&&value.food()==0&&entity.life().nutrition()<30)quarrel(value);
    }
    /** Winter famine: two starving housemates over empty stores snap at each other. Sacrifice for the family is not modelled yet (plan 2.4). */
    private void quarrel(ShelterBlockEntity value) {
        for(var id:value.residentIds()) {
            if(id.equals(entity.getUUID())||entity.getUUID().compareTo(id)>0)continue;
            if(!(level().getEntity(id) instanceof YukkuriEntity other)||!other.isAlive()||other.life().nutrition()>=30
                    ||other.habitat().quarrelCooldown>0||entity.distanceToSqr(other)>256)continue;
            var relations=YukkuriWorldData.get(level());
            int loss=-YukkuriConfig.WINTER_QUARREL_PENALTY.get();
            relations.relate(entity.getUUID(),other.getUUID(),loss);relations.relate(other.getUUID(),entity.getUUID(),loss);
            boolean weaker=entity.life().nutrition()<=other.life().nutrition();
            entity.showReaction(weaker?"teary":"angry","mine",80);other.showReaction(weaker?"angry":"teary","mine",80);
            quarrelCooldown=2400;other.habitat().quarrelCooldown=2400;return;
        }
    }
    private boolean autumn() { return Seasons.is(entity,Season.AUTUMN); }
    private boolean ready() {
        return entity.isAlive()&&!entity.isNoAi()&&!entity.isPassenger()&&!entity.getVariant().isHouse()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.family().active()&&!entity.family().awaitingLanding()
                &&!entity.life().isSleeping()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&entity.life().mealTicks()==0
                &&!Migration.underway(entity)&&!entity.wintering().active();
    }
    private boolean wantsResource(ShelterBlockEntity value,ItemStack stack) {
        if(!value.accepts(stack))return false;
        if(value.needsMaterial(stack))return true;
        return stack.is(YukkuriEntity.FOOD)&&entity.life().acceptsFood(stack)&&value.food()<AutumnStock.target(level(),value)
                &&(!AutumnStock.autumn(level(),value.getBlockPos())||AutumnStock.fitCollector(entity));
    }
    private boolean outingMealAvailable() {
        var value=shelter();
        return entity.life().canEat()&&value!=null&&value.usable(entity)&&value.hasFoodFor(entity)
                &&!entity.wintering().usesWinterStores();
    }
    public boolean canUse() {
        if(!ready())return false;
        // A higher-priority goal must reject maintenance before the selector interrupts construction.
        if(entity.independence().householdOuting()&&carrying.isEmpty()
                &&(!outingMealAvailable()||active()&&task!=Task.FOOD))return false;
        if(active())return true;
        if(wait>0)return false;wait=autumn()?30:60;
        var value=shelter();boolean housed=value!=null&&value.usable(entity);
        if(!housed) {
            if(hasNestProject())cancelNestProject();
            return seasonal(null);
        }
        if(entity.life().canEat()&&value.food()>0&&!entity.wintering().usesWinterStores()){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;return true;}
        if(carrying.isEmpty()&&!entity.independence().householdOuting()&&seasonal(value))return true;
        if(nestEligible(value)&&startNest(value))return true;
        if(hasNestProject()) {
            if(!nestProjectValid())cancelNestProject();
            return false;
        }
        // Young residents eat and observe the season, but do not maintain the household before their nest gate.
        if(!entity.family().adult()&&!entity.childhood().canLiveIndependently()) {
            if(!carrying.isEmpty()&&entity.life().dryStand(entity.blockPosition()))dropCarrying();
            return false;
        }
        if(shareStart(value))return true;
        if(carrying.isEmpty()&&(entity.settlement().society().dejected()||!entity.settlement().duties().carrying().isEmpty()))return false;
        boolean mayWork=EventHooks.canEntityGrief(level(),entity)&&entity.life().nutrition()>=40&&entity.life().energy()>=35;
        if(!carrying.isEmpty()) {
            if(!mayWork){if(entity.life().dryStand(entity.blockPosition()))dropCarrying();return false;}
            task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;return true;
        }
        if(!mayWork)return false;
        targetItem=value.wasteInside().stream().filter(item->!item.hasPickUpDelay()).findFirst().orElse(null);
        if(targetItem==null)targetItem=level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(10),item->item.isAlive()&&!item.hasPickUpDelay()
                &&wantsResource(value,item.getItem()))
                .stream().filter(item->entity.life().reachable(item.blockPosition(),false))
                .min(Comparator.<ItemEntity>comparingInt(item->value.needsMaterial(item.getItem())?0:1).thenComparingDouble(entity::distanceToSqr)).orElse(null);
        if(targetItem!=null&&!targetItem.getItem().is(ModItems.WASTE.get())&&!value.needsMaterial(targetItem.getItem())
                &&value.needsMaterial(new ItemStack(Items.SHORT_GRASS))&&planHarvest(value))return true;
        if(targetItem!=null){task=Task.PICKUP;ticks=0;return true;}
        return planHarvest(value);
    }
    private static boolean nestBedding(ItemStack stack) {
        return stack.is(ItemTags.LEAVES)||stack.is(Items.SHORT_GRASS)||stack.is(Items.WHEAT);
    }
    /** A late child builds only at its actual inherited burrow bed, never by creating or destroying blocks. */
    private boolean nestEligible(ShelterBlockEntity value) {
        return value!=null&&value.kind()==ShelterBlock.Kind.BURROW&&value.owns(entity.getUUID())&&value.usable(entity)
                &&entity.family().readyForOwnNest()&&!value.privateNest(entity.getUUID())
                &&!entity.life().needsFood()&&!entity.life().hasUrgentNeed()&&!entity.independence().householdOuting()
                &&(!hasNestProject()||sameNestHome(value));
    }
    private boolean startNest(ShelterBlockEntity value) {
        if(!nestEligible(value))return false;
        if(!EventHooks.canEntityGrief(level(),entity))return false;
        normalizeNestMaterials();
        if(!carrying.isEmpty()&&!nestBedding(carrying))return false;
        if(!nestMaterial.isEmpty()&&(!nestBedding(nestMaterial)||carrying.isEmpty()))return false;
        if(!carrying.isEmpty()&&nestHome==null)nestHome=GlobalPos.of(level().dimension(),value.getBlockPos());
        if(hasNestMaterials()) {
            task=Task.NEST_BUILD;targetBlock=value.bed(entity.getUUID());targetItem=null;ticks=buildTicks=0;return true;
        }
        return findNestBedding(value);
    }
    private void normalizeNestMaterials() {
        if(nestMaterial.isEmpty()&&carrying.getCount()==2)nestMaterial=carrying.split(1);
    }
    private boolean hasNestMaterials() { return !carrying.isEmpty()&&!nestMaterial.isEmpty(); }
    private boolean hasNestProject() { return nestHome!=null; }
    private boolean sameNestHome(ShelterBlockEntity value) {
        return nestHome!=null&&nestHome.dimension().equals(entity.level().dimension())&&value!=null&&nestHome.pos().equals(value.getBlockPos());
    }
    private boolean nestProjectValid() {
        var value=shelter();
        return !entity.family().adult()&&sameNestHome(value)&&value.owns(entity.getUUID())&&!value.privateNest(entity.getUUID());
    }
    /** Invalid projects release every real reserved portion at the current location, retrying after a failed spawn. */
    private void cancelNestProject() {
        if(!hasNestProject())return;
        dropCarrying();
        if(carrying.isEmpty()&&nestMaterial.isEmpty())nestHome=null;
    }
    private boolean nearNest(ShelterBlockEntity value,BlockPos pos) {
        return value.beds().stream().anyMatch(bed->bed.distSqr(pos)<=9);
    }
    /** Select one physical bedding portion close to this home; blocks and paper never count as nest material. */
    private boolean findNestBedding(ShelterBlockEntity value) {
        targetItem=level().getEntitiesOfClass(ItemEntity.class,value.interior().inflate(3),item->item.isAlive()&&!item.hasPickUpDelay()
                &&nearNest(value,item.blockPosition())&&nestBedding(item.getItem())
                &&entity.life().reachable(item.blockPosition(),false)).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(targetItem!=null){task=Task.NEST_PICKUP;targetBlock=null;ticks=0;return true;}
        for(var pos:BlockPos.betweenClosed(BlockPos.containing(value.interior().minX-3,value.interior().minY-1,value.interior().minZ-3),
                BlockPos.containing(value.interior().maxX+3,value.interior().maxY+1,value.interior().maxZ+3)))
            if(nearNest(value,pos)&&level().hasChunkAt(pos)&&level().getBlockState(pos).is(Blocks.SHORT_GRASS)
                    &&entity.life().dryStand(pos)&&entity.life().reachable(pos,false)) {
                task=Task.NEST_HARVEST;targetBlock=pos.immutable();ticks=0;return true;
            }
        return false;
    }
    private void takeNestMaterial(ShelterBlockEntity value,ItemStack material) {
        if(nestHome==null)nestHome=GlobalPos.of(level().dimension(),value.getBlockPos());
        if(carrying.isEmpty())carrying=material;
        else if(nestMaterial.isEmpty())nestMaterial=material;
    }
    private boolean planHarvest(ShelterBlockEntity value) {
        if(wantsResource(value,new ItemStack(Items.SHORT_GRASS))) {
            for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8)))
                if(level().hasChunkAt(pos)&&level().getBlockState(pos).is(Blocks.SHORT_GRASS)&&entity.life().dryStand(pos)&&entity.life().reachable(pos,false)){
                    targetBlock=pos.immutable();targetItem=null;task=Task.HARVEST;ticks=0;return true;
                }
        }
        return false;
    }
    /** Winter closed nest and summer noon rest. Both only hold the animal in place; eating still comes first. */
    private boolean seasonal(ShelterBlockEntity value) {
        if(!Seasons.enabled()||entity.getVariant().isPredator()||entity.family().active()
                ||entity.independence().householdOuting()||entity.life().hasUrgentNeed())return false;
        if(value!=null&&!entity.wintering().usesWinterStores()&&Seasons.confined(entity)){task=Task.STAY;targetBlock=value.bed(entity.getUUID());ticks=0;arrived=false;return true;}
        long day=level().getDayTime()/24000;
        if(siestaDay==day||!Seasons.is(entity,Season.SUMMER)||!Seasons.noon(level())||level().isRaining()
                ||entity.life().nutrition()<30||entity.life().covered(entity.blockPosition()))return false;
        siestaDay=day;
        BlockPos shade=null;double best=Double.MAX_VALUE;
        for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-6,-2,-6),entity.blockPosition().offset(6,2,6))) {
            double distance=pos.distSqr(entity.blockPosition());
            if(distance<best&&level().hasChunkAt(pos)&&entity.life().covered(pos)&&ShelterBlock.shelter(level(),pos)==null
                    &&entity.life().dryStand(pos)&&entity.life().reachable(pos,false)){shade=pos.immutable();best=distance;}
        }
        if(shade==null&&value!=null){BlockPos bed=value.bed(entity.getUUID());if(bed!=null&&entity.distanceToSqr(Vec3.atBottomCenterOf(bed))<=24*24)shade=bed;}
        if(shade==null)return false;
        task=Task.SIESTA;targetBlock=shade;ticks=0;arrived=false;return true;
    }
    /** At a gathering, a household with plenty hands one real portion to the poorest other household of the group. */
    private boolean shareStart(ShelterBlockEntity value) {
        var group=entity.settlement().group();
        if(group==null||shareCooldown>0||!carrying.isEmpty()||entity.independence().householdOuting()
                ||entity.settlement().society().dejected()||!entity.settlement().duties().carrying().isEmpty()
                ||!EventHooks.canEntityGrief(level(),entity)||entity.life().energy()<35||entity.life().nutrition()<40||Migration.moving(group)||!Seasons.assembling(level(),group)
                ||value.food()<=value.occupants()*YukkuriConfig.SHARE_SURPLUS_PER_RESIDENT.get())return false;
        ShelterBlockEntity best=null;
        var members=level().getEntitiesOfClass(YukkuriEntity.class,new AABB(group.center).inflate(40),
                other->other!=entity&&other.isAlive()&&group.id.equals(other.settlement().groupId()));
        for(var other:members) {
            var shelter=other.habitat().shelter();
            if(shelter==null||shelter.getBlockPos().equals(value.getBlockPos())||shelter.food()>=shelter.occupants()*YukkuriConfig.SHARE_NEED_PER_RESIDENT.get()
                    ||shelter.food()>=shelter.foodCapacity())continue;
            if(best==null||shelter.food()<best.food())best=shelter;
        }
        if(best==null||!entity.life().reachable(best.entrance(),false))return false;
        var food=value.takeFood(entity);
        if(food.isEmpty())return false;
        carrying=food;shareTo=best.getBlockPos();task=Task.SHARE;ticks=0;shareCooldown=YukkuriConfig.SHARE_COOLDOWN.get();return true;
    }
    public boolean canContinue() {
        if(!active()||!ready())return false;
        if(task==Task.SIESTA)return ticks<1200&&targetBlock!=null&&Seasons.is(entity,Season.SUMMER)&&Seasons.noon(level())&&!entity.life().hasUrgentNeed();
        if(task==Task.STAY)return homeStand()!=null&&Seasons.confined(entity)&&!entity.wintering().usesWinterStores()&&!entity.life().hasUrgentNeed();
        if(task==Task.NEST_PICKUP||task==Task.NEST_HARVEST||task==Task.NEST_BUILD)return ticks<400&&nestEligible(shelter())&&EventHooks.canEntityGrief(level(),entity);
        return active()&&ready()&&ticks<400&&homeStand()!=null&&(task==Task.FOOD||EventHooks.canEntityGrief(level(),entity))
                &&(!entity.independence().householdOuting()||!carrying.isEmpty()||task==Task.FOOD&&outingMealAvailable())
                &&(task==Task.FOOD||!carrying.isEmpty()||!entity.settlement().society().dejected())
                &&(task==Task.FOOD||entity.life().nutrition()>=40&&entity.life().energy()>=35);
    }
    public void tickGoal() {
        if(!canContinue()){stop();return;}ticks++;
        var value=shelter();
        if(task==Task.STAY||task==Task.SIESTA) {
            if(task==Task.STAY&&value!=null&&entity.life().canEat()&&value.food()>0){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;return;}
            if(approach(targetBlock)) {
                entity.getNavigation().stop();
                if(!arrived){arrived=true;if(task==Task.SIESTA)entity.showReaction("tired","puff",80);}
            }
            return;
        }
        if(task==Task.SHARE) {
            var other=shareTo==null?null:ShelterBlock.shelter(level(),shareTo);
            if(other==null){task=Task.DEPOSIT;ticks=0;return;}
            if(approach(other.entrance())) {
                if(other.deposit(carrying)){carrying=ItemStack.EMPTY;entity.showReaction("happy","share",80);stop();}
                else{task=Task.DEPOSIT;ticks=0;}
            }
            return;
        }
        if(task==Task.FOOD) {
            if(approach(value.bed(entity.getUUID()))) {
                // Put down the existing burden before taking a meal, so a rejected meal can
                // return its remainder without overwriting an unrelated carried resource.
                if(!carrying.isEmpty()) {
                    if(!nestMaterial.isEmpty())dropCarrying();
                    else if(value.deposit(carrying))carrying=ItemStack.EMPTY;else dropCarrying();
                    if(!carrying.isEmpty()){stop();return;}
                }
                var food=value.takeFood(entity);
                if(!food.isEmpty()&&!entity.life().beginMeal(food,null,false))carrying=value.returnFood(food);
                stop();
            }
        } else if(task==Task.NEST_PICKUP) {
            if(targetItem==null||!targetItem.isAlive()||targetItem.hasPickUpDelay()||!nestEligible(value)
                    ||!nearNest(value,targetItem.blockPosition())||!nestBedding(targetItem.getItem())){stop();return;}
            if(approach(targetItem.blockPosition())&&entity.distanceToSqr(targetItem)<2.25) {
                var remaining=targetItem.getItem().copy();var gathered=remaining.split(1);
                if(remaining.isEmpty())targetItem.discard();else targetItem.setItem(remaining);
                takeNestMaterial(value,gathered);
                targetItem=null;ticks=0;
                if(hasNestMaterials()){task=Task.NEST_BUILD;targetBlock=value.bed(entity.getUUID());buildTicks=0;}
                else if(!findNestBedding(value))stop();
            }
        } else if(task==Task.NEST_HARVEST) {
            if(targetBlock==null||!level().hasChunkAt(targetBlock)||!nearNest(value,targetBlock)
                    ||!level().getBlockState(targetBlock).is(Blocks.SHORT_GRASS)){stop();return;}
            if(approach(targetBlock)&&level().setBlock(targetBlock,Blocks.AIR.defaultBlockState(),3)) {
                takeNestMaterial(value,new ItemStack(Items.SHORT_GRASS));ticks=0;
                if(hasNestMaterials()){task=Task.NEST_BUILD;targetBlock=value.bed(entity.getUUID());buildTicks=0;}
                else if(!findNestBedding(value))stop();
            }
        } else if(task==Task.NEST_BUILD) {
            if(!hasNestMaterials()||targetBlock==null||!targetBlock.equals(value.bed(entity.getUUID()))||!nestEligible(value)){stop();return;}
            if(approach(targetBlock)) {
                entity.getNavigation().stop();buildTicks++;
                if(buildTicks>=120&&nestEligible(value)&&value.completePrivateNest(entity)){carrying=nestMaterial=ItemStack.EMPTY;nestHome=null;stop();}
            }
        } else if(task==Task.PICKUP) {
            if(targetItem==null||!targetItem.isAlive()||targetItem.hasPickUpDelay()
                    ||!(targetItem.getItem().is(ModItems.WASTE.get())&&value.contains(targetItem.blockPosition())
                    ||wantsResource(value,targetItem.getItem()))){stop();return;}
            if(approach(targetItem.blockPosition())&&entity.distanceToSqr(targetItem)<2.25) {
                carrying=targetItem.getItem().split(1);if(targetItem.getItem().isEmpty())targetItem.discard();targetItem=null;
                task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.HARVEST) {
            if(targetBlock==null||!level().hasChunkAt(targetBlock)||!level().getBlockState(targetBlock).is(Blocks.SHORT_GRASS)
                    ||!wantsResource(value,new ItemStack(Items.SHORT_GRASS))){stop();return;}
            if(approach(targetBlock)&&level().setBlock(targetBlock,Blocks.AIR.defaultBlockState(),3)) {
                carrying=new ItemStack(Items.SHORT_GRASS);task=Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.DEPOSIT) {
            if(approach(value.bed(entity.getUUID()))) {
                if(value.deposit(carrying))carrying=ItemStack.EMPTY;else if(entity.life().dryStand(entity.blockPosition()))dropCarrying();
                stop();
            }
        } else if(task==Task.CLEAN) {
            BlockPos outside=value.entrance().relative(value.facing(),2);
            if(!entity.life().dryStand(outside)){stop();return;}
            if(approach(outside)){dropCarrying();value.cleaned();stop();}
        }
    }
    private boolean approach(BlockPos pos) {
        Vec3 goal=Vec3.atBottomCenterOf(pos);
        if(entity.distanceToSqr(goal)<.64){entity.getNavigation().stop();return true;}
        if(ticks%20==1) {
            if(entity.distanceToSqr(goal)>100)pos=BlockPos.containing(entity.position().add(goal.subtract(entity.position()).normalize().scale(7)));
            if(!entity.life().navigate(pos,1,false))stop();
        }
        return false;
    }
    public void stop() { task=Task.NONE;targetItem=null;targetBlock=null;shareTo=null;wait=autumn()?40:80;ticks=buildTicks=0;entity.getNavigation().stop(); }
    public void dropCarrying() {
        if(entity.level().isClientSide)return;
        if(!carrying.isEmpty())drop(carrying,true);
        if(!nestMaterial.isEmpty())drop(nestMaterial,false);
        if(carrying.isEmpty()&&!nestMaterial.isEmpty()){carrying=nestMaterial;nestMaterial=ItemStack.EMPTY;}
    }
    private void drop(ItemStack stack,boolean primary) {
        var drop=new ItemEntity(level(),entity.getX(),entity.getY(),entity.getZ(),stack.copy());drop.setPickUpDelay(100);
        if(level().addFreshEntity(drop)){if(primary)carrying=ItemStack.EMPTY;else nestMaterial=ItemStack.EMPTY;}
    }
    public CompoundTag save() {
        var tag=new CompoundTag();FamilyData.putPosition(tag,"Home",home);
        FamilyData.putPosition(tag,"NestHome",nestHome);
        if(!carrying.isEmpty())tag.put("Carrying",carrying.save(entity.registryAccess()));
        if(!nestMaterial.isEmpty())tag.put("NestMaterial",nestMaterial.save(entity.registryAccess()));return tag;
    }
    public void load(CompoundTag tag) {
        home=FamilyData.position(tag,"Home");nestHome=FamilyData.position(tag,"NestHome");carrying=tag.contains("Carrying")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Carrying")):ItemStack.EMPTY;
        nestMaterial=tag.contains("NestMaterial")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("NestMaterial")):ItemStack.EMPTY;
        task=Task.NONE;targetItem=null;targetBlock=null;wait=ticks=buildTicks=0;
    }
}
