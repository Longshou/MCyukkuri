package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Stateless dispatch only: no component instances, cached profiles or additional clock. */
public final class EcologyRoutines {
    private EcologyRoutines() {}

    public static boolean house(YukkuriEntity entity) {
        return entity.profile().ecology() == SpeciesProfile.Ecology.HOUSE;
    }

    public static boolean predator(YukkuriEntity entity) {
        return entity.profile().ecology() == SpeciesProfile.Ecology.PREDATOR;
    }

    public static void tick(EcologyRoutine routine) {
        if (routine.supportsPassiveTick()) routine.passiveTick();
    }

    public static void naturalSpawn(YukkuriEntity entity) {
        if (predator(entity)) entity.predator().naturalSpawn();
        else entity.settlement().naturalSpawn();
    }

}
