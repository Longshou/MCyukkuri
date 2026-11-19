package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.social.Temperament;
import io.github.longshou.mcyukkuri.social.Temperament.Gate;

enum SupplyGuard {
    BOWL, WATER;

    boolean guards(Temperament occupant, int grade, boolean careParent, boolean kin, float need) {
        if (grade >= 2 || careParent || !occupant.is(Gate.BOLDISH) || kin && occupant.is(Gate.KINDLY)) return false;
        return occupant.is(Gate.HARSH) || (this == WATER ? need >= 70 : need < 25);
    }
}
