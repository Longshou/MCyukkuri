package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCYukkuri.MOD_ID);

    // Registration probe and future field guide. No reading UI is implemented yet.
    public static final DeferredItem<Item> FIELD_NOTEBOOK = ITEMS.registerSimpleItem(
            "field_notebook", new Item.Properties().stacksTo(1));

    public static final DeferredItem<DeferredSpawnEggItem> REIMU_SPAWN_EGG = ITEMS.register(
            "reimu_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.REIMU, 0xF1D5AA, 0xB52E36, new Item.Properties()));
    public static final DeferredItem<DeferredSpawnEggItem> MARISA_SPAWN_EGG = ITEMS.register(
            "marisa_spawn_egg", () -> new DeferredSpawnEggItem(
                    ModEntities.MARISA, 0xF1D5AA, 0x342D38, new Item.Properties()));

    private ModItems() {}
}
