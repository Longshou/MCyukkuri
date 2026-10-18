package io.github.longshou.mcyukkuri.freezer;

import io.github.longshou.mcyukkuri.registry.ModCreativeTabs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

public final class FreezerModule {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    public static final DeferredBlock<FreezerBlock> CABINET = BLOCKS.register("cold_storage", () ->
            new FreezerBlock(BlockBehaviour.Properties.of().strength(3.5f).sound(SoundType.METAL).noOcclusion()));
    public static final DeferredItem<BlockItem> ITEM = ITEMS.register("cold_storage", () ->
            new BlockItem(CABINET.get(), new Item.Properties()));
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
    public static void creative(BuildCreativeModeTabContentsEvent event) {
        ModCreativeTabs.addFreezerContents(event);
    }
    private FreezerModule() {}
}
