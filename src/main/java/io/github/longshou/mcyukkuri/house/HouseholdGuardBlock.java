package io.github.longshou.mcyukkuri.house;

import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Legacy save compatibility only. Recover the old box once as a finite spray bottle. */
public final class HouseholdGuardBlock extends Block {
    public HouseholdGuardBlock(Properties properties){super(properties);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return box(3,0,4,13,5,12);}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!player.getMainHandItem().isEmpty()||!player.mayBuild()||!level.mayInteract(player,pos)
                ||!player.mayUseItemAt(pos,hit.getDirection(),ItemStack.EMPTY))return InteractionResult.PASS;
        if(!level.isClientSide&&level.getBlockState(pos).is(this)&&level.removeBlock(pos,false)){
            SupplyPiles.give(player,new ItemStack(ModItems.HOUSEHOLD_GUARD.get()));
            player.displayClientMessage(Component.translatable("house.mcyukkuri.guard.legacy"),true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
