package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import net.minecraft.world.phys.Vec3;

/** A host's measured birth space; choosing it never reserves or seats a newborn. */
public interface BirthCabin {
    PlacedHatEntity hat();
    boolean fits(FamilyData.Brood brood);
    boolean ready();
    boolean clear(FamilyData.Brood brood, int index);
    Vec3 exit(int index);
    /** The newborn's ordinary impulse from the outlet into the hollow, or null when no cloth lies under it in the fall. */
    BirthLaunch.Nominal launch(FamilyData.Brood brood, int index);
    String clip();
    /** The mother's supported underside in host units, read by her own birth goal. */
    double underside();
}
