package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Pure operations on the pile's existing slots. The block entity owns persistence and change notifications. */
final class StoreInventory {
    static int food(NonNullList<ItemStack> stock) { int total = 0; for (var stack : stock) total += stack.getCount(); return total; }
    static int capacity(NonNullList<ItemStack> stock) {
        int total = 0;
        for (var stack : stock) total += stack.isEmpty() ? Items.SHORT_GRASS.getDefaultInstance().getMaxStackSize() : stack.getMaxStackSize();
        return total;
    }
    static int foodSpace(NonNullList<ItemStack> stock, ItemStack incoming) {
        if (incoming.isEmpty() || !incoming.is(YukkuriEntity.FOOD)) return 0;
        int space = 0;
        for (var stored : stock) {
            if (stored.isEmpty()) space += incoming.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(stored, incoming)) space += Math.max(0, stored.getMaxStackSize() - stored.getCount());
        }
        return space;
    }
    static ItemStack returnFood(NonNullList<ItemStack> stock, ItemStack stack) {
        var remaining = stack.copy();
        if (remaining.isEmpty() || !remaining.is(YukkuriEntity.FOOD)) return remaining;
        // Merge compatible stacks first, so components do not needlessly consume all nine slots.
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < stock.size() && !remaining.isEmpty(); i++) {
            var stored = stock.get(i);
            if (pass == 0 && (stored.isEmpty() || !ItemStack.isSameItemSameComponents(stored, remaining))) continue;
            if (pass == 1 && !stored.isEmpty()) continue;
            int room = stored.isEmpty() ? remaining.getMaxStackSize() : Math.max(0, stored.getMaxStackSize() - stored.getCount());
            int moved = Math.min(room, remaining.getCount());
            if (moved == 0) continue;
            if (stored.isEmpty()) stock.set(i, remaining.copyWithCount(moved)); else stored.grow(moved);
            remaining.shrink(moved);
        }
        return remaining;
    }
    static ItemStack peek(NonNullList<ItemStack> stock, Predicate<ItemStack> wanted) {
        for (var stack : stock) if (!stack.isEmpty() && wanted.test(stack)) return stack.copyWithCount(1);
        return ItemStack.EMPTY;
    }
    static ItemStack take(NonNullList<ItemStack> stock, Predicate<ItemStack> wanted) {
        for (var stack : stock) if (!stack.isEmpty() && wanted.test(stack)) { var item = stack.split(1); return item; }
        return ItemStack.EMPTY;
    }
    static List<ItemStack> drain(NonNullList<ItemStack> stock) {
        var result = new ArrayList<ItemStack>();
        for (int i = 0; i < stock.size(); i++) if (!stock.get(i).isEmpty()) { result.add(stock.get(i)); stock.set(i, ItemStack.EMPTY); }
        return result;
    }
    private StoreInventory() {}
}
