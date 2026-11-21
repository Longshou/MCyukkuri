package io.github.longshou.mcyukkuri.human;

import java.util.List;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The two trades a keeper makes over a counter run: the player buys one good for one emerald, or sells a batch of goods for one emerald
 * out of the till. The till is the emeralds lying in the run's crates, the shelf its goods rows, so nothing is created or lost: what
 * the player hands over lands in the crates, what the player gets leaves them. Every destination is chosen before anything moves.
 */
final class CounterTrade {
    enum Result { DONE, EMPTY, TILL_FULL, TILL_EMPTY, SHELF_FULL, TOO_FEW, INVENTORY_FULL }

    private CounterTrade() {}

    static int till(List<ProduceCrateBlockEntity> run) {
        int sum = 0;
        for (var crate : run) sum += crate.emeralds();
        return sum;
    }

    /** The buyer's held emerald pays for the front good of the crate used (the first of {@code run}). */
    static Result buy(List<ProduceCrateBlockEntity> run, Player buyer, ItemStack emerald) {
        var clicked = run.get(0);
        int offer = clicked.saleRow();
        if (offer < 0) return Result.EMPTY;
        ProduceCrateBlockEntity tillCrate = null;
        int tillRow = -1;
        for (var crate : run) {
            tillRow = crate.insertionRow(emerald, crate == clicked ? offer : -1);
            if (tillRow >= 0) { tillCrate = crate; break; }
        }
        if (tillCrate == null) return Result.TILL_FULL;
        var goods = clicked.getItem(offer);
        var inventory = buyer.getInventory();
        int slot = inventory.getSlotWithRemainingSpace(goods);
        if (slot < 0) slot = inventory.getFreeSlot();
        if (slot < 0 && emerald.getCount() == 1 && emerald == buyer.getMainHandItem()) slot = inventory.selected;
        if (slot < 0) return Result.INVENTORY_FULL;

        var delivered = clicked.takeFrom(offer);
        tillCrate.insert(tillRow, emerald);
        emerald.shrink(1);
        var held = inventory.getItem(slot);
        if (held.isEmpty()) inventory.setItem(slot, delivered);
        else held.grow(1);
        inventory.setChanged();
        return Result.DONE;
    }

    /** The seller's held goods go onto the shelves of the run, one emerald of the till goes to the seller. One batch per trade. */
    static Result buyBack(List<ProduceCrateBlockEntity> run, Player seller, ItemStack goods) {
        int batch = ProduceCrateBlockEntity.batch(goods);
        if (goods.getCount() < batch) return Result.TOO_FEW;
        ProduceCrateBlockEntity tillCrate = null;
        for (var crate : run) if (crate.emeraldRow() >= 0) { tillCrate = crate; break; }
        if (tillCrate == null) return Result.TILL_EMPTY;
        int tillRow = tillCrate.emeraldRow();
        // The emerald leaves first and may empty its row, which then takes goods.
        int room = 0;
        for (var crate : run) room += crate.room(goods, crate == tillCrate ? tillRow : -1);
        if (room < batch) return Result.SHELF_FULL;
        var pay = new ItemStack(Items.EMERALD);
        var inventory = seller.getInventory();
        int slot = inventory.getSlotWithRemainingSpace(pay);
        if (slot < 0) slot = inventory.getFreeSlot();
        if (slot < 0 && goods.getCount() == batch && goods == seller.getMainHandItem()) slot = inventory.selected;
        if (slot < 0) return Result.INVENTORY_FULL;

        tillCrate.takeFrom(tillRow);
        var sold = goods.copyWithCount(1);
        int left = batch;
        // The crate the player used first, then the rest of the run.
        for (var crate : run) left -= crate.putUpTo(sold, left);
        goods.shrink(batch);
        var held = inventory.getItem(slot);
        if (held.isEmpty()) inventory.setItem(slot, pay);
        else held.grow(1);
        inventory.setChanged();
        return Result.DONE;
    }
}
