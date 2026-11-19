package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.Duty;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

public final class ConscienceGoal extends Goal {
    private final YukkuriEntity pet;
    private LivingEntity owner;

    public ConscienceGoal(YukkuriEntity pet) {
        this.pet = pet;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override public boolean canUse() {
        var conscience = pet.pet().conscience();
        if (!conscience.undiscovered() || !conscience.confesses() || !pet.duty().mayTake(Duty.Kind.RULES)) return false;
        owner = conscience.encounter();
        return owner != null;
    }

    @Override public void start() {
        pet.pet().conscience().reacted();
        pet.duty().take(Duty.Kind.RULES, 400, 600, "confess");
    }

    @Override public boolean canContinueToUse() {
        return pet.duty().holds(Duty.Kind.RULES) && pet.pet().conscience().undiscovered() && pet.pet().canWelcomeOwner()
                && pet.distanceToSqr(owner) <= 36 && HumanSight.sees(owner, pet) && pet.hasLineOfSight(owner);
    }

    @Override public void tick() {
        if (pet.distanceToSqr(owner) <= 2.25) {
            pet.pet().conscience().caught("confession");
            pet.duty().release(Duty.Kind.RULES, Duty.End.DONE, "confessed");
        } else {
            var stand = SurfaceApproach.beside(pet, owner, 1, .5, 1.5);
            if (stand == null || SurfaceApproach.walkResult(pet, stand, owner, .8) == SurfaceApproach.Result.BLOCKED)
                pet.duty().release(Duty.Kind.RULES, Duty.End.FAILED, "unreachable");
        }
    }

    @Override public void stop() {
        SurfaceApproach.stopWalking(pet);
        pet.duty().release(Duty.Kind.RULES, Duty.End.YIELDED, "interrupted");
    }
}
