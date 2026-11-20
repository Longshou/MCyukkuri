package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

final class ShopBeddingDock {
    private final Container barrel;
    private final List<DockBedding.Slot> stock = new ArrayList<>();
    private final ItemStack cushion = new ItemStack(ModItems.NEST.get()), woven = new ItemStack(ModItems.LARGE_NEST.get());

    ShopBeddingDock(Container barrel) {
        this.barrel = barrel;
        for (int i = 0; i < barrel.getContainerSize(); i++) {
            var stack = barrel.getItem(i);
            var kind = stack.isEmpty() ? DockBedding.Kind.EMPTY
                    : ItemStack.isSameItemSameComponents(stack, cushion) ? DockBedding.Kind.CUSHION
                    : ItemStack.isSameItemSameComponents(stack, woven) ? DockBedding.Kind.WOVEN
                    : ShopStaff.openDockSlot(stack) ? DockBedding.Kind.CARRIER : DockBedding.Kind.OTHER;
            stock.add(new DockBedding.Slot(kind, stack.getCount()));
        }
    }
    static int cushions(BlockState state) { return state.is(ModBlocks.NEST.get()) ? 1 : 0; }
    static int woven(BlockState state) {
        return state.is(ModBlocks.LARGE_NEST.get()) && state.getValue(LargeNestBlock.PART) == 0 ? 1 : 0;
    }
    DockBedding.Exchange plan(int cushions, int wovenBeds, int basketPlaces, DockBedding.Supply supply) {
        return DockBedding.plan(stock, cushions, wovenBeds, barrel.getMaxStackSize(cushion), barrel.getMaxStackSize(woven), basketPlaces, supply);
    }
    void apply(DockBedding.Exchange exchange) {
        write(stock, exchange.received());
        write(exchange.received(), exchange.after());
    }
    private void write(List<DockBedding.Slot> before, List<DockBedding.Slot> after) {
        for (int i = 0; i < before.size(); i++) {
            var slot = after.get(i);
            if (!slot.equals(before.get(i))) barrel.setItem(i, switch (slot.kind()) {
                case EMPTY -> ItemStack.EMPTY;
                case CUSHION -> cushion.copyWithCount(slot.count());
                case WOVEN -> woven.copyWithCount(slot.count());
                default -> throw new IllegalStateException("Bedding exchange changed other cargo");
            });
        }
    }
}
