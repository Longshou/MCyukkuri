package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Bitter, spicy and poisonous food (komicolle 11518847: "bitter and spicy are poison to yukkuri, though a little bitterness can be endured").
 * Item tags choose the dose: {@code bitter_food} adds {@link #BITTER_DOSE}, {@code toxic_food} adds {@link #TOXIC_DOSE}. The poison load
 * itself lives in {@code BodyLife.poisoned}. The numbers and the item lists are project design.
 */
public final class Toxins {
    public static final TagKey<Item> BITTER = TagKey.create(Registries.ITEM, MCYukkuri.id("bitter_food"));
    public static final TagKey<Item> TOXIC = TagKey.create(Registries.ITEM, MCYukkuri.id("toxic_food"));
    public static final int BITTER_DOSE = 1, TOXIC_DOSE = 4, SPICY_DOSE = 3;
    /** Below this nutrition a yukkuri swallows even bitter or poisonous food; otherwise it refuses with disgust. */
    public static final float STARVING = 15;

    private Toxins() {}

    public static int dose(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        return stack.is(TOXIC) ? TOXIC_DOSE : stack.is(BITTER) ? BITTER_DOSE : 0;
    }
}
