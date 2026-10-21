package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Project design: combat specialists use courage instead of the ordinary low-health flight rule. */
public final class CombatCourage {
    private CombatCourage() {}
    public static float retreatRatio(YukkuriEntity animal) {
        return .65f - animal.life().personality().boldness() * .0055f;
    }
    public static boolean willing(YukkuriEntity animal) {
        return animal.life().personality().boldness() >= 35 && animal.body().healthRatio() > retreatRatio(animal);
    }
}
