package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.UUID;

/** A detached portion keeps its composition and yield independently of its former body. */
public final class FillingResource {
    private FillingResource() {}

    public static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static ItemStack portion(YukkuriEntity source, String method) {
        ItemStack stack = new ItemStack(ModItems.FILLING.get());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt("FillingVersion", 1);
            tag.putUUID("Portion", UUID.randomUUID());
            tag.putUUID("Source", source.getUUID());
            tag.putString("Variant", source.getVariant().assetId());
            tag.putFloat("SourceScale",source.growthScale()*source.getScale()*(source.getVariant().isHouse()?.32f:1));
            tag.putString("Composition", "bean_paste");
            tag.putString("Method", method);
            // Unease sweetens the filling; a broken (non-yukkuri) mind leaves it foul (docs/EMOTION_CHAIN.md).
            int sweetness = source.mind().fillingSweetness();
            tag.putInt("Nutrition", source.mind().foulFilling() ? 3 : sweetness >= 85 ? 6 : 5);
            tag.putInt("Sweetness", sweetness);
            if (source.mind().foulFilling()) tag.putString("Taste", "foul");
            // Wounds and vomit do not make fresh filling smell rotten.
            tag.putInt("Odor", 0);
        });
        return stack;
    }

    public static boolean isFilling(ItemStack stack) { return stack.is(ModItems.FILLING.get()); }
    public static int nutrition(ItemStack stack) {
        var tag = data(stack);
        return tag.contains("Nutrition") ? Math.clamp(tag.getInt("Nutrition"), 1, 10) : 5;
    }
    public static int odor(ItemStack stack) { return Math.clamp(data(stack).getInt("Odor"), 0, 100); }

    public static boolean acceptable(YukkuriEntity eater, ItemStack stack) {
        if (!isFilling(stack)) return false;
        if (eater.life().nutrition() < 15) return true;
        if ("foul".equals(data(stack).getString("Taste"))) return false;
        return odor(stack) < 40 && (eater.getVariant().isPredator() || !eater.body().knowsSource(stack));
    }

    /** Only actual local observers receive source knowledge; the item tag is not telepathy. */
    public static void witnessed(YukkuriEntity source, ItemStack stack) {
        if (!(source.level() instanceof ServerLevel level)) return;
        source.body().learnSource(stack);
        for (var witness : level.getEntitiesOfClass(YukkuriEntity.class, source.getBoundingBox().inflate(8),
                other -> other != source && other.isAlive() && !other.life().isSleeping()
                        && other.distanceToSqr(source) <= 64 && other.hasLineOfSight(source))) {
            witness.body().learnSource(stack);
            witness.showReaction("surprise", "wounded", 60);
        }
    }

    public static void swallowed(YukkuriEntity eater, ItemStack stack) {
        if (odor(stack) >= 40) eater.body().contaminated();
    }
}
