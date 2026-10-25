package io.github.longshou.mcyukkuri.subspecies.crew;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Holds every participant of a {@link CrewTransfer} (the body and its carrier) while the ticket runs. Priority 1, MOVE|JUMP|LOOK:
 * the decision layers below it (aquatic life, family, daily life) stand aside, and nothing but the ticket walks, turns or jumps
 * the pair. Whichever participant's goal runs first in a tick advances the ticket; the other only keeps still.
 */
public final class CrewTransferGoal extends Goal {
    private final YukkuriEntity owner;

    public CrewTransferGoal(YukkuriEntity owner) {
        this.owner = owner;
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override public boolean canUse() { return owner.family().crew().active(); }
    @Override public boolean canContinueToUse() { return owner.family().crew().active(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public boolean isInterruptable() { return false; }

    @Override public void tick() {
        var ticket = owner.family().crew().ticket();
        if (ticket != null) ticket.tick();
    }

    @Override public void stop() {
        // A goal taken away mid-ticket (a higher priority one) is not a transfer ended: the ticket's own limits end it.
        if (!owner.family().crew().active()) owner.getNavigation().stop();
    }
}
