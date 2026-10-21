package io.github.longshou.mcyukkuri.body;

import io.github.longshou.mcyukkuri.entity.RemainsEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The one mutable food balance carried by a dead body, whether it lies in the
 * world or is being carried as an item.  It never creates a loose filling
 * stack: callers retain the returned bite until it is swallowed or processed.
 */
public final class CorpseFood {
    public static final String REMAINING = "FoodRemaining";
    public static final String CAPACITY = "FoodCapacity";
    private static final String LEGACY_FILLING = "Filling";

    private CorpseFood() {}

    /** Remaining edible portions. Legacy remains retain exactly their old {@code Filling} value. */
    public static float remaining(CompoundTag remains) {
        if (remains.contains(REMAINING)) return finitePortions(remains.getFloat(REMAINING));
        return finitePortions(remains.getFloat(LEGACY_FILLING));
    }

    /** Immutable body-size reference used only for visual fullness. It never restores old remains. */
    public static float capacity(CompoundTag remains) {
        if (remains.contains(CAPACITY)) return finitePortions(remains.getFloat(CAPACITY));
        return remaining(remains);
    }

    /** Atomically removes up to {@code requested} portions from world remains. */
    public static BodyFood.Bite take(RemainsEntity remains, float requested) {
        if (remains == null || remains.level().isClientSide) return BodyFood.Bite.EMPTY;
        ItemStack stack = remains.remains();
        BodyFood.Bite bite = take(stack, requested);
        if (!bite.isEmpty()) remains.setRemains(stack);
        return bite;
    }

    /** Atomically removes up to {@code requested} portions from carried remains. */
    public static BodyFood.Bite take(ItemStack remains, float requested) {
        if (!isRemains(remains) || !Float.isFinite(requested) || requested <= 0) return BodyFood.Bite.EMPTY;
        CompoundTag data = data(remains);
        float available = remaining(data);
        float portions = Math.min(available, requested);
        if (portions <= 0) return BodyFood.Bite.EMPTY;
        BodyFood.Bite bite = bite(data, portions);
        CustomData.update(DataComponents.CUSTOM_DATA, remains, tag -> {
            float current = remaining(tag);
            tag.putFloat(CAPACITY, Math.max(capacity(tag), current));
            tag.putFloat(REMAINING, Math.max(0, current - portions));
            tag.remove(LEGACY_FILLING);
        });
        return bite;
    }

    /** Factory work is restricted to fresh, ordinary edible remains. */
    public static boolean canProcessInFactory(CompoundTag remains) {
        return RemainsRot.stage(remains) == RemainsRot.FRESH && !remains.getBoolean("FoodFoul") && remaining(remains) >= 1;
    }

    /** Converts a legacy carried corpse once without changing its remaining balance. */
    public static void migrate(ItemStack remains) {
        if (!isRemains(remains)) return;
        CustomData.update(DataComponents.CUSTOM_DATA, remains, tag -> {
            if (tag.contains(REMAINING)) return;
            float legacy = finitePortions(tag.getFloat(LEGACY_FILLING));
            tag.putFloat(REMAINING, legacy);
            tag.putFloat(CAPACITY, legacy);
            tag.remove(LEGACY_FILLING);
        });
    }

    private static boolean isRemains(ItemStack stack) { return !stack.isEmpty() && stack.is(ModItems.REMAINS.get()); }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static BodyFood.Bite bite(CompoundTag remains, float portions) {
        int stage = RemainsRot.stage(remains);
        boolean offered = RemainsRot.offered(remains);
        boolean foul = remains.getBoolean("FoodFoul");
        int sweetness = Math.clamp(remains.getInt("FoodSweetness"), 0, 100);
        if (stage >= RemainsRot.ROTTEN || foul) return new BodyFood.Bite(portions, portions * 30, 90, "foul");
        if (stage == RemainsRot.STALE) return new BodyFood.Bite(portions, portions * 40, 60, "stale");
        float nutrition = offered || sweetness >= 85 ? 60 : 50;
        return new BodyFood.Bite(portions, portions * nutrition, offered ? 0 : 30, "fresh");
    }

    private static float finitePortions(float value) {
        return Float.isFinite(value) ? Math.max(0, Math.min(value, 64)) : 0;
    }
}
