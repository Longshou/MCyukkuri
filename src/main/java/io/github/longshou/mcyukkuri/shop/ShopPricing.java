package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.pet.Ability;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;

/** Single quote formula for the shop, buyback and consultation (PET_SHOP.md section 6). */
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
    public static int buyback(YukkuriEntity y) { return Math.max(1, (int)Math.round(price(y) * 0.6)); }
    private ShopPricing() {}
}
