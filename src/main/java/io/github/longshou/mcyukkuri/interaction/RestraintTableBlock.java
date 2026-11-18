package io.github.longshou.mcyukkuri.interaction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class RestraintTableBlock extends Block implements EntityBlock {
    public static final String ID = "restraint_frame";
    private static final double TOP = 5.0 / 16;
    private static final VoxelShape SHAPE = Shapes.or(box(2, 0, 2, 14, 3, 14), box(0, 3, 0, 16, 5, 16));

    public RestraintTableBlock(Properties properties) { super(properties); }

    public static Vec3 surface(BlockPos pos) { return Vec3.atBottomCenterOf(pos).add(0, TOP, 0); }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RestraintTableBlockEntity(pos, state);
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()) return InteractionResult.PASS;
        if (level instanceof ServerLevel && level.getBlockEntity(pos) instanceof RestraintTableBlockEntity table)
            table.release(player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (level instanceof ServerLevel && !replacement.is(this) && level.getBlockEntity(pos) instanceof RestraintTableBlockEntity table)
            table.release("table_removed");
        super.onRemove(state, level, pos, replacement, moving);
    }
}
