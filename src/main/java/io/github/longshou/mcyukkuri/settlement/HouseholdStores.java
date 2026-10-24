package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.network.chat.Component;

/** Daily household gathering target; the local biome does not predict a future season. */
public final class HouseholdStores {
    private HouseholdStores() {}

    public static int target(ShelterBlockEntity home) {
        return Math.min(home.foodCapacity(), Math.min(24, home.occupants() * 3));
    }

    public static Component observation(YukkuriEntity entity) {
        var home = entity.habitat().shelter();
        if (home == null) return Component.translatable("notebook.mcyukkuri.household_stores.none");
        return Component.translatable("notebook.mcyukkuri.household_stores", home.food(), target(home), home.foodCapacity());
    }
}
