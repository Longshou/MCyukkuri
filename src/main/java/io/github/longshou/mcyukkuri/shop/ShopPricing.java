package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.social.BondLevels;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;
import io.github.longshou.mcyukkuri.pet.PetSkill;

/**
 * Single quote formula for the shop, buyback and consultation (docs/FEATURES.md, 宠物店). The value is the species base (rarer species and
 * predators more) plus the effective badge, halved without a badge; adults sell for {@link #ADULT} of it, like a pet shop's grown animals.
 * Buyback pays the shop's rate of the value and each learned skill, scaled by health and cut by stress: the better the individual, the more.
 */
public final class ShopPricing {
    /** Grown animals are cheaper (user 2026-11-13); the young are the shop's main stock. */
    static final double ADULT = .6;
    /** Each learned skill adds this many emeralds to the buyback value. */
    private static final int SKILL = 2;

    public static int price(ShopKind kind, YukkuriEntity animal) {
        double value = value(animal);
        return Math.max(1, (int) Math.round(kind == ShopKind.PET ? value : value / 4));
    }

    private static double value(YukkuriEntity y) {
        var v = y.getVariant();
        int base = SpeciesProfile.of(v).dangerToYukkuri() ? 30 : SpeciesProfile.of(v).houseBody() ? 3 : switch (v) { case ALICE, CHEN, MYON -> 8; case PATCHOULI -> 10; default -> 6; };
        var pet = y.pet();
        double total = base + switch (pet.effectiveGrade()) { case NONE -> 0; case BRONZE -> 4; case SILVER -> 12; case GOLD -> 28; case PLATINUM -> 30; };
        if (pet.effectiveGrade() == BadgeGrade.NONE) total *= 0.5;
        return total * (y.family().adult() ? ADULT : 1);
    }
    /** Percent of the value the shop pays: 60 / 70 / 80 by effective grade (none counts as bronze). */
    public static int rate(YukkuriEntity y) { return PetPrivileges.resalePercent(y.pet().effectiveGrade()); }
    /** Percent taken off the buyback for stress, at most 30. */
    public static int penalty(YukkuriEntity y) {
        var pet = y.pet(); int p = 0;
        if (pet.stress() > BondLevels.UNEASE_SWEETENED) p += 5 * ((pet.stress() - 51) / 10);
        return Math.min(30, p);
    }
    static int buyback(ShopKind kind, YukkuriEntity animal) {
        double quality = qualityQuote(animal);
        if (kind == ShopKind.PET) return Math.max(1, (int) Math.round(quality));
        return 1 + (int) Math.round(7 * (1 - Math.clamp(quality / 40, 0, 1)));
    }

    private static double qualityQuote(YukkuriEntity y) {
        int skills = 0;
        for (var skill : PetSkill.values()) if (y.pet().lessons().knows(skill)) skills++;
        double health = y.getHealth() / Math.max(1, y.getMaxHealth());
        return (value(y) + skills * SKILL) * (.5 + .5 * health) * (rate(y) / 100.0) * ((100 - penalty(y)) / 100.0);
    }
    private ShopPricing() {}
}
