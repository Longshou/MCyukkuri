package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class HumanSight {
    private HumanSight() {}

    public static boolean sees(LivingEntity human, Entity target) {
        return human.isAlive() && !human.isSleeping() && human.level() == target.level() && target.isAlive()
                && human.distanceToSqr(target) <= 256 && human.hasLineOfSight(target)
                && (!(human instanceof Player) || !human.isSpectator() && human.getViewVector(1)
                        .dot(target.getEyePosition().subtract(human.getEyePosition()).normalize()) > .5);
    }

    public static boolean watching(YukkuriEntity pet) {
        var owner = pet.pet().presentOwner();
        return owner != null && sees(owner, pet);
    }
}
