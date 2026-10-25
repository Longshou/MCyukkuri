package io.github.longshou.mcyukkuri.registry;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.life.CareBlock;
import io.github.longshou.mcyukkuri.life.LargeNestBlock;
import io.github.longshou.mcyukkuri.life.CareBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import io.github.longshou.mcyukkuri.body.GraveBlock;
import io.github.longshou.mcyukkuri.body.GraveBlockEntity;
import io.github.longshou.mcyukkuri.family.DetachedStemBlock;
import io.github.longshou.mcyukkuri.family.DetachedStemBlockEntity;
import io.github.longshou.mcyukkuri.house.HouseNestBlock;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.house.SupplyPileBlock;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.life.PoopBlock;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import io.github.longshou.mcyukkuri.settlement.DenBarrierBlock;
import io.github.longshou.mcyukkuri.settlement.DenBarrierBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlock;
import io.github.longshou.mcyukkuri.settlement.ForagePileBlockEntity;
import io.github.longshou.mcyukkuri.settlement.ShelterBlock;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import net.minecraft.world.level.material.PushReaction;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MCYukkuri.MOD_ID);
    public static final DeferredBlock<DetachedStemBlock> DETACHED_STEM=BLOCKS.register("detached_brood_stem",()->new DetachedStemBlock(false,
            BlockBehaviour.Properties.of().strength(.2f).noCollission().noOcclusion()));
    public static final DeferredBlock<DetachedStemBlock> BOTTLED_STEM=BLOCKS.register("bottled_brood_stem",()->new DetachedStemBlock(true,
            BlockBehaviour.Properties.of().strength(.3f).sound(SoundType.GLASS).noCollission().noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<DetachedStemBlockEntity>> DETACHED_STEM_ENTITY=BLOCK_ENTITIES.register("detached_stem",
            ()->BlockEntityType.Builder.of(DetachedStemBlockEntity::new,DETACHED_STEM.get(),BOTTLED_STEM.get()).build(null));
    public static final DeferredBlock<CareBlock> FOOD_BOWL = register("food_bowl", CareBlock.Kind.BOWL, SoundType.STONE);
    public static final DeferredBlock<CareBlock> NEST = register("yukkuri_nest", CareBlock.Kind.NEST, SoundType.GRASS);
    public static final DeferredBlock<LargeNestBlock> LARGE_NEST = BLOCKS.register("large_yukkuri_nest", () ->
            new LargeNestBlock(BlockBehaviour.Properties.of().strength(.6f).sound(SoundType.GRASS).noOcclusion().pushReaction(PushReaction.DESTROY),"large_yukkuri_nest"));
    public static final DeferredBlock<LargeNestBlock> LARGE_WILD_NEST = BLOCKS.register("large_wild_nest", () ->
            new LargeNestBlock(BlockBehaviour.Properties.of().strength(.6f).sound(SoundType.GRASS).noOcclusion().pushReaction(PushReaction.DESTROY),"large_wild_nest"));
    public static final DeferredBlock<CareBlock> PREDATOR_NEST = BLOCKS.register("predator_nest", () ->
            new CareBlock(CareBlock.Kind.NEST, BlockBehaviour.Properties.of().strength(.6f).sound(SoundType.ROOTS).noOcclusion(),
                    "predator_nest", 1, 2.4f));
    public static final DeferredBlock<CareBlock> TOILET = register("yukkuri_toilet", CareBlock.Kind.TOILET, SoundType.WOOD);
    public static final DeferredBlock<ShelterBlock> FOREST_BURROW=BLOCKS.register("forest_burrow",
            ()->new ShelterBlock(ShelterBlock.Kind.BURROW,BlockBehaviour.Properties.of().strength(.8f).sound(SoundType.ROOTS).noOcclusion()));
    public static final DeferredBlock<ShelterBlock> CARDBOARD_SHELTER=BLOCKS.register("cardboard_shelter",
            ()->new ShelterBlock(ShelterBlock.Kind.CARDBOARD,BlockBehaviour.Properties.of().strength(.3f).sound(SoundType.WOOL).noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<ShelterBlockEntity>> SHELTER_ENTITY=BLOCK_ENTITIES.register("wild_shelter",
            ()->BlockEntityType.Builder.of(ShelterBlockEntity::new,FOREST_BURROW.get(),CARDBOARD_SHELTER.get()).build(null));
    public static final DeferredBlock<ForagePileBlock> FORAGE_PILE=BLOCKS.register("forage_pile",
            ()->new ForagePileBlock(BlockBehaviour.Properties.of().strength(.3f).sound(SoundType.GRASS).noCollission().noOcclusion()
                    .pushReaction(PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<ForagePileBlockEntity>> FORAGE_PILE_ENTITY=BLOCK_ENTITIES.register("forage_pile",
            ()->BlockEntityType.Builder.of(ForagePileBlockEntity::new,FORAGE_PILE.get()).build(null));
    /** The twig-and-leaf screen a clever wild Reimu piles across its den mouth (settlement/DenSeal). Has no item. */
    public static final DeferredBlock<DenBarrierBlock> DEN_BARRIER=BLOCKS.register("den_barrier",
            ()->new DenBarrierBlock(BlockBehaviour.Properties.of().strength(.3f).sound(SoundType.GRASS).noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<DenBarrierBlockEntity>> DEN_BARRIER_ENTITY=BLOCK_ENTITIES.register("den_barrier",
            ()->BlockEntityType.Builder.of(DenBarrierBlockEntity::new,DEN_BARRIER.get()).build(null));
    public static final DeferredBlock<GraveBlock> GRAVE=BLOCKS.register("burial_mound",()->new GraveBlock(BlockBehaviour.Properties.of().strength(.5f).sound(SoundType.GRAVEL).noOcclusion()));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<GraveBlockEntity>> GRAVE_ENTITY=BLOCK_ENTITIES.register("grave",
            ()->BlockEntityType.Builder.of(GraveBlockEntity::new,GRAVE.get()).build(null));
    public static final DeferredBlock<HouseNestBlock> HOUSE_DUST_NEST=BLOCKS.register("house_dust_nest",
            ()->new HouseNestBlock(BlockBehaviour.Properties.of().instabreak().sound(SoundType.WOOL).noCollission().noOcclusion()
                    .pushReaction(PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<HouseNestBlockEntity>> HOUSE_NEST_ENTITY=BLOCK_ENTITIES.register("house_nest",
            ()->BlockEntityType.Builder.of(HouseNestBlockEntity::new,HOUSE_DUST_NEST.get()).build(null));
    public static final DeferredBlock<SupplyPileBlock> HOUSE_SUPPLIES=BLOCKS.register("house_supplies",
            ()->new SupplyPileBlock(BlockBehaviour.Properties.of().instabreak().sound(SoundType.WOOL).noCollission().noOcclusion().replaceable().randomTicks()
                    .pushReaction(PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<SupplyPileBlockEntity>> HOUSE_SUPPLIES_ENTITY=BLOCK_ENTITIES.register("house_supplies",
            ()->BlockEntityType.Builder.of(SupplyPileBlockEntity::new,HOUSE_SUPPLIES.get()).build(null));
    /** Droppings on the ground (life/Poops): non-solid, up to eight portions per cell, four looks by load. */
    public static final DeferredBlock<PoopBlock> POOP=BLOCKS.register("yukkuri_poop",
            ()->new PoopBlock(BlockBehaviour.Properties.of().instabreak().sound(SoundType.MUD).noCollission().noOcclusion().pushReaction(PushReaction.DESTROY)));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<PoopBlockEntity>> POOP_ENTITY=BLOCK_ENTITIES.register("poop",
            ()->BlockEntityType.Builder.of(PoopBlockEntity::new,POOP.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CareBlockEntity>> CARE = BLOCK_ENTITIES.register("care",
            () -> BlockEntityType.Builder.of(CareBlockEntity::new, FOOD_BOWL.get(), NEST.get(), LARGE_NEST.get(), LARGE_WILD_NEST.get(), PREDATOR_NEST.get(), TOILET.get(),
                    Facilities.NEWSPAPER.get(),Facilities.SOIL.get(),Facilities.SANDPAPER.get(),
                    Facilities.CERAMIC_TOILET.get(),Facilities.COVERED_TOILET.get()).build(null));

    private static DeferredBlock<CareBlock> register(String id, CareBlock.Kind kind, SoundType sound) {
        return BLOCKS.register(id, () -> new CareBlock(kind, BlockBehaviour.Properties.of().strength(.6f).sound(sound).noOcclusion()));
    }
    private ModBlocks() {}
}
