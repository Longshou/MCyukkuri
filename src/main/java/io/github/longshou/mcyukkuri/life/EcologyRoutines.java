package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.item.ItemStack;

/** Stateless dispatch only: no component instances, cached profiles or additional clock. */
public final class EcologyRoutines {
    private EcologyRoutines() {}

    public static boolean house(YukkuriEntity entity) {
        return entity.profile().ecology() == SpeciesProfile.Ecology.HOUSE;
    }

    public static boolean predator(YukkuriEntity entity) {
        return entity.profile().ecology() == SpeciesProfile.Ecology.PREDATOR;
    }

    /** The old kind-only capability. It does not gate ordinary passengers' safety or care. */
    public static boolean aquaticVoyage(YukkuriEntity entity) { return entity.profile().aquatic(); }

    public static void tick(EcologyRoutine routine) {
        if (routine.supportsPassiveTick()) routine.passiveTick();
    }

    public static void naturalSpawn(YukkuriEntity entity) {
        if (predator(entity)) entity.predator().naturalSpawn();
        else entity.settlement().naturalSpawn();
    }

    /** Used only after family paddle, family, independence and settlement-duty carrying have failed. */
    public static ItemStack carryingFallback(YukkuriEntity entity) {
        return house(entity) ? entity.house().carrying() : entity.habitat().carrying();
    }
}
