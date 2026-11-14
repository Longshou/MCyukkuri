package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
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

    /** What one serving of this stack gives at the swallow; the one authority the meal and the taste grade both read. */
    public static float serving(ItemStack food) {
        var data = food.get(DataComponents.FOOD);
        float gain = FillingResource.isFilling(food) ? FillingResource.nutrition(food)
                : food.is(ModItems.FOOD_SCRAP.get()) ? 10 : data == null ? 20 : Math.clamp(data.nutrition() * 5, 10, 35);
        if (food.is(ModItems.WASTE.get())) gain = LifeMeal.PIT_MEAL_NUTRITION * (float) DetachedItems.volume(food);
        else if (food.is(ModItems.GRAIN_BUDGET.get())) gain = 12;
        else if (food.is(ModItems.GRAIN_PREMIUM.get())) gain = 30;
        if (Sweets.is(food)) gain = Sweets.nutrition(food);
        return gain;
    }

    /** The taste tier of one serving from the same existing food data the meal reads. */
    public static Taste.Grade grade(ItemStack food) {
        if (Sweets.is(food) || Sweets.premium(food)) return Taste.Grade.TREAT;
        if (food.is(ModItems.WASTE.get()) || grass(food) || food.is(ModItems.FOOD_SCRAP.get())
                || food.is(YukkuriEntity.WATER_FOOD)) return Taste.Grade.COARSE;
        return serving(food) < 15 ? Taste.Grade.ORDINARY : Taste.Grade.FINE;
    }

    private FoodTaste() {}
}
