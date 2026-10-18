package io.github.longshou.mcyukkuri.play;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.util.RandomSource;

public final class PlayBlock extends Block implements EntityBlock {
    public enum Kind {
        PAPER("paper_tunnel",1),CERAMIC("ceramic_tunnel",1),SLIDE("yukkuri_slide",2),TRAMPOLINE("yukkuri_trampoline",1),POOL("yukkuri_pool",1);
        public final String id;public final int depth;
        Kind(String id,int depth){this.id=id;this.depth=depth;}
    }
    public static final IntegerProperty PART=IntegerProperty.create("part",0,49), WATER=IntegerProperty.create("water",0,3);
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final int CORE=12;
    private final Kind kind;
    public PlayBlock(Kind kind,Properties properties){super(properties);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(PART,CORE).setValue(FACING,
            Direction.SOUTH).setValue(WATER,0));}
    public Kind kind(){return kind;}
    public static BlockPos offset(int part){return new BlockPos(part%5-2,part/25,(part%25)/5-2);}
    public static int part(BlockPos offset){return offset.getX()+2+5*(offset.getZ()+2)+25*offset.getY();}
    public static BlockPos root(BlockPos pos,BlockState state){return pos.subtract(offset(state.getValue(PART)));}
    public static PlayBlockEntity facility(BlockGetter level,BlockPos pos){
        var state=level.getBlockState(pos);
        return state.getBlock() instanceof PlayBlock&&level.getBlockEntity(root(pos,state)) instanceof PlayBlockEntity e
                &&e.getBlockState().is(state.getBlock())&&e.facing()==state.getValue(FACING)?e:null;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(PART,FACING,WATER);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return state.getValue(PART)==CORE?new PlayBlockEntity(pos,state):null;}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.INVISIBLE;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){var shape=getCollisionShape(state,level,pos,context);
            return shape.isEmpty()?box(0,0,0,16,1,16):shape;}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return PlayGeometry.shape(kind,
            state.getValue(FACING),state.getValue(PART));}
    @Override protected VoxelShape getOcclusionShape(BlockState state,BlockGetter level,BlockPos pos){return Shapes.empty();}
    @Override protected boolean isPathfindable(BlockState state,PathComputationType type){return type==PathComputationType.LAND;}
    @Override protected FluidState getFluidState(BlockState state){return kind==Kind.POOL&&state.getValue(PART)==CORE&&state.getValue(WATER)>0
            ?Fluids.FLOWING_WATER.getFlowing(state.getValue(WATER),false):super.getFluidState(state);}
    @Override protected void entityInside(BlockState state,Level level,BlockPos pos,Entity entity){
        if(!level.isClientSide&&kind==Kind.POOL&&state.getValue(PART)==CORE&&entity instanceof YukkuriEntity y
                &&y.isInWater()&&y.tickCount%40==0){var pool=facility(level,pos);if(pool!=null)pool.rinse(y);}
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        var e=facility(level,pos);
        if(e==null||kind!=Kind.POOL||!stack.is(Items.WATER_BUCKET))return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(e.water()>=3)return ItemInteractionResult.FAIL;
        if(!level.isClientSide){e.fill();if(!player.getAbilities().instabuild)player.setItemInHand(hand,new ItemStack(Items.BUCKET));}
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        var e=facility(level,pos);if(kind!=Kind.POOL||e==null)return InteractionResult.PASS;
        if(!level.isClientSide)player.displayClientMessage(Component.translatable("play.mcyukkuri.pool",e.water()),true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState previous,boolean moving){
        super.onPlace(state,level,pos,previous,moving);if(!level.isClientSide&&state.getValue(PART)!=CORE)level.scheduleTick(pos,this,200);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(state.getValue(PART)==CORE)return;var root=root(pos,state);
        if(level.hasChunkAt(root)&&facility(level,pos)==null)level.removeBlock(pos,false);else level.scheduleTick(pos,this,200);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(!level.isClientSide&&player.getAbilities().instabuild){var e=facility(level,pos);if(e!=null)e.suppressDrop();}
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving){
        if(!state.is(replacement.getBlock())&&!level.isClientSide&&level.getBlockEntity(root(pos,state)) instanceof PlayBlockEntity e)e.dismantle();
        super.onRemove(state,level,pos,replacement,moving);
    }
}
