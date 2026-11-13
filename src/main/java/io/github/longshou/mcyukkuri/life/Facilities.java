package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The shop toys beside the yarn ball, and the floor a yukkuri stands on. */
public final class Facilities {
    private static final DeferredRegister.Blocks BLOCK_TYPES = DeferredRegister.createBlocks(MCYukkuri.MOD_ID);
    private static final DeferredRegister.Items ITEM_TYPES = DeferredRegister.createItems(MCYukkuri.MOD_ID);
    private static final List<String> TOYS = List.of("glass_marble","reimu_doll","marisa_doll","pretty_stone");
    public static final Map<String,DeferredItem<? extends Item>> ITEMS=new LinkedHashMap<>();
    static {
        for(String name:TOYS)ITEMS.put(name,ITEM_TYPES.registerSimpleItem(name,new Item.Properties().stacksTo(16)));
        // Removed duplicates and floor mats (item cleanup, 2026-11-13): old saves load them as the item / block that carries the same use.
        ITEM_TYPES.addAlias(MCYukkuri.id("rubber_ball"),MCYukkuri.id("yarn_ball"));
        ITEM_TYPES.addAlias(MCYukkuri.id("clear_dice"),MCYukkuri.id("glass_marble"));
        for(var pair:List.of(List.of("ceramic_toilet","mcyukkuri:yukkuri_toilet"),List.of("covered_toilet","mcyukkuri:yukkuri_toilet"),
                List.of("newspaper_mat","minecraft:white_carpet"),List.of("soil_mat","minecraft:brown_carpet"),List.of("sandpaper_mat","minecraft:yellow_carpet"))){
            ITEM_TYPES.addAlias(MCYukkuri.id(pair.get(0)),ResourceLocation.parse(pair.get(1)));
            BLOCK_TYPES.addAlias(MCYukkuri.id(pair.get(0)),ResourceLocation.parse(pair.get(1)));
        }
    }
    public static void register(IEventBus bus) {
        BLOCK_TYPES.register(bus);
        ITEM_TYPES.register(bus);
    }
    public static boolean toy(ItemStack stack) { return stack.is(ModItems.TOY.get())||TOYS.stream().anyMatch(id->stack.is(ITEMS.get(id).get())); }
    public static boolean rollingToy(ItemStack stack) { return stack.is(ModItems.TOY.get())||stack.is(ITEMS.get("glass_marble").get()); }
    public static BlockState floor(YukkuriEntity entity) {
        var pos = entity.blockPosition();
        var at = entity.level().getBlockState(pos);
        return occupiesFloorCell(at) ? at : entity.level().getBlockState(pos.below());
    }
    public static BlockPos floorPosition(YukkuriEntity entity) {
        var pos = entity.blockPosition();
        return occupiesFloorCell(entity.level().getBlockState(pos)) ? pos : pos.below();
    }
    private static boolean occupiesFloorCell(BlockState state) { return state.getBlock() instanceof CareBlock || state.is(BlockTags.WOOL_CARPETS); }
    public static float roughness(BlockState floor) {
        if(floor.getBlock() instanceof PetNestBlock||floor.is(BlockTags.WOOL_CARPETS))return 0;
        return floor.is(BlockTags.DIRT)?.4f:1;
    }
    public static float landing(BlockState floor) { return floor.getBlock() instanceof PetNestBlock||floor.is(BlockTags.WOOL_CARPETS)?.5f:1; }
    private Facilities() {}
}
