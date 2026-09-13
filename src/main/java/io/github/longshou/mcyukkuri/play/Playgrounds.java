package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.EnumMap;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** Registration shares the existing mod registries; only full facilities have items. */
public final class Playgrounds {
    public static final EnumMap<PlayBlock.Kind,DeferredBlock<PlayBlock>> BLOCKS=new EnumMap<>(PlayBlock.Kind.class);
    public static final EnumMap<PlayBlock.Kind,DeferredItem<PlayItem>> ITEMS=new EnumMap<>(PlayBlock.Kind.class);
    static {
        for(var kind:PlayBlock.Kind.values()){
            BLOCKS.put(kind,ModBlocks.BLOCKS.register(kind.id,()->new PlayBlock(kind,BlockBehaviour.Properties.of()
                    .strength(.8f).sound(kind==PlayBlock.Kind.PAPER?SoundType.WOOL:SoundType.METAL).noOcclusion())));
            ITEMS.put(kind,ModItems.ITEMS.register(kind.id,()->new PlayItem(kind,new Item.Properties().stacksTo(16))));
        }
    }
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<PlayBlockEntity>> ENTITY=ModBlocks.BLOCK_ENTITIES.register("playground",
            ()->BlockEntityType.Builder.of(PlayBlockEntity::new,BLOCKS.values().stream().map(DeferredBlock::get).toArray(PlayBlock[]::new)).build(null));
    public static void init(){}
    private Playgrounds(){}
}
