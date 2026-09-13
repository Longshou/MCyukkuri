package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.InteractionHand;

/** Small survival facilities. Inventory and residence are owned by the block entity. */
public final class CareBlock extends Block implements EntityBlock {
    public enum Kind { BOWL, NEST, TOILET, MAT }
    private final Kind kind;
    private final String style;
    private final int capacity;
    private final float height;
    public CareBlock(Kind kind, Properties properties) { this(kind,properties,"",8,0); }
    public CareBlock(Kind kind, Properties properties,String style,int capacity,float height) { super(properties);this.kind=kind;this.style=style;this.capacity=capacity;this.height=height; }
    public Kind kind() { return kind; }
    public String style(){return style;}
    public int capacity(){return capacity;}
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new CareBlockEntity(pos, state); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if(height>0)return box(0,0,0,16,height,16);
        return switch(kind){
            case BOWL -> box(.92,0,1.5572,15.08,2.22,14.4428);
            case NEST -> box(1.2,0,2.343894,14.8,1.863109,13.656106);
            case TOILET -> box(1.975,0,3.175,14.025,2.35,12.825);
            case MAT -> box(0,0,0,16,.15,16);
        };
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // Let notebook selection and the cleaning tool reach Item.useOn.
        if (stack.is(ModItems.FIELD_NOTEBOOK.get()) || stack.is(ModItems.WASH_CLOTH.get()))
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if(kind==Kind.MAT)return InteractionResult.PASS;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CareBlockEntity care) {
            if (kind == Kind.BOWL) {
                player.openMenu(new SimpleMenuProvider((id, inventory, owner) ->
                        new ChestMenu(MenuType.GENERIC_9x1, id, inventory, care, 1), Component.translatable("block.mcyukkuri.food_bowl")));
            } else if (kind == Kind.NEST) {
                player.displayClientMessage(Component.translatable(care.resident() == null ? "care.mcyukkuri.nest.free" : "care.mcyukkuri.nest.occupied"), true);
            } else {
                player.displayClientMessage(Component.translatable("care.mcyukkuri.toilet.level", care.waste(), care.capacity()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof CareBlockEntity care) {
            Containers.dropContents(level, pos, care);
            if (care.waste() > 0) for(var waste:care.cleanItems())popResource(level,pos,waste);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
