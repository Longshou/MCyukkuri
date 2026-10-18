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
            tag.putFloat("SourceScale",source.growthScale()*source.getScale()*(source.profile().modelScale()));
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

    /**
     * The centre filling taken from remains whose core was never hurt (docs/INJURIES_AND_REMAINS.md, "中枢馅"): the darkest,
     * sweetest part of the body (komicolle 11518802). Same item as a leaked portion, with its own composition, name and food value.
     */
    public static ItemStack core(CompoundTag remains) {
        ItemStack stack = new ItemStack(ModItems.FILLING.get());
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
        stack.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.translatable("item.mcyukkuri.core_filling").withStyle(s -> s.withItalic(false)));
        stack.set(DataComponents.FOOD, new net.minecraft.world.food.FoodProperties.Builder().nutrition(6).saturationModifier(1.2f).build());
        return stack;
    }
    /**
     * One bite of the filling left in remains. A corpse always carries the death stench yukkuri smell (Odor 30 even when fresh: only
     * overcome by extreme hunger, never a sweet); filling from a body that was offered by a parent (social/SelfOffering) is sweetened
     * by the feeling and has no stench. Stale filling reeks (Odor 60, Taste "stale": it infects and unsettles) and rotten filling is foul (Odor 90, Taste "foul": the eater
     * is badly upset). "陳餡" is what the lecture calls it. Numbers are project design.
     */
    public static ItemStack carrion(CompoundTag remains, int stage) {
        ItemStack stack = new ItemStack(ModItems.FILLING.get());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putInt("FillingVersion", 1);
            tag.putUUID("Portion", UUID.randomUUID());
            if (remains.hasUUID("Identity")) tag.putUUID("Source", remains.getUUID("Identity"));
            tag.putString("Variant", remains.getString("Variant"));
            tag.putFloat("SourceScale", remains.contains("SourceScale") ? remains.getFloat("SourceScale") : 1);
            tag.putString("Composition", "bean_paste");
            tag.putString("Method", "carrion");
            boolean offered = RemainsRot.offered(remains);
            tag.putInt("Nutrition", offered ? 6 : stage == 0 ? 5 : stage == 1 ? 4 : 3);
            tag.putInt("Sweetness", offered ? 90 : stage == 0 ? 40 : stage == 1 ? 30 : 5);
            tag.putInt("Odor", offered ? 0 : stage == 0 ? 30 : stage == 1 ? 60 : 90);
            if (stage == 1) tag.putString("Taste", "stale");
            if (stage >= 2) tag.putString("Taste", "foul");
        });
        return stack;
    }
    public static final String CORE = "core";
    public static boolean isCore(ItemStack stack) { return isFilling(stack) && CORE.equals(data(stack).getString("Composition")); }

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
        return odor(stack) < 40 && (eater.profile().dangerToYukkuri() || !eater.body().knowsSource(stack));
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
        if (isCore(stack)) eater.body().ateCore(Math.clamp(data(stack).getInt("Sweetness"), 0, 100));
    }
}
