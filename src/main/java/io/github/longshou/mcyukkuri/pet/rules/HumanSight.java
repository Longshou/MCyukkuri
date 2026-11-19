package io.github.longshou.mcyukkuri.pet.rules;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.life.GlassSight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class HumanSight {
    private HumanSight() {}

    public static boolean sees(LivingEntity human, Entity target) {
        var facing = human instanceof Player ? human.getViewVector(1) : Vec3.directionFromRotation(human.getXRot(), human.getYHeadRot());
        return human.level() == target.level() && target.isAlive() && !target.isInvisible() && !human.isSpectator()
                && Observation.inView(human.isAlive() && !human.isSleeping() && !(human instanceof HumanNpc npc && npc.resting()),
                human.distanceToSqr(target), facing.dot(target.getEyePosition().subtract(human.getEyePosition()).normalize()))
                && GlassSight.sees(human, target);
    }

    public static boolean watching(YukkuriEntity pet) {
        var owner = pet.pet().presentOwner();
        return owner != null && sees(owner, pet);
    }
}
