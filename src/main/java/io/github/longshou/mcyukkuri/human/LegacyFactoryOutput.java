package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** The only surviving part of an old worker's production tag is its physical inventory. */
final class LegacyFactoryOutput {
    private static final String INVENTORY = "FactoryProduction";
    private final List<ItemStack> output = new ArrayList<>();

    void load(CompoundTag tag, HolderLookup.Provider registries) {
        output.clear();
        for (Tag saved : tag.getCompound(INVENTORY).getList("Output", Tag.TAG_COMPOUND)) {
            var stack = ItemStack.parseOptional(registries, (CompoundTag) saved);
            if (!stack.isEmpty()) output.add(stack);
        }
    }

    void save(CompoundTag tag, HolderLookup.Provider registries) {
        if (output.isEmpty()) return;
        var list = new ListTag();
        for (var stack : output) list.add(stack.save(registries));
        var inventory = new CompoundTag();
        inventory.put("Output", list);
        tag.put(INVENTORY, inventory);
    }

    void collect(HumanNpc worker) {
        if (output.isEmpty()) return;
        var level = worker.level();
        // The nearest authored storage is up to 13 blocks from the old post.
        var store = BlockPos.findClosestMatch(worker.home(), 13, 1, pos -> level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof BarrelBlockEntity barrel && barrel.hasAnyMatching(ItemStack::isEmpty));
        if (store.isPresent()) {
            var barrel = (BarrelBlockEntity) level.getBlockEntity(store.get());
            int moved = 0;
            for (int slot = 0; slot < barrel.getContainerSize() && !output.isEmpty(); slot++) {
                if (!barrel.getItem(slot).isEmpty()) continue;
                barrel.setItem(slot, output.getFirst());
                output.removeFirst();
                moved++;
            }
            GameplayLog.event(worker, "factory", "action=legacy_output store={} stacks={} remaining={}",
                    store.get().toShortString(), moved, output.size());
        }
        // A dismantled or full store leaves real pickups where the worker stands.
        var remaining = output.iterator();
        while (remaining.hasNext()) {
            var stack = remaining.next();
            var pickup = new ItemEntity(level, worker.getX(), worker.getY(), worker.getZ(), stack);
            pickup.setDefaultPickUpDelay();
            pickup.setUnlimitedLifetime();
            if (stack.is(HumanWorld.LIVE_BAG.get())) pickup.setInvulnerable(true);
            if (!level.addFreshEntity(pickup)) continue;
            remaining.remove();
            GameplayLog.event(worker, "factory", "action=legacy_output pickup={} item={} count={}",
                    pickup.getUUID(), stack.getItem(), stack.getCount());
        }
    }
}
