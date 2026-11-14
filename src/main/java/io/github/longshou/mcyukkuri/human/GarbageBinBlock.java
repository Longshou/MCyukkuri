package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A lidded street bin (art/props/street-bin). Standing, its contents are out of a yukkuri's reach; a hard enough shove knocks
 * it over toward the far side and its open mouth then faces {@link #FACING}. People and players stand it back up.
 */
public final class GarbageBinBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty TIPPED = BooleanProperty.create("tipped");
    private static final String LEGACY_MARKER = "YukkuriGarbage";
    // Model boxes (16ths) with the mouth of the fallen can toward south; the blockstate turns them like the model.
    private static final double[][] STANDING = {{2, 0, 2, 14, 14, 14}, {6, 14, 7, 10, 15, 9}};
    private static final double[][] FALLEN = {{2, 0, 1, 14, 11, 13}, {14, 0, 2, 16, 12, 14}};
    private static final Map<Direction, VoxelShape> STANDING_SHAPES = shapes(STANDING), FALLEN_SHAPES = shapes(FALLEN);

    public GarbageBinBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH).setValue(TIPPED, false));
    }

    private static Map<Direction, VoxelShape> shapes(double[][] boxes) {
        var out = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (var facing : Direction.Plane.HORIZONTAL) {
            int turns = switch (facing) { case SOUTH -> 0; case WEST -> 1; case NORTH -> 2; default -> 3; };
            VoxelShape shape = Shapes.empty();
            for (var b : boxes) {
                double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
                for (int i = 0; i < turns; i++) { double a = x0, c = x1; x0 = 16 - z1; x1 = 16 - z0; z0 = a; z1 = c; }
                shape = Shapes.or(shape, box(x0, b[1], z0, x1, b[4], z1));
            }
            out.put(facing, shape.optimize());
        }
        return out;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, TIPPED); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return (state.getValue(TIPPED) ? FALLEN_SHAPES : STANDING_SHAPES).get(state.getValue(FACING));
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new GarbageBinBlockEntity(pos, state); }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (state.getValue(TIPPED)) {
            if (!standUp(level, pos)) return InteractionResult.FAIL;
            GameplayLog.world((ServerLevel) level, "street", "bin righted by=player player={} bin={}", player.getUUID(), pos.toShortString());
        } else if (level.getBlockEntity(pos) instanceof GarbageBinBlockEntity bin) {
            player.openMenu(new SimpleMenuProvider((id, inventory, owner) -> new ChestMenu(MenuType.GENERIC_9x1, id, inventory, bin, 1),
                    Component.translatable("block.mcyukkuri.garbage_bin")));
        }
        return InteractionResult.CONSUME;
    }

    /** True when the side it would fall toward is open (no collision there). */
    public static boolean canFall(Level level, BlockPos pos, Direction fall) {
        var to = pos.relative(fall);
        return level.hasChunkAt(to) && level.getBlockState(to).getCollisionShape(level, to).isEmpty() && level.getFluidState(to).isEmpty();
    }

    /**
     * Whether a yukkuri could ever reach what is inside: it has fallen already, or it still has one side to be shoved from that
     * leaves the far side open to fall into. A bin wedged between a bench and a wall has none, so nobody fills it.
     */
    public static boolean reachable(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof GarbageBinBlock)) return false;
        if (state.getValue(TIPPED)) return true;
        for (var side : Direction.Plane.HORIZONTAL) {
            var stand = pos.relative(side);
            if (canFall(level, pos, side.getOpposite()) && level.hasChunkAt(stand)
                    && level.getBlockState(stand).getCollisionShape(level, stand).isEmpty()
                    && level.getFluidState(stand).isEmpty()) return true;
        }
        return false;
    }

    /** Knocks the standing bin over toward {@code fall}; its contents stay inside, its mouth now opens that way. */
    public static void knockOver(Level level, BlockPos pos, Direction fall) {
        level.setBlock(pos, level.getBlockState(pos).setValue(TIPPED, true).setValue(FACING, fall), Block.UPDATE_ALL);
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, .8f, .7f);
    }

    public static boolean standUp(Level level, BlockPos pos) {
        var upright = level.getBlockState(pos).setValue(TIPPED, false);
        if (!level.isUnobstructed(upright, pos, CollisionContext.empty()) || !level.setBlock(pos, upright, Block.UPDATE_ALL)) return false;
        level.playSound(null, pos, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, .6f, 1.1f);
        return true;
    }

    /** Older street templates made garbage points from vanilla barrels tagged {@code NeoForgeData.YukkuriGarbage}. */
    public static boolean legacy(BlockEntity entity) {
        return entity instanceof BarrelBlockEntity barrel && !barrel.isRemoved() && barrel.getPersistentData().getBoolean(LEGACY_MARKER);
    }

    /**
     * Turns such a barrel, where a stray or a passer-by first looks at it, into a fallen bin opening the way the barrel lay
     * (its stand cell); untagged barrels are never touched. Its stock moves over in slot order; stacks past the bin's nine
     * slots drop at the spot, so nothing is lost. Not called while a caller iterates the chunk's block entities.
     */
    public static void adopt(ServerLevel level, BarrelBlockEntity barrel) {
        var pos = barrel.getBlockPos();
        if (level.getBlockEntity(pos) != barrel) return;
        var facing = barrel.getBlockState().getValue(BarrelBlock.FACING);
        var stock = new ArrayList<ItemStack>(barrel.getContainerSize());
        for (int slot = 0; slot < barrel.getContainerSize(); slot++) stock.add(barrel.getItem(slot).copy());
        var saved = barrel.getPersistentData();   // read while the barrel still exists
        barrel.clearContent();   // the barrel's own removal would otherwise spill what is moved below
        var state = ModBlocks.GARBAGE_BIN.get().defaultBlockState().setValue(TIPPED, facing.getAxis().isHorizontal())
                .setValue(FACING, facing.getAxis().isHorizontal() ? facing : Direction.SOUTH);
        if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
            for (int slot = 0; slot < stock.size(); slot++) barrel.setItem(slot, stock.get(slot));
            return;
        }
        var bin = (GarbageBinBlockEntity) level.getBlockEntity(pos);
        if (saved.contains(VillageBin.DAY)) bin.deposited(saved.getLong(VillageBin.DAY));
        int stacks = 0, dropped = 0;
        for (var stack : stock) {
            if (stack.isEmpty()) continue;
            if (stacks < GarbageBinBlockEntity.SLOTS) bin.setItem(stacks, stack);
            else { Containers.dropItemStack(level, pos.getX() + .5, pos.getY() + 1, pos.getZ() + .5, stack); dropped++; }
            stacks++;
        }
        GameplayLog.world(level, "street", "legacy garbage barrel converted bin={} stacks={} dropped={} tipped={}",
                pos.toShortString(), stacks, dropped, state.getValue(TIPPED));
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof GarbageBinBlockEntity bin) {
            Containers.dropContents(level, pos, bin);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
