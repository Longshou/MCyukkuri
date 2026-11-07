package io.github.longshou.mcyukkuri.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A burial mound sized to the buried body, with a small stone marker at its head end (project design). The marker faces
 * whoever buried the body ({@link #FACING}); right-clicking the grave reads the buried record like a sign. The marker is
 * part of the outline but not of the collision, like a vanilla sign.
 */
public final class GraveBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    public GraveBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.SOUTH));}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new GraveBlockEntity(pos,state);}

    /** The head end of the mound, where the marker stands: away from the burier. */
    public static Direction head(BlockState state){return state.getValue(FACING).getOpposite();}
    /** Degrees about +Y that turn the mound and marker model (head toward +X) to {@link #head}. */
    public static float headYaw(BlockState state){return 270-head(state).toYRot();}
    /** Distance of the marker from the mound centre along the head axis, 1/16 block: just past the mound's end, inside the block. */
    public static float markerOffset(float scale){return Math.min(6.8f*scale+.1f,6.9f);}
    /** A smaller marker for a small body's grave. */
    public static float markerScale(float scale){return Math.clamp(scale,.5f,1f);}

    private static float scale(BlockGetter level,BlockPos pos){return level.getBlockEntity(pos) instanceof GraveBlockEntity grave?grave.sourceScale():1;}
    /** A box centred {@code along} 1/16 block from the block centre toward {@code head}. */
    private static VoxelShape around(Direction head,double along,double halfAlong,double halfAcross,double height){
        boolean x=head.getAxis()==Direction.Axis.X;double cx=8+head.getStepX()*along,cz=8+head.getStepZ()*along;
        double hx=x?halfAlong:halfAcross,hz=x?halfAcross:halfAlong;
        return box(cx-hx,0,cz-hz,cx+hx,height,cz+hz);
    }
    private static VoxelShape mound(BlockState state,float scale){return around(head(state),0,6.8*scale,5.032*scale,2.5*scale);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        float scale=scale(level,pos),marker=markerScale(scale);
        return Shapes.or(mound(state,scale),around(head(state),markerOffset(scale),marker,2*marker,6.5*marker));
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return mound(state,scale(level,pos));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof GraveBlockEntity grave)grave.read(player);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
