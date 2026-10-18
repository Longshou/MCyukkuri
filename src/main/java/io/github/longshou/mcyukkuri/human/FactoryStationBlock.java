package io.github.longshou.mcyukkuri.human;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.util.Comparator;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A local service point; all stock and material debits belong to the resident worker. */
public final class FactoryStationBlock extends Block {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private final VoxelShape shape;
    public FactoryStationBlock(String name,Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));
        shape=Block.box(0,0,0,16,switch(name){case "factory_transfer_tray"->4;case "manju_steamer"->32;case "live_packing_table"->24;case "nursery_station"->18;default->14;},16);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState s,Rotation r){return s.setValue(FACING,r.rotate(s.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState s,Mirror m){return s.rotate(m.getRotation(s.getValue(FACING)));}
    @Override protected boolean isPathfindable(BlockState s,PathComputationType type){return false;}
    @Override protected VoxelShape getShape(BlockState s,BlockGetter l,BlockPos p,CollisionContext c){return shape;}
    @Override protected InteractionResult useWithoutItem(BlockState s,Level l,BlockPos pos,Player p,BlockHitResult hit) {
        if (!l.isClientSide) {
            var worker=l.getEntitiesOfClass(HumanNpc.class,new AABB(pos).inflate(6),n->n.isAlive()&&n.job()==HumanNpc.Job.FACTORY)
                    .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
            if(worker!=null)HumanMenu.open(p,worker);
            else p.displayClientMessage(Component.translatable("human.mcyukkuri.worker_away"),true);
        }
        return InteractionResult.sidedSuccess(l.isClientSide);
    }
}
