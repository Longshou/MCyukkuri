package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** The mark travels with the serving through hands, mouths, containers and drops. */
public final class PicnicFood {
    static boolean apple(ItemStack stack) { return stack.is(Items.APPLE) || stack.is(ModItems.APPLE_HALF.get()); }

    static ItemStack mark(ItemStack stack) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("Picnic", true));
        return stack;
    }

    static ItemStack serving(ItemStack stack, UUID id) {
        CustomData.update(DataComponents.CUSTOM_DATA, mark(stack), tag -> tag.putUUID("PicnicServing", id));
        return stack;
    }

    static ItemStack collected(ItemStack stack) {
        var half = stack.copyWithCount(1);
        CustomData.update(DataComponents.CUSTOM_DATA, half, tag -> tag.remove("PicnicServing"));
        return half;
    }

    public static ItemStack split(ItemStack apple) {
        var half = new ItemStack(ModItems.APPLE_HALF.get());
        return marked(apple) ? mark(half) : half;
    }

    static boolean matches(ItemStack stack, UUID id) {
        if (!stack.is(ModItems.APPLE_HALF.get())) return false;
        var tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.hasUUID("PicnicServing") && id.equals(tag.getUUID("PicnicServing"));
    }

    public static void joining(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof ItemEntity item && apple(item.getItem())
                && marked(item.getItem())) {
            item.setUnlimitedLifetime();
        }
    }

    private static boolean marked(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean("Picnic");
    }

    private PicnicFood() {}
}
