package io.github.longshou.mcyukkuri.status;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityTypeTest;

public final class ThreatCandidates {
    /** Uses the section's candidate index while vanilla supplies section order and live box intersections. */
    public static final EntityTypeTest<Entity, LivingEntity> TYPE = EntityTypeTest.forClass(LivingEntity.class);

    private ThreatCandidates() {}

    public static boolean includes(Object entity) {
        return entity instanceof HumanNpc
                || entity instanceof YukkuriEntity y && y.getVariant().isPredator();
    }
}
