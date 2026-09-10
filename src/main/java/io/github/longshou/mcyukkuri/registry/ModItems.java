package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCYukkuri.MOD_ID);

    // Registration probe and future field guide. No reading UI is implemented yet.
    public static final DeferredItem<Item> FIELD_NOTEBOOK = ITEMS.registerSimpleItem(
            "field_notebook", new Item.Properties().stacksTo(1));

    private ModItems() {}
}
