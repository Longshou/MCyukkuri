package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** The dedicated centre filling, plus read-only recognition of its old item form. */
public final class FillingResource {
    private FillingResource() {}

    public static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    /** The centre filling taken from remains whose core was never hurt. */
    public static ItemStack core(CompoundTag remains) {
        ItemStack stack = new ItemStack(ModItems.CORE_FILLING.get());
        var body = remains.getCompound("Body");
        int sweetness = body.contains("CoreSweetness") ? Math.clamp(body.getInt("CoreSweetness"), 0, 100) : 90;
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt("FillingVersion", 1);
            tag.putUUID("Portion", UUID.randomUUID());
            if (remains.hasUUID("Identity")) tag.putUUID("Source", remains.getUUID("Identity"));
            tag.putString("Variant", remains.getString("Variant"));
            tag.putFloat("SourceScale", remains.contains("SourceScale") ? remains.getFloat("SourceScale") : 1);
            tag.putString("Composition", CORE);
            tag.putString("Method", "core");
            tag.putInt("Nutrition", 10);
            tag.putInt("Sweetness", sweetness);
            tag.putInt("Odor", 0);
        });
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable("item.mcyukkuri.core_filling").withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.FOOD, new FoodProperties.Builder().nutrition(6).saturationModifier(1.2f).build());
        return stack;
    }
    public static final String CORE = "core";
    /** New core items and old leaked-filling stacks with {@code Composition=core} stay valid. */
    public static boolean isCore(ItemStack stack) {
        return stack.is(ModItems.CORE_FILLING.get())
                || stack.is(ModItems.FILLING.get()) && CORE.equals(data(stack).getString("Composition"));
    }

    /** Only the centre filling remains edible. Ordinary leaked filling is a legacy placeholder. */
    public static boolean isFilling(ItemStack stack) { return isCore(stack); }
    public static int nutrition(ItemStack stack) {
        var tag = data(stack);
        return tag.contains("Nutrition") ? Math.clamp(tag.getInt("Nutrition"), 1, 10) : isCore(stack) ? 10 : 0;
    }
    public static int odor(ItemStack stack) { return Math.clamp(data(stack).getInt("Odor"), 0, 100); }

    public static boolean acceptable(YukkuriEntity eater, ItemStack stack) { return isCore(stack); }

    public static void swallowed(YukkuriEntity eater, ItemStack stack) {
        if (odor(stack) >= 40) eater.body().contaminated();
        if (isCore(stack)) eater.body().ateCore(Math.clamp(data(stack).getInt("Sweetness"), 0, 100));
    }
}
