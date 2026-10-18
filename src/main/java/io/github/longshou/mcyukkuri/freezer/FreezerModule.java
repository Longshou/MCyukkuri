package io.github.longshou.mcyukkuri.freezer;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

public final class FreezerModule {
    public static final DeferredBlock<FreezerBlock> CABINET = ModBlocks.BLOCKS.register("cold_storage", () ->
            new FreezerBlock(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ModItems.ITEMS.register("cold_storage", () ->
            new BlockItem(CABINET.get(), new Item.Properties()));
    public static void init() {}
    public static void creative(BuildCreativeModeTabContentsEvent event) {
        ModCreativeTabs.addFreezerContents(event);
    }
    private FreezerModule() {}
}
