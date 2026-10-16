package io.github.longshou.mcyukkuri.house;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A maintenance-free household protection fixture: no ticking block entity or consumable. */
public final class HouseholdGuardBlock extends Block {
    public HouseholdGuardBlock(Properties properties){super(properties);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return box(3,0,4,13,5,12);}
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState previous,boolean moving){
        super.onPlace(state,level,pos,previous,moving);
        if(!previous.is(this)&&level instanceof ServerLevel server)HouseholdProtection.get(server).add(pos);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving){
        if(!replacement.is(this)&&level instanceof ServerLevel server)HouseholdProtection.get(server).remove(pos);
        super.onRemove(state,level,pos,replacement,moving);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide)player.displayClientMessage(Component.translatable("house.mcyukkuri.guard.active",HouseholdProtection.RADIUS,HouseholdProtection.HEIGHT),true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack,Item.TooltipContext context,List<Component> tooltip,TooltipFlag flag){
        tooltip.add(Component.translatable("house.mcyukkuri.guard.active",HouseholdProtection.RADIUS,HouseholdProtection.HEIGHT));
        tooltip.add(Component.translatable("house.mcyukkuri.guard.existing"));
    }
}
