package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.item.OperationToolItem;
import io.github.longshou.mcyukkuri.life.Consumables;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The shopkeeper's goods (docs/FEATURES.md). Order and ids are stable: shelf stock is saved by id, pages are built per
 * category. Each good has a price per unit (in emeralds), a shelf capacity, a daily restock and an optional badge requirement
 * (0 none, 1 bronze, 2 silver) checked against {@code PetPrivileges}. Pure data, safe on both sides.
 */
public final class ShopCatalog {
    private static final int TOOL_CATEGORY = 5;
    /** unlock: 0 free, 1 needs a bronze badge in the owner's keeping, 2 needs silver. */
    public record Good(String id, int category, Supplier<ItemStack> supplier, int price, int cap, int restock, int unlock) {
        public ItemStack stack() { return supplier.get(); }
    }
    private static Good good(String id, int category, Supplier<ItemStack> supplier, int price, int cap, int restock, int unlock) { return new Good(id,
            category, supplier, price, cap, restock, unlock); }
    private static final Good BUDGET_GRAIN = good("oil_grain_budget", 0, () -> new ItemStack(ModItems.GRAIN_BUDGET.get(), 16), 2, 6, 3, 0);

    /** Categories: 0 food and sweets, 1 care, 2 toys and accessories, 3 training and outings, 4 breeding and nursery. */
    private static final List<Good> GOODS = List.of(
        good("oil_grain_regular", 0, () -> new ItemStack(ModItems.GRAIN_REGULAR.get(), 8), 1, 8, 4, 0),
        BUDGET_GRAIN,
        good("oil_grain_premium", 0, () -> new ItemStack(ModItems.GRAIN_PREMIUM.get(), 8), 3, 3, 1, 2),
        good("orange_juice", 0, () -> new ItemStack(ModItems.ORANGE_JUICE.get()), 2, 4, 2, 0),
        good("carrier", 1, () -> new ItemStack(ModItems.CARRIER.get()), 3, 3, 1, 0),
        good("wash_cloth", 1, () -> new ItemStack(ModItems.WASH_CLOTH.get()), 2, 4, 2, 0),
        good("food_bowl", 1, () -> new ItemStack(ModItems.FOOD_BOWL.get()), 3, 4, 2, 0),
        // The three bed grades (docs/FEATURES.md): straw for every purse, the cushion, the velvet bed for a bronze-badged keeper.
        good("straw_nest", 1, () -> new ItemStack(ModItems.STRAW_NEST.get()), 2, 4, 2, 0),
        good("yukkuri_nest", 1, () -> new ItemStack(ModItems.NEST.get()), 4, 4, 2, 0),
        good("velvet_nest", 1, () -> new ItemStack(ModItems.VELVET_NEST.get()), 9, 2, 1, 1),
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
        good("swaddle_cloth", 4, () -> new ItemStack(ModItems.SWADDLE_CLOTH.get()), 3, 3, 1, 0),
        // The other real toys (life/Facilities): cheap rolling marbles up to the plush dolls, which need a bronze badge in the buyer's keeping.
        good("glass_marble", 2, () -> new ItemStack(Facilities.ITEMS.get("glass_marble").get()), 1, 6, 3, 0),
        good("pretty_stone", 2, () -> new ItemStack(Facilities.ITEMS.get("pretty_stone").get()), 2, 4, 2, 0),
        good("reimu_doll", 2, () -> new ItemStack(Facilities.ITEMS.get("reimu_doll").get()), 5, 2, 1, 1),
        good("marisa_doll", 2, () -> new ItemStack(Facilities.ITEMS.get("marisa_doll").get()), 5, 2, 1, 1));

    private static final List<Good> ONI_GOODS = oniGoods();
    private static final List<List<Good>> PET_PAGES = pages(GOODS);
    private static final List<List<Good>> ONI_PAGES = pages(ONI_GOODS);

    private static List<Good> oniGoods() {
        List<Good> goods = new ArrayList<>();
        for (var tool : OperationToolItem.Tool.values())
            goods.add(good(tool.id, TOOL_CATEGORY, () -> new ItemStack(tool.item()), 4, 3, 1, 0));
        // Every cut (hair, accessory, stem, sterilizing) is made with vanilla shears.
        goods.add(good("shears", TOOL_CATEGORY, () -> new ItemStack(Items.SHEARS), 2, 3, 1, 0));
        for (var dose : List.of(Consumables.Kind.SPICY, Consumables.Kind.RAMUNE, Consumables.Kind.STIMULANT))
            goods.add(good(dose.id, 1, () -> new ItemStack(Consumables.ITEMS.get(dose).get()), 2, 6, 3, 0));
        goods.addAll(List.of(BUDGET_GRAIN,
                good("bread", 0, () -> new ItemStack(Items.BREAD, 4), 1, 6, 3, 0),
                good("candle", 0, () -> new ItemStack(Items.CANDLE, 4), 1, 6, 3, 0),
                good("paper", 0, () -> new ItemStack(Items.PAPER, 4), 1, 6, 3, 0)));
        return List.copyOf(goods);
    }

    private static List<List<Good>> pages(List<Good> goods) {
        List<List<Good>> result = new ArrayList<>();
        for (int category = 0; category <= TOOL_CATEGORY; category++) {
            int selected = category;
            var rows = goods.stream().filter(g -> g.category() == selected).toList();
            for (int start = 0; start < rows.size(); start += ShopMenu.GOODS_ROWS)
                result.add(rows.subList(start, Math.min(rows.size(), start + ShopMenu.GOODS_ROWS)));
        }
        return List.copyOf(result);
    }

    static List<List<Good>> pages(ShopKind kind) { return kind == ShopKind.ONI ? ONI_PAGES : PET_PAGES; }
    static List<Good> goods(ShopKind kind) { return kind == ShopKind.ONI ? ONI_GOODS : GOODS; }
    static Good find(ShopKind kind, String id) {
        return goods(kind).stream().filter(g -> g.id().equals(id)).findFirst().orElse(null);
    }

    private ShopCatalog() {}
}
