package io.github.longshou.mcyukkuri.human;

import javax.annotation.Nullable;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/** Owns navigation while a factory animal approaches its station; care, panic and player operations take priority. */
public final class FactoryWorkGoal extends Goal {
    private final YukkuriEntity animal;
    private BlockPos destination;
    private int steps;
    public FactoryWorkGoal(YukkuriEntity animal){this.animal=animal;setFlags(EnumSet.of(Flag.MOVE,Flag.LOOK));}
    /** The destination is chosen by AI, never recomputed for display. Managed waiting is not production. */
    @Nullable public String activity() {
        if (animal.life().mealTicks() > 0) return animal.life().activity();
        if (destination != null) return Behaviors.FACTORY_APPROACH;
        return FactoryPipeline.managed(animal) ? Behaviors.FACTORY_WAIT : null;
    }

    @Override public boolean canUse(){
        if (!animal.onGround() || animal.isPassenger()) return false;
        var work=assignedWork();
        if(work!=null){ destination=work; return true; }
        if(managedWaiting()){ destination=null; return true; }
        destination=null;
        return false;
    }
    /** Null means that this individual has no concrete station to report to right now. */
    @Nullable private BlockPos assignedWork(){
        if(!(animal.level() instanceof ServerLevel l)||animal.isPanicking()||animal.operations().active()
                ||animal.life().hasUrgentNeed()||animal.life().isSleeping()||animal.life().mealTicks()>0)return null;
        var custody=Custody.view(animal);
        if(custody.owner()==null||!(l.getEntity(custody.owner()) instanceof HumanNpc n)||!n.isAlive())return null;
        return n.production().workPosition(animal);
    }
    private boolean managedWaiting(){
        return FactoryPipeline.managed(animal) && !animal.operations().active() && !animal.life().hasUrgentNeed()
                && !animal.isPanicking();
    }
    @Override public boolean canContinueToUse(){return canUse();}
    @Override public void start(){steps=0;}
    @Override public boolean requiresUpdateEveryTick(){return true;}
    @Override public void tick(){
        if(destination==null){animal.getNavigation().stop();return;}
        if(steps++%20==0)animal.getNavigation().moveTo(destination.getX()+.5,destination.getY(),destination.getZ()+.5,.8);
    }
    @Override public void stop(){
        animal.getNavigation().stop();destination=null;
    }
}
