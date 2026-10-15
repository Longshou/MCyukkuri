package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyData;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** A real resident, never a hidden duplicate: carries one resource, maintains stores and clears waste. */
public final class HabitatLife {
    private enum Task { NONE, FOOD, PICKUP, HARVEST, DEPOSIT, CLEAN, SHARE, STAY, SIESTA }
    private final YukkuriEntity entity;
    private GlobalPos home;
    private ItemStack carrying=ItemStack.EMPTY;
    private ItemEntity targetItem;
    private BlockPos targetBlock;
    private Task task=Task.NONE;
    private int wait,ticks;
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
    public String activity() { return task==Task.CLEAN?"shelter_clean":task==Task.DEPOSIT||task==Task.SHARE?"shelter_carry":task==Task.STAY||task==Task.SIESTA?"idle":task==Task.HARVEST||task==Task.PICKUP?"shelter_gather":"shelter_stock"; }
    public BlockPos homeStand() { var value=shelter();return value!=null&&value.usable(entity)?value.bed(entity.getUUID()):null; }
    public boolean leaking() {
        var value=shelter();
        return value!=null&&value.moisture()>=60&&value.contains(entity.blockPosition());
    }
    public boolean choose(ShelterBlockEntity value) {
        if(roaming()||entity.getVariant().isHouse()||entity.getVariant().isPredator()||!value.sound()||value.moisture()>=85||value.dirt()>=90||entity.distanceToSqr(Vec3.atBottomCenterOf(value.getBlockPos()))>32*32)return false;
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
    public void release() { var value=shelter();if(value!=null)value.release(entity.getUUID());home=null;stop(); }
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
        if(wait>0)wait--;
        if(shareCooldown>0)shareCooldown--;
        if(quarrelCooldown>0)quarrelCooldown--;
        if(entity.tickCount%100!=0)return;
        var value=shelter();boolean inside=value!=null&&value.contains(entity.blockPosition());
        boolean cold=level().getBiome(entity.blockPosition()).value().getBaseTemperature()<.2f;
        int wet=inside&&value.moisture()>=60?3:0;
        int hygiene=inside&&value.dirt()>=50?2:0;
        int energy=cold&&!(inside&&value.insulated())?2:0;
        entity.life().applyEnvironment(wet,hygiene,energy);
        if(cold&&entity.life().wetness()>40&&!(inside&&value.insulated())&&entity.tickCount%400==0)
            entity.hurt(level().damageSources().freeze(),1);
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
    /** Autumn regions stockpile harder: more portions per resident. */
    private int stockGoal(ShelterBlockEntity value) {
        return autumn()?Math.min(40,value.occupants()*YukkuriConfig.AUTUMN_STOCK_PER_RESIDENT.get()):Math.min(24,value.occupants()*3);
    }
    private boolean ready() {
        return !entity.getVariant().isHouse()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()
                &&!entity.life().isSleeping()&&!entity.isInWaterOrBubble()&&!entity.isOnFire()&&entity.life().mealTicks()==0
                &&!Migration.underway(entity);
    }
    public boolean canUse() {
        if(!ready())return false;
        if(active())return true;
        if(wait>0)return false;wait=autumn()?30:60;
        var value=shelter();boolean housed=value!=null&&value.usable(entity);
        if(housed&&entity.life().canEat()&&value.food()>0){task=Task.FOOD;targetBlock=value.bed(entity.getUUID());ticks=0;arrived=false;return true;}
        if(seasonal(housed?value:null))return true;
        if(!housed)return false;
        if(shareStart(value))return true;
        if(!EventHooks.canEntityGrief(level(),entity)||entity.life().nutrition()<40||entity.life().energy()<35)return false;
        if(!carrying.isEmpty()){task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;return true;}
        targetItem=value.wasteInside().stream().filter(item->!item.hasPickUpDelay()).findFirst().orElse(null);
        if(targetItem==null)targetItem=level().getEntitiesOfClass(ItemEntity.class,entity.getBoundingBox().inflate(10),item->item.isAlive()&&!item.hasPickUpDelay()
                &&(value.needsMaterial(item.getItem())||item.getItem().is(YukkuriEntity.FOOD)&&value.food()<stockGoal(value)))
                .stream().filter(item->entity.life().reachable(item.blockPosition(),false)).min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);
        if(targetItem!=null){task=Task.PICKUP;ticks=0;return true;}
        if(value.food()<stockGoal(value)||value.needsMaterial(new ItemStack(Items.SHORT_GRASS))) {
            for(var pos:BlockPos.betweenClosed(entity.blockPosition().offset(-8,-2,-8),entity.blockPosition().offset(8,2,8)))
                if(level().hasChunkAt(pos)&&level().getBlockState(pos).is(Blocks.SHORT_GRASS)&&entity.life().dryStand(pos)&&entity.life().reachable(pos,false)){
                    targetBlock=pos.immutable();task=Task.HARVEST;ticks=0;return true;
                }
        }
        return false;
    }
    /** Winter closed nest and summer noon rest. Both only hold the animal in place; eating still comes first. */
    private boolean seasonal(ShelterBlockEntity value) {
        if(!Seasons.enabled()||entity.getVariant().isPredator()||entity.family().active())return false;
        if(value!=null&&Seasons.confined(entity)){task=Task.STAY;targetBlock=value.bed(entity.getUUID());ticks=0;arrived=false;return true;}
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
        if(group==null||shareCooldown>0||!carrying.isEmpty()||entity.life().nutrition()<40||Migration.moving(group)||!Seasons.assembling(level(),group)
                ||value.food()<=value.occupants()*YukkuriConfig.SHARE_SURPLUS_PER_RESIDENT.get())return false;
        ShelterBlockEntity best=null;
        var members=level().getEntitiesOfClass(YukkuriEntity.class,new AABB(group.center).inflate(40),
                other->other!=entity&&other.isAlive()&&group.id.equals(other.settlement().groupId()));
        for(var other:members) {
            var shelter=other.habitat().shelter();
            if(shelter==null||shelter.getBlockPos().equals(value.getBlockPos())||shelter.food()>=shelter.occupants()*YukkuriConfig.SHARE_NEED_PER_RESIDENT.get()
                    ||shelter.food()>=shelter.foodLimit())continue;
            if(best==null||shelter.food()<best.food())best=shelter;
        }
        if(best==null||!entity.life().reachable(best.entrance(),false))return false;
        var food=value.takeFood(entity);
        if(food.isEmpty())return false;
        carrying=food;shareTo=best.getBlockPos();task=Task.SHARE;ticks=0;shareCooldown=YukkuriConfig.SHARE_COOLDOWN.get();return true;
    }
    public boolean canContinue() {
        if(!active()||!ready())return false;
        if(task==Task.SIESTA)return ticks<1200&&targetBlock!=null&&Seasons.is(entity,Season.SUMMER)&&Seasons.noon(level());
        if(task==Task.STAY)return homeStand()!=null&&Seasons.confined(entity);
        return ticks<400&&homeStand()!=null&&(task==Task.FOOD||task==Task.SHARE||EventHooks.canEntityGrief(level(),entity));
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
                var food=value.takeFood(entity);
                if(!food.isEmpty()&&!entity.life().beginMeal(food,null,false))value.returnFood(food);
                stop();
            }
        } else if(task==Task.PICKUP) {
            if(targetItem==null||!targetItem.isAlive()||targetItem.hasPickUpDelay()
                    ||!(targetItem.getItem().is(ModItems.WASTE.get())||value.accepts(targetItem.getItem()))){stop();return;}
            if(approach(targetItem.blockPosition())&&entity.distanceToSqr(targetItem)<2.25) {
                carrying=targetItem.getItem().split(1);if(targetItem.getItem().isEmpty())targetItem.discard();targetItem=null;
                task=carrying.is(ModItems.WASTE.get())?Task.CLEAN:Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.HARVEST) {
            if(targetBlock==null||!level().getBlockState(targetBlock).is(Blocks.SHORT_GRASS)){stop();return;}
            if(approach(targetBlock)&&level().setBlock(targetBlock,Blocks.AIR.defaultBlockState(),3)) {
                carrying=new ItemStack(Items.SHORT_GRASS);task=Task.DEPOSIT;ticks=0;
            }
        } else if(task==Task.DEPOSIT) {
            if(approach(value.bed(entity.getUUID()))) {
                if(value.deposit(carrying))carrying=ItemStack.EMPTY;else dropCarrying();
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
    public void stop() { task=Task.NONE;targetItem=null;targetBlock=null;shareTo=null;wait=autumn()?40:80;entity.getNavigation().stop(); }
    public void dropCarrying() { if(!carrying.isEmpty()){var drop=entity.spawnAtLocation(carrying.copy());if(drop!=null){drop.setPickUpDelay(100);carrying=ItemStack.EMPTY;}} }
    public CompoundTag save() {
        var tag=new CompoundTag();FamilyData.putPosition(tag,"Home",home);
        if(!carrying.isEmpty())tag.put("Carrying",carrying.save(entity.registryAccess()));return tag;
    }
    public void load(CompoundTag tag) {
        home=FamilyData.position(tag,"Home");carrying=tag.contains("Carrying")?ItemStack.parseOptional(entity.registryAccess(),tag.getCompound("Carrying")):ItemStack.EMPTY;
        task=Task.NONE;targetItem=null;targetBlock=null;wait=ticks=0;
    }
}
