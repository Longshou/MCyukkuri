package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The shopkeeper's goods (docs/PET_SHOP.md section 6.3 and section 12). Order and ids are stable: shelf stock is saved by id, pages are built per
 * category. Each good has a price per unit (in emeralds), a shelf capacity, a daily restock and an optional badge requirement
 * (0 none, 1 bronze, 2 silver) checked against {@code PetPrivileges}. Pure data, safe on both sides.
 */
public final class ShopCatalog {
    public static final int CATEGORIES = 5;
    /** unlock: 0 free, 1 needs a bronze badge in the owner's keeping, 2 needs silver. */
    public record Good(String id, int category, Supplier<ItemStack> supplier, int price, int cap, int restock, int unlock) {
        public ItemStack stack() { return supplier.get(); }
    }
    private static Good good(String id, int category, Supplier<ItemStack> supplier, int price, int cap, int restock, int unlock) { return new Good(id,
            category, supplier, price, cap, restock, unlock); }

    /** Categories: 0 food and sweets, 1 care, 2 toys and accessories, 3 training and outings, 4 breeding and nursery. */
    public static final List<Good> GOODS = List.of(
        good("oil_grain_regular", 0, () -> new ItemStack(ModItems.GRAIN_REGULAR.get(), 8), 1, 8, 4, 0),
        good("oil_grain_budget", 0, () -> new ItemStack(ModItems.GRAIN_BUDGET.get(), 16), 2, 6, 3, 0),
        good("oil_grain_premium", 0, () -> new ItemStack(ModItems.GRAIN_PREMIUM.get(), 8), 3, 3, 1, 2),
        good("orange_juice", 0, () -> new ItemStack(ModItems.ORANGE_JUICE.get()), 2, 4, 2, 0),
        good("carrier", 1, () -> new ItemStack(ModItems.CARRIER.get()), 3, 3, 1, 0),
        good("wash_cloth", 1, () -> new ItemStack(ModItems.WASH_CLOTH.get()), 2, 4, 2, 0),
        good("food_bowl", 1, () -> new ItemStack(ModItems.FOOD_BOWL.get()), 3, 4, 2, 0),
        good("yukkuri_nest", 1, () -> new ItemStack(ModItems.NEST.get()), 4, 4, 2, 0),
        good("yukkuri_toilet", 1, () -> new ItemStack(ModItems.TOILET.get()), 3, 4, 2, 0),
        good("mold_remover", 1, () -> new ItemStack(ModItems.MOLD_REMOVER.get()), 3, 4, 2, 0),
        good("yarn_ball", 2, () -> new ItemStack(ModItems.TOY.get()), 2, 4, 2, 0),
        good("reimu_ribbon", 2, () -> new ItemStack(ModItems.RIBBON.get()), 4, 2, 1, 0),
        good("marisa_hat", 2, () -> new ItemStack(ModItems.HAT.get()), 4, 2, 1, 0),
        good("chen_hat", 2, () -> new ItemStack(ModItems.CHEN_HAT.get()), 4, 2, 1, 0),
        good("alice_headband", 2, () -> new ItemStack(ModItems.ALICE_HEADBAND.get()), 4, 2, 1, 0),
        good("patchouli_bonnet", 2, () -> new ItemStack(ModItems.PATCHOULI_BONNET.get()), 4, 2, 1, 0),
        good("teaching_whistle", 3, () -> new ItemStack(ModItems.WHISTLE.get()), 4, 2, 1, 0),
        good("companion_cord", 4, () -> new ItemStack(ModItems.COMPANION_CORD.get()), 4, 2, 1, 0),
        // added with the closed care loop: sweets, harness and swaddle, and the soft hats of the predator species (a lost hat could never be replaced)
        good("sweet_candy", 0, () -> new ItemStack(ModItems.SWEET_CANDY.get(), 6), 1, 8, 4, 0),
        good("cookie", 0, () -> new ItemStack(Items.COOKIE, 4), 2, 6, 3, 0),
        good("custard_pudding", 0, () -> new ItemStack(ModItems.CUSTARD_PUDDING.get()), 3, 3, 1, 2),
        good("remilia_bonnet", 2, () -> new ItemStack(ModItems.REMILIA_BONNET.get()), 4, 2, 1, 0),
        good("flandre_bonnet", 2, () -> new ItemStack(ModItems.FLANDRE_BONNET.get()), 4, 2, 1, 0),
        good("pet_harness", 3, () -> new ItemStack(ModItems.PET_HARNESS.get()), 5, 3, 1, 1),
        good("swaddle_cloth", 4, () -> new ItemStack(ModItems.SWADDLE_CLOTH.get()), 3, 3, 1, 0));

    /** Each page: category followed by up to four catalog indexes. */
    private static final List<int[]> PAGES = new ArrayList<>();
    static {
        for (int c = 0; c < CATEGORIES; c++) {
            List<Integer> in = new ArrayList<>();
            for (int i = 0; i < GOODS.size(); i++) if (GOODS.get(i).category() == c) in.add(i);
            for (int s = 0; s < in.size(); s += 4) {
                int n = Math.min(4, in.size() - s); int[] page = new int[n + 1]; page[0] = c;
                for (int k = 0; k < n; k++) page[k + 1] = in.get(s + k);
                PAGES.add(page);
            }
        }
    }
    private ShopCatalog() {}

    public static int pages() { return PAGES.size(); }
    private static int[] page(int page) { return PAGES.get(Math.floorMod(page, PAGES.size())); }
    public static int category(int page) { return page(page)[0]; }
    /** Catalog index of the good in this row of the page, or -1 for an empty row. */
    public static int index(int page, int row) { var p = page(page); return row >= 0 && row + 1 < p.length ? p[row + 1] : -1; }
    public static Good at(int index) { return index >= 0 && index < GOODS.size() ? GOODS.get(index) : null; }
    public static int indexOf(String id) {
        for (int i = 0; i < GOODS.size(); i++) if (GOODS.get(i).id().equals(id)) return i;
        return -1;
    }
}
