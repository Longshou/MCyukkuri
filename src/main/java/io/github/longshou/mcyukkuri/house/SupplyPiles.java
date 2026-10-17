package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

/**
 * Shared accounting for ground debris. Lint and crumbs are world state produced by human activity: the two registered
 * items are only the internal carriers for storage, mouth display and rendering, and never reach a player (see
 * {@link HouseholdEcology#purge}). Waste is the one real item, the cleaned-up result that can be composted.
 */
public final class SupplyPiles {
    public static boolean lint(ItemStack stack){return !stack.isEmpty()&&stack.is(ModItems.NEST_LINT.get());}
    public static boolean crumb(ItemStack stack){return !stack.isEmpty()&&stack.is(ModItems.FOOD_SCRAP.get());}
    public static boolean waste(ItemStack stack){return !stack.isEmpty()&&stack.is(ModItems.WASTE.get());}
    /** True for the internal carrier items that must never exist in a player's hands, containers or as loose item entities. */
    public static boolean internal(ItemStack stack){return lint(stack)||crumb(stack);}
    public static boolean material(ItemStack stack){return lint(stack)||crumb(stack)||waste(stack);}
    public static ItemStack lintUnit(){return new ItemStack(ModItems.NEST_LINT.get());}
    public static ItemStack crumbUnit(){return new ItemStack(ModItems.FOOD_SCRAP.get());}
    public static boolean supported(LevelReader level,BlockPos pos){
        return level.hasChunkAt(pos)&&level.getFluidState(pos).isEmpty()
                &&level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);
    }
    /** Returns the amount stored in the ground cell; the caller debits whatever it owns. Never replaces another block. */
    public static int offer(Level level,BlockPos pos,ItemStack stack,int limit){
        if(level.isClientSide||!material(stack)||limit<=0||!supported(level,pos))return 0;
        if(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile)return pile.deposit(stack,limit);
        // Never replace another block or force-load a chunk for microscopic debris.
        if(!level.isInWorldBounds(pos)||!level.isEmptyBlock(pos))return 0;
        if(!level.setBlockAndUpdate(pos,ModBlocks.HOUSE_SUPPLIES.get().defaultBlockState()))return 0;
        if(level.getBlockEntity(pos) instanceof SupplyPileBlockEntity pile)return pile.deposit(stack,limit);
        level.removeBlock(pos,false);return 0;
    }
    public static void give(Player player,ItemStack stack){
        while(!stack.isEmpty()){
            var portion=stack.split(Math.min(stack.getMaxStackSize(),stack.getCount()));
            if(!player.getInventory().add(portion))player.drop(portion,false);
        }
    }
    private SupplyPiles(){}
}
