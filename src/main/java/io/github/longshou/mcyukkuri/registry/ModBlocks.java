package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MCYukkuri.MOD_ID);
    public static final DeferredBlock<io.github.longshou.mcyukkuri.family.DetachedStemBlock> DETACHED_STEM=BLOCKS.register("detached_brood_stem",()->new io.github.longshou.mcyukkuri.family.DetachedStemBlock(false,BlockBehaviour.Properties.of().strength(.2f).noCollission().noOcclusion()));
    public static final DeferredBlock<io.github.longshou.mcyukkuri.family.DetachedStemBlock> BOTTLED_STEM=BLOCKS.register("bottled_brood_stem",()->new io.github.longshou.mcyukkuri.family.DetachedStemBlock(true,BlockBehaviour.Properties.of().strength(.3f).sound(SoundType.GLASS).noCollission().noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity>> DETACHED_STEM_ENTITY=BLOCK_ENTITIES.register("detached_stem",
            ()->BlockEntityType.Builder.of(io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity::new,DETACHED_STEM.get(),BOTTLED_STEM.get()).build(null));
    public static final DeferredBlock<CareBlock> FOOD_BOWL = register("food_bowl", CareBlock.Kind.BOWL, SoundType.STONE);
    public static final DeferredBlock<CareBlock> NEST = register("yukkuri_nest", CareBlock.Kind.NEST, SoundType.GRASS);
    public static final DeferredBlock<CareBlock> PREDATOR_NEST = BLOCKS.register("predator_nest", () ->
            new CareBlock(CareBlock.Kind.NEST, BlockBehaviour.Properties.of().strength(.6f).sound(SoundType.ROOTS).noOcclusion(),
                    "predator_nest", 1, 2.4f));
    public static final DeferredBlock<CareBlock> TOILET = register("yukkuri_toilet", CareBlock.Kind.TOILET, SoundType.WOOD);
    public static final DeferredBlock<CareBlock> WILD_LATRINE = register("wild_latrine",CareBlock.Kind.TOILET,SoundType.GRAVEL);
    public static final DeferredBlock<io.github.longshou.mcyukkuri.settlement.ShelterBlock> FOREST_BURROW=BLOCKS.register("forest_burrow",
            ()->new io.github.longshou.mcyukkuri.settlement.ShelterBlock(io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.BURROW,BlockBehaviour.Properties.of().strength(.8f).sound(SoundType.ROOTS).noOcclusion()));
    public static final DeferredBlock<io.github.longshou.mcyukkuri.settlement.ShelterBlock> CARDBOARD_SHELTER=BLOCKS.register("cardboard_shelter",
            ()->new io.github.longshou.mcyukkuri.settlement.ShelterBlock(io.github.longshou.mcyukkuri.settlement.ShelterBlock.Kind.CARDBOARD,BlockBehaviour.Properties.of().strength(.3f).sound(SoundType.WOOL).noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity>> SHELTER_ENTITY=BLOCK_ENTITIES.register("wild_shelter",
            ()->BlockEntityType.Builder.of(io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity::new,FOREST_BURROW.get(),CARDBOARD_SHELTER.get()).build(null));
    public static final DeferredBlock<io.github.longshou.mcyukkuri.body.GraveBlock> GRAVE=BLOCKS.register("burial_mound",()->new io.github.longshou.mcyukkuri.body.GraveBlock(BlockBehaviour.Properties.of().strength(.5f).sound(SoundType.GRAVEL).noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<io.github.longshou.mcyukkuri.body.GraveBlockEntity>> GRAVE_ENTITY=BLOCK_ENTITIES.register("grave",
            ()->BlockEntityType.Builder.of(io.github.longshou.mcyukkuri.body.GraveBlockEntity::new,GRAVE.get()).build(null));
    public static final DeferredBlock<io.github.longshou.mcyukkuri.house.HouseNestBlock> HOUSE_DUST_NEST=BLOCKS.register("house_dust_nest",
            ()->new io.github.longshou.mcyukkuri.house.HouseNestBlock(BlockBehaviour.Properties.of().instabreak().sound(SoundType.WOOL).noCollission().noOcclusion()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<io.github.longshou.mcyukkuri.house.HouseNestBlockEntity>> HOUSE_NEST_ENTITY=BLOCK_ENTITIES.register("house_nest",
            ()->BlockEntityType.Builder.of(io.github.longshou.mcyukkuri.house.HouseNestBlockEntity::new,HOUSE_DUST_NEST.get()).build(null));
    public static final DeferredBlock<io.github.longshou.mcyukkuri.house.SupplyPileBlock> HOUSE_SUPPLIES=BLOCKS.register("house_supplies",
            ()->new io.github.longshou.mcyukkuri.house.SupplyPileBlock(BlockBehaviour.Properties.of().instabreak().sound(SoundType.WOOL).noCollission().noOcclusion().replaceable()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity>> HOUSE_SUPPLIES_ENTITY=BLOCK_ENTITIES.register("house_supplies",
            ()->BlockEntityType.Builder.of(io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity::new,HOUSE_SUPPLIES.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CareBlockEntity>> CARE = BLOCK_ENTITIES.register("care",
            () -> BlockEntityType.Builder.of(CareBlockEntity::new, FOOD_BOWL.get(), NEST.get(), PREDATOR_NEST.get(), TOILET.get(),WILD_LATRINE.get(),
                    io.github.longshou.mcyukkuri.life.Facilities.NEWSPAPER.get(),io.github.longshou.mcyukkuri.life.Facilities.SOIL.get(),io.github.longshou.mcyukkuri.life.Facilities.SANDPAPER.get(),
                    io.github.longshou.mcyukkuri.life.Facilities.CERAMIC_TOILET.get(),io.github.longshou.mcyukkuri.life.Facilities.COVERED_TOILET.get()).build(null));

    private static DeferredBlock<CareBlock> register(String id, CareBlock.Kind kind, SoundType sound) {
        return BLOCKS.register(id, () -> new CareBlock(kind, BlockBehaviour.Properties.of().strength(.6f).sound(sound).noOcclusion()));
    }
    private ModBlocks() {}
}
