package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.PetPrivileges;

/** Single quote formula for the shop, buyback and consultation (PET_SHOP.md section 6 and section 12). */
public final class ShopPricing {
    public static int price(YukkuriEntity y) {
        var v = y.getVariant();
        int base = v.isPredator() ? 30 : v.isHouse() ? 3 : switch (v) { case ALICE, CHEN -> 8; case PATCHOULI -> 10; default -> 6; };
        var pet = y.pet(); int sum = 0;
        for (var a : Ability.values()) sum += pet.ability(a);
        double total = base + switch (pet.effectiveGrade()) { case BRONZE -> 4; case SILVER -> 12; case GOLD -> 28; default -> 0; } + pet.pedigree() / 5 + sum / 4 / 10;
        if (pet.effectiveGrade() == BadgeGrade.NONE) { total *= 0.5; if (ShopDeliveries.factoryOrigin(y)) total *= 0.8; } // unbadged factory shipments are sold 20% cheaper
        return Math.max(1, (int)Math.round(total));
    }
    /** Percent of the quote the shop pays: 60 / 70 / 80 by effective grade (none counts as bronze). */
    public static int rate(YukkuriEntity y) { return PetPrivileges.resalePercent(y.pet().effectiveGrade()); }
    /** Percent taken off the buyback for a stressed or spoiled pet: 5 per ten stress points above 60 and 3 per ten indulgence points above 60, at most 30. */
    public static int penalty(YukkuriEntity y) {
        var pet = y.pet(); int p = 0;
        if (pet.stress() > 60) p += 5 * ((pet.stress() - 51) / 10);
        if (pet.indulgence() > 60) p += 3 * ((pet.indulgence() - 51) / 10);
        return Math.min(30, p);
    }
    public static int buyback(YukkuriEntity y) { return Math.max(1, (int)Math.round(price(y) * (rate(y) / 100.0) * ((100 - penalty(y)) / 100.0))); }
    private ShopPricing() {}
}
