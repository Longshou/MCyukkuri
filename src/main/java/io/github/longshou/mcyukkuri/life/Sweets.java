package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Sweets are an extra: a treat on top of meals, never a meal replacement (docs/PET_SHOP.md section 12).
 * The tag {@code mcyukkuri:yukkuri_sweets} (sweet candy, cookie, custard pudding) is part of {@code yukkuri_food}; the numbers are project-original.
 * A sweet is accepted up to nutrition 95 ("second stomach") while ordinary food is only taken at 75 or less.
 * The effects below are applied once, when the serving is swallowed, and only for an owner's pet fed by its own owner.
 */
public final class Sweets {
    public static final TagKey<Item> TAG = TagKey.create(Registries.ITEM, MCYukkuri.id("yukkuri_sweets"));
    /** Highest nutrition at which a sweet is still taken. */
    public static final float FULL = 95;
    /** Sweets per pet and game day that count as a treat; the next one is greed. */
    public static final int DAILY_FREE = 3;

    private Sweets() {}

    public static boolean is(ItemStack stack) { return !stack.isEmpty() && stack.is(TAG); }
    public static boolean pudding(ItemStack stack) { return stack.is(ModItems.CUSTARD_PUDDING.get()); }
    public static boolean candy(ItemStack stack) { return stack.is(ModItems.SWEET_CANDY.get()); }

    /** Nutrition gained from one sweet (the cookie keeps roughly the generic food formula of 2 x 5). */
    public static float nutrition(ItemStack stack) { return candy(stack) ? 6 : pudding(stack) ? 15 : 10; }
    /** Thirst added by one sweet. */
    public static int dryness(ItemStack stack) { return candy(stack) ? 10 : pudding(stack) ? 0 : 15; }
    /** Multiplier of the lesson reward gain: pudding is a proper prize. */
    public static float rewardFactor(ItemStack stack) { return pudding(stack) ? 1.5f : 1f; }

    /** Premium grain is the pet shop's better grade: it never leaves the pet thirsty, calms it a little and restores health. */
    public static boolean premium(ItemStack stack) { return stack.is(ModItems.GRAIN_PREMIUM.get()); }

    /**
     * Called at the server step that grants the nutrition. Returns the speech event to use instead of "fed" (null keeps "fed").
     */
    public static String eaten(YukkuriEntity y, ItemStack food, UUID feeder) {
        var pet = y.pet();
        if (premium(food)) {
            if (pet.isPet()) pet.addStress(-2);
            y.heal(2);
            return null;
        }
        if (!is(food) || !pet.isPet() || !pet.ownedBy(feeder)) return null;
        var care = pet.care();
        int count = care.countSnack();
        int stress = pudding(food) ? 12 : candy(food) ? 4 : 6, bored = pudding(food) ? 20 : candy(food) ? 8 : 10;
        boolean greedy = count > DAILY_FREE;
        pet.addStress(-stress);
        y.dailyLife().entertained(bored);
        care.snackEaten();
        return greedy ? "snack_greedy" : "snack_eat";
    }
}
