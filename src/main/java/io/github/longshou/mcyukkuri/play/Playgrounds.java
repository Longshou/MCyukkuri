package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.MCYukkuri;
import net.minecraft.core.registries.Registries;
import java.util.EnumMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;

/** Module-owned registration; only full facilities have items. */
public final class Playgrounds {
    private static final DeferredRegister.Blocks BLOCK_TYPES = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    private static final DeferredRegister.Items ITEM_TYPES = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MCYukkuri.MOD_ID);
    public static final EnumMap<PlayBlock.Kind,DeferredBlock<PlayBlock>> BLOCKS=new EnumMap<>(PlayBlock.Kind.class);
    public static final EnumMap<PlayBlock.Kind,DeferredItem<PlayItem>> ITEMS=new EnumMap<>(PlayBlock.Kind.class);
    static {
        for(var kind:PlayBlock.Kind.values()){
            BLOCKS.put(kind,BLOCK_TYPES.register(kind.id,()->new PlayBlock(kind,BlockBehaviour.Properties.of()
                    .strength(.8f).sound(kind==PlayBlock.Kind.PAPER?SoundType.WOOL:SoundType.METAL).noOcclusion())));
            ITEMS.put(kind,ITEM_TYPES.register(kind.id,()->new PlayItem(kind,new Item.Properties().stacksTo(16))));
        }
    }
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<PlayBlockEntity>> ENTITY=BLOCK_ENTITY_TYPES.register("playground",
            ()->BlockEntityType.Builder.of(PlayBlockEntity::new,BLOCKS.values().stream().map(DeferredBlock::get).toArray(PlayBlock[]::new)).build(null));
    public static void register(IEventBus bus) {
        BLOCK_TYPES.register(bus);
        ITEM_TYPES.register(bus);
        BLOCK_ENTITY_TYPES.register(bus);
    }
    private Playgrounds(){}
}
