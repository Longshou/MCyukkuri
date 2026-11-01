package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;

/** One choice at the beginning of play; observing a desire never draws randomness. */
final class KidPlayRule {
    private KidPlayRule() {}

    static boolean available(boolean jumper, int stores) { return jumper || stores > 0; }

    /** {@code brimReachable} vetoes only the splash: the other two are played where the young sits. */
    static String pick(boolean jumper, boolean paddling, int stores, boolean brimReachable, double draw, double pokeChance) {
        if (jumper && paddling) return Behaviors.KID_MIMIC_PADDLE;
        if (stores > 0 && (!jumper || draw < pokeChance)) return Behaviors.KID_POKE_STORES;
        return jumper && brimReachable ? Behaviors.KID_BRIM_SPLASH : null;
    }
}
