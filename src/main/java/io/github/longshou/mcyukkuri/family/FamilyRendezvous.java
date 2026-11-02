package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.phys.Vec3;

/** A parent's running activity requests a real ground stand; ordinary family follow executes it. */
public interface FamilyRendezvous {
    Vec3 gatherAt(YukkuriEntity child);
}
