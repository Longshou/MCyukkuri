package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.BodyFood;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Marks one existing food portion as food an adult has prepared for a dependent child. */
public final class InfantFood {
    private static final String MASH = "InfantMash";
    private static final String CAREGIVER = "InfantMashCaregiver";

    private InfantFood() {}

    /** Copy one real portion and retain every component, including filling provenance and nutrition. */
    public static ItemStack mash(ItemStack food, UUID caregiver) {
        if (food.isEmpty() || food.getCount() != 1) return ItemStack.EMPTY;
        ItemStack prepared = food.copy();
        CustomData.update(DataComponents.CUSTOM_DATA, prepared, data -> {
            data.putBoolean(MASH, true);
            if (caregiver != null) data.putUUID(CAREGIVER, caregiver);
        });
        return prepared;
    }

    /** One real bite portioned from world remains. Chewing changes its form and retains its food value. */
    public static ItemStack preyPortion(BodyFood.Bite bite) {
        var serving = new ItemStack(ModItems.FOOD_SCRAP.get());
        CustomData.update(DataComponents.CUSTOM_DATA, serving, tag -> tag.put("PreyBite", bite.save()));
        return serving;
    }

    public static BodyFood.Bite preyBite(ItemStack food) {
        if (!food.is(ModItems.FOOD_SCRAP.get())) return BodyFood.Bite.EMPTY;
        return BodyFood.Bite.load(food.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound("PreyBite"));
    }

    public static boolean isMash(ItemStack food) {
        return !food.isEmpty() && food.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(MASH);
    }
}
