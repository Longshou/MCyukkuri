package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetSkill;

/** Single quote formula for the shop, buyback and consultation (docs/FEATURES.md). */
public final class ShopPricing {
    public static int price(YukkuriEntity y) {
        return Math.max(1, (int)Math.round(value(y) * (y.family().adult() ? .75 : 1)));
    }

    private static double value(YukkuriEntity y) {
        var v = y.getVariant();
        int base = SpeciesProfile.of(v).dangerToYukkuri() ? 30 : SpeciesProfile.of(v).houseBody() ? 3 : switch (v) { case ALICE, CHEN, MYON -> 8; case PATCHOULI -> 10; default -> 6; };
        var pet = y.pet();
        double total = base + switch (pet.effectiveGrade()) { case NONE -> 0; case BRONZE -> 4; case SILVER -> 12; case GOLD -> 28; case PLATINUM -> 30; };
        if (pet.effectiveGrade() == BadgeGrade.NONE) total *= 0.5;
        return total;
    }
    /** Percent of the quote the shop pays: 60 / 70 / 80 by effective grade (none counts as bronze). */
    public static int rate(YukkuriEntity y) { return PetPrivileges.resalePercent(y.pet().effectiveGrade()); }
    /** Percent taken off the buyback for stress, at most 30. */
    public static int penalty(YukkuriEntity y) {
        var pet = y.pet(); int p = 0;
        if (pet.stress() > BondLevels.UNEASE_SWEETENED) p += 5 * ((pet.stress() - 51) / 10);
        return Math.min(30, p);
    }
    public static int buyback(YukkuriEntity y) {
        int skills = 0;
        for (var skill : PetSkill.values()) if (y.pet().lessons().knows(skill)) skills++;
        double health = y.getHealth() / Math.max(1, y.getMaxHealth());
        return Math.max(1, (int)Math.round((value(y) + skills * 2) * (.5 + .5 * health)
                * (rate(y) / 100.0) * ((100 - penalty(y)) / 100.0)));
    }
    private ShopPricing() {}
}
