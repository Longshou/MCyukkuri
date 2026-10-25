package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Grass is edible fallback food, but its taste is poor; droppings (only a slave eats them) are the last resort. */
public final class FoodTaste {
    public static boolean grass(ItemStack food) {
        return food.is(Items.SHORT_GRASS) || food.is(Items.TALL_GRASS)
                || food.is(Items.FERN) || food.is(Items.LARGE_FERN);
    }

    /** Lower values are chosen first among reachable loose food. */
    public static int rank(ItemStack food) { return food.is(ModItems.WASTE.get()) ? 2 : grass(food) ? 1 : 0; }

    private FoodTaste() {}
}
