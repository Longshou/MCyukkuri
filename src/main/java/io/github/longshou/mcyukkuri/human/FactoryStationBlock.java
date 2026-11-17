package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared facing and collision for factory equipment. */
public sealed class FactoryStationBlock extends Block permits FactoryStationBlock.Belt, FactoryStationBlock.Steamer {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private final VoxelShape shape;
    public FactoryStationBlock(String name, Properties properties) {
        this(properties, switch (name) {
            case "factory_transfer_tray" -> 4;
            case "live_packing_table" -> 24;
            case "nursery_station" -> 18;
            default -> 14;
        });
    }
    private FactoryStationBlock(Properties properties, int height) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
        shape = Block.box(0, 0, 0, 16, height, 16);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected boolean isPathfindable(BlockState s,PathComputationType type){return false;}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return shape;}

    public static final class Belt extends FactoryStationBlock {
        public static final BooleanProperty RUNNING = BooleanProperty.create("running");

        public Belt(Properties properties) {
            super(properties, 6); // The belt support surface is six model pixels above the floor.
            registerDefaultState(defaultBlockState().setValue(RUNNING, false));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(RUNNING);
        }
    }

    public static final class Steamer extends FactoryStationBlock {
        public static final BooleanProperty OPEN = BlockStateProperties.OPEN;
        public static final BooleanProperty LIT = BlockStateProperties.LIT;

        public Steamer(Properties properties) {
            super(properties, 21); // The closed lid sets the collision height.
            registerDefaultState(defaultBlockState().setValue(OPEN, false).setValue(LIT, false));
        }

        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            super.createBlockStateDefinition(builder);
            builder.add(OPEN, LIT);
        }
    }
}
