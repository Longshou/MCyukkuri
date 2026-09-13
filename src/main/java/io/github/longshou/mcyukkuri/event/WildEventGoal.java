package io.github.longshou.mcyukkuri.event;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Optional event movement yields to physiology, restraint and danger through the goal selector. */
public final class WildEventGoal extends Goal {
    private final YukkuriEntity entity;
    public WildEventGoal(YukkuriEntity entity){this.entity=entity;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
    @Override public boolean canUse(){return WildEvents.reserved(entity)&&WildEvents.safe(entity)
            &&!entity.life().hasUrgentNeed()&&entity.life().mealTicks()==0&&!entity.dailyLife().active()&&!entity.social().active();}
    @Override public boolean canContinueToUse(){return canUse();}
    @Override public boolean requiresUpdateEveryTick(){return true;}
    @Override public void tick() {
        var level=(ServerLevel)entity.level();var scene=WildEventData.get(level).forMember(entity.getUUID());if(scene==null)return;
        if(scene.kind==WildEventData.Kind.RIVER){RiverEncounter.move(entity,scene);return;}
        if(scene.kind==WildEventData.Kind.EXPEDITION){Expedition.move(entity,scene);return;}
        if(scene.kind==WildEventData.Kind.CARD){WildCards.move(entity,scene);return;}
        Entity target=scene.player==null?null:level.getPlayerByUUID(scene.player);
        if(target==null && scene.members.size()>1)target=level.getEntity(scene.members.getFirst());
        if(target==null||target==entity)return;
        entity.getLookControl().setLookAt(target,25,25);
        if(scene.kind==WildEventData.Kind.ABANDONMENT) {
            if(scene.phaseTicks<80){entity.getNavigation().stop();return;}
            if(scene.phaseTicks%20==0) {
                Vec3 away=entity.position().subtract(target.position()).multiply(1,0,1).normalize();
                BlockPos goal=BlockPos.containing(entity.position().add(away.scale(3)));
                if(entity.life().dryStand(goal))entity.life().navigate(goal,.7,false);
            }
        } else if(entity.distanceToSqr(target)>9 && scene.phaseTicks%20==0) {
            if(!entity.life().navigate(target.blockPosition(),.75,false))WildEvents.finish(level,scene,WildEventData.Phase.INTERRUPTED,"unreachable_player");
        } else if(entity.distanceToSqr(target)<=9)entity.getNavigation().stop();
    }
    @Override public void stop(){entity.getNavigation().stop();}
}
