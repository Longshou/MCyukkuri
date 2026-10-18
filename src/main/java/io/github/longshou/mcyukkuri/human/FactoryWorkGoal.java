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
        if(FactoryPipeline.managed(animal) && !animal.isPassenger() && !animal.operations().active() && !animal.life().hasUrgentNeed()
                && !animal.isPanicking()) { destination=null; return true; }
        if(!(animal.level() instanceof ServerLevel l)||animal.isPanicking()||animal.isPassenger()||animal.operations().active()
                ||animal.life().hasUrgentNeed()||animal.life().isSleeping()||animal.life().mealTicks()>0)return false;
        var tag=HumanCommerce.data(animal);
        if(!tag.hasUUID("Owner")||!(l.getEntity(tag.getUUID("Owner")) instanceof HumanNpc n)||!n.isAlive())return false;
        destination=n.production().workPosition(animal);return destination!=null;
    }
    @Override public boolean canContinueToUse(){return canUse();}
    @Override public void start(){steps=0;}
    @Override public boolean requiresUpdateEveryTick(){return true;}
    @Override public void tick(){if(destination==null){animal.getNavigation().stop();return;}if(steps++%20==0&&destination!=null)animal.getNavigation().moveTo(destination.getX()+.5,destination.getY(),destination.getZ()+.5,.8);}
    @Override public void stop(){animal.getNavigation().stop();destination=null;}
}
