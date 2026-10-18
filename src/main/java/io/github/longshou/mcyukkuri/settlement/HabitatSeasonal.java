package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import io.github.longshou.mcyukkuri.settlement.HabitatLife.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

/** Seasonal rest, sharing and winter quarrels delegated by HabitatLife. */
final class HabitatSeasonal {
    private final HabitatLife o;
    // Transient seasonal state: none of these fields changes the save format.
    private boolean shareLoaded;
    private BlockPos shareTo;
    private int shareCooldown,quarrelCooldown;
    private long siestaDay=-1;
    private boolean arrived;
    HabitatSeasonal(HabitatLife o) { this.o=o; }
    private ServerLevel level() { return o.level(); }
    void clock() {
        if(shareCooldown>0)shareCooldown--;
        if(quarrelCooldown>0)quarrelCooldown--;
    }
    void quarrelIfNeeded(ShelterBlockEntity value) { if(value!=null&&quarrelCooldown==0&&SettlementSeasons.is(o.entity(),Season.WINTER)&&value.food()==0&&o.entity().life().nutrition()<30)quarrel(value); }
    int quarrelCooldown() { return quarrelCooldown; }
    void quarrelCooldown(int ticks) { quarrelCooldown=ticks; }
    void resetLoad() { shareLoaded=false; }
    void clearShareTarget() { shareTo=null; }
    boolean canSiestaContinue() { return o.ticks()<1200&&o.targetBlock()!=null&&SettlementSeasons.is(o.entity(),Season.SUMMER)&&Seasons.noon(level())&&!o.entity().life().hasUrgentNeed(); }
    boolean canStayContinue() { return o.homeStand()!=null&&SettlementSeasons.confined(o.entity())&&!o.entity().wintering().usesWinterStores()&&!o.entity().life().hasUrgentNeed(); }
    /** Winter famine: two starving housemates over empty stores snap at each other. Sacrifice for the family is not modelled yet (plan 2.4). */
    private void quarrel(ShelterBlockEntity value) {
        for(var id:value.residentIds()) {
            if(id.equals(o.entity().getUUID())||o.entity().getUUID().compareTo(id)>0)continue;
            if(!(level().getEntity(id) instanceof YukkuriEntity other)||!other.isAlive()||other.life().nutrition()>=30
                    ||other.habitat().quarrelCooldown()>0||o.entity().distanceToSqr(other)>256)continue;
            var relations=YukkuriWorldData.get(level());
            int loss=-YukkuriConfig.WINTER_QUARREL_PENALTY.get();
            relations.relate(o.entity().getUUID(),other.getUUID(),loss);relations.relate(other.getUUID(),o.entity().getUUID(),loss);
            boolean weaker=o.entity().life().nutrition()<=other.life().nutrition();
            o.entity().showReaction(weaker?"teary":"angry","mine",80);other.showReaction(weaker?"angry":"teary","mine",80);
            quarrelCooldown=2400;other.habitat().quarrelCooldown(2400);return;
        }
    }
    /** Winter closed nest and summer noon rest. Both only hold the animal in place; eating still comes first. */
    boolean start(ShelterBlockEntity value) {
        if(!Seasons.enabled()||o.entity().profile().dangerToYukkuri()||o.entity().family().active()
                ||o.entity().independence().householdOuting()||o.entity().life().hasUrgentNeed())return false;
        if(value!=null&&!o.entity().wintering().usesWinterStores()&&SettlementSeasons.confined(o.entity())){o.task(Task.STAY);o.targetBlock(value.bed(o.entity().getUUID()));o.resetTicks();arrived=false;return true;}
        long day=level().getDayTime()/24000;
        if(siestaDay==day||!SettlementSeasons.is(o.entity(),Season.SUMMER)||!Seasons.noon(level())||level().isRaining()
                ||o.entity().life().nutrition()<30||o.entity().life().covered(o.entity().blockPosition()))return false;
        siestaDay=day;
        BlockPos shade=null;double best=Double.MAX_VALUE;
        for(var pos:BlockPos.betweenClosed(o.entity().blockPosition().offset(-6,-2,-6),o.entity().blockPosition().offset(6,2,6))) {
            double distance=pos.distSqr(o.entity().blockPosition());
            if(distance<best&&level().hasChunkAt(pos)&&o.entity().life().covered(pos)&&ShelterBlock.shelter(level(),pos)==null
                    &&o.entity().life().dryStand(pos)&&o.entity().life().reachable(pos,false)){shade=pos.immutable();best=distance;}
        }
        if(shade==null&&value!=null){BlockPos bed=value.bed(o.entity().getUUID());if(bed!=null&&o.entity().distanceToSqr(Vec3.atBottomCenterOf(bed))<=24*24)shade=bed;}
        if(shade==null)return false;
        o.task(Task.SIESTA);o.targetBlock(shade);o.resetTicks();arrived=false;return true;
    }
    /** At a gathering, a household with plenty hands one real portion to the poorest other household of the group. */
    boolean shareStart(ShelterBlockEntity value) {
        var group=o.entity().settlement().group();
        if(group==null||shareCooldown>0||!o.carrying().isEmpty()||o.entity().independence().householdOuting()
                ||o.entity().settlement().society().dejected()||!o.entity().settlement().duties().carrying().isEmpty()
                ||!EventHooks.canEntityGrief(level(),o.entity())||o.entity().life().energy()<35||o.entity().life().nutrition()<40||Migration.moving(group)||!SettlementSeasons.assembling(level(),group)
                ||value.food()<=value.occupants()*YukkuriConfig.SHARE_SURPLUS_PER_RESIDENT.get())return false;
        if(value.pile()==null)return false;
        ShelterBlockEntity best=null;
        var members=level().getEntitiesOfClass(YukkuriEntity.class,new AABB(group.center).inflate(40),
                other->other!=o.entity()&&other.isAlive()&&group.id.equals(other.settlement().groupId()));
        for(var other:members) {
            var shelter=other.habitat().shelter();
            if(shelter==null||shelter.getBlockPos().equals(value.getBlockPos())||shelter.food()>=shelter.occupants()*YukkuriConfig.SHARE_NEED_PER_RESIDENT.get()
                    ||shelter.food()>=shelter.foodCapacity())continue;
            if(best==null||shelter.food()<best.food())best=shelter;
        }
        var bestPile=best==null?null:best.pile();
        if(bestPile==null||!value.hasFoodFor(o.entity())||!o.entity().life().reachable(bestPile.standCell(),false))return false;
        // Nothing is taken yet: the portion leaves the own pile by real mouth contact once the errand is under way.
        shareTo=best.getBlockPos();shareLoaded=false;o.resetContact();o.task(Task.SHARE);o.resetTicks();shareCooldown=YukkuriConfig.SHARE_COOLDOWN.get();return true;
    }
    void tickRest(ShelterBlockEntity value) {
        if(o.task()==Task.STAY&&value!=null&&o.entity().life().canEat()&&value.food()>0){o.task(Task.FOOD);o.targetBlock(value.bed(o.entity().getUUID()));o.resetTicks();return;}
        if(o.approach(o.targetBlock())) {
            o.entity().getNavigation().stop();
            if(!arrived){arrived=true;if(o.task()==Task.SIESTA)o.entity().showReaction("tired","puff",80);}
        }
        return;
    }
    void tickShare(ShelterBlockEntity value) {
        if(!shareLoaded) {
            var own=value==null?null:value.pile();
            if(own==null||value.food()<=value.occupants()*YukkuriConfig.SHARE_SURPLUS_PER_RESIDENT.get()){o.stop();return;}
            if(!o.mouth(own))return;
            var food=value.takeFood(o.entity());
            if(food.isEmpty()){o.stop();return;}
            o.carry(food);shareLoaded=true;o.resetContact();o.resetTicks();return;
        }
        var other=shareTo==null?null:ShelterBlock.shelter(level(),shareTo);
        var pile=other==null?null:other.ensurePile();
        if(pile==null){o.task(Task.DEPOSIT);o.resetTicks();return;}
        if(!o.mouth(pile))return;
        if(other.deposit(o.carrying())){o.carry(ItemStack.EMPTY);o.entity().showReaction("happy","share",80);o.stop();}
        else{o.task(Task.DEPOSIT);o.resetTicks();}
        return;
    }
}
