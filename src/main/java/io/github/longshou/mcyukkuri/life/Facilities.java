package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.bus.api.IEventBus;
import java.util.List;

public final class Facilities {
    private static final DeferredRegister.Blocks BLOCK_TYPES = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    private static final DeferredRegister.Items ITEM_TYPES = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    public static final ResourceKey<DamageType> ABRASION=ResourceKey.create(Registries.DAMAGE_TYPE,MCYukkuri.id("terrain_abrasion"));
    public static final Map<String,DeferredItem<? extends Item>> ITEMS=new LinkedHashMap<>();
    public static final DeferredBlock<CareBlock> NEWSPAPER=block("newspaper_mat",CareBlock.Kind.MAT,8,.15f);
    public static final DeferredBlock<CareBlock> SOIL=block("soil_mat",CareBlock.Kind.MAT,8,.85f);
    public static final DeferredBlock<CareBlock> SANDPAPER=block("sandpaper_mat",CareBlock.Kind.MAT,8,.25f);
    public static final DeferredBlock<CareBlock> CERAMIC_TOILET=block("ceramic_toilet",CareBlock.Kind.TOILET,16,2.8f);
    public static final DeferredBlock<CareBlock> COVERED_TOILET=block("covered_toilet",CareBlock.Kind.TOILET,32,4.5f);
    static { for(String name:List.of("glass_marble","rubber_ball","reimu_doll","marisa_doll","pretty_stone","clear_dice"))
        ITEMS.put(name,ITEM_TYPES.registerSimpleItem(name,new Item.Properties().stacksTo(16))); }
    private static DeferredBlock<CareBlock> block(String name,CareBlock.Kind kind,int capacity,float height) {
        var block=BLOCK_TYPES.register(name,()->new CareBlock(kind,BlockBehaviour.Properties.of().strength(.5f).sound(kind==CareBlock.Kind.MAT?SoundType.WOOL
                :SoundType.STONE).noOcclusion(),name,capacity,height));
        ITEMS.put(name,ITEM_TYPES.register(name,()->new BlockItem(block.get(),new Item.Properties())));return block;
    }
    public static void register(IEventBus bus) {
        BLOCK_TYPES.register(bus);
        ITEM_TYPES.register(bus);
    }
    public static boolean toy(ItemStack stack) { return stack.is(ModItems.TOY.get())||List.of("glass_marble","rubber_ball","reimu_doll","marisa_doll","pretty_stone",
            "clear_dice").stream().anyMatch(id->stack.is(ITEMS.get(id).get())); }
    public static boolean rollingToy(ItemStack stack) { return stack.is(ModItems.TOY.get())||stack.is(ITEMS.get("glass_marble").get())||stack.is(ITEMS.get("rubber_ball").get()); }
    public static BlockState floor(YukkuriEntity entity) {
        var at=entity.level().getBlockState(entity.blockPosition());
        return at.getBlock() instanceof CareBlock||at.is(BlockTags.WOOL_CARPETS)?at:entity.level().getBlockState(entity.blockPosition().below());
    }
    public static float roughness(BlockState floor) {
        if(floor.is(NEWSPAPER.get()))return .28f/.48f;
        if(floor.is(SOIL.get()))return .75f/.48f;
        if(floor.is(SANDPAPER.get()))return 5f/.48f;
        if(floor.is(ModBlocks.NEST.get())||floor.is(BlockTags.WOOL_CARPETS))return 0;
        return floor.is(BlockTags.DIRT)?.4f:1;
    }
    public static float landing(BlockState floor) { return floor.is(NEWSPAPER.get())?.9f:floor.is(SOIL.get())?.65f:floor.is(ModBlocks.NEST.get())||floor.is(BlockTags.WOOL_CARPETS)?.5f:1; }
    private Facilities() {}
}
