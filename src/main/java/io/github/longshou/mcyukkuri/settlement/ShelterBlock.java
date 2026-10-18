package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.registry.ModBlocks;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

/** Hollow, physical multi-block shelters. Only the central floor block owns inventory and residents. */
public final class ShelterBlock extends Block implements EntityBlock {
    public enum Kind {
        BURROW("forest_burrow", 0, 1, 16), CARDBOARD("cardboard_shelter", 1, 2, 1);
        public final String id;
        public final int radius, height, capacity;
        Kind(String id, int radius, int height, int capacity) { this.id=id; this.radius=radius; this.height=height; this.capacity=capacity; }
    }
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 74);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final int CORE = 12;
    private final Kind kind;
    public ShelterBlock(Kind kind, Properties properties) {
        super(properties); this.kind=kind;
        registerDefaultState(stateDefinition.any().setValue(PART,CORE).setValue(FACING,Direction.SOUTH));
    }
    public Kind kind() { return kind; }
    public static BlockPos offset(int part) { return new BlockPos(part % 5 - 2, part / 25, (part % 25) / 5 - 2); }
    public static int part(int x, int y, int z) { return x + 2 + 5 * (z + 2) + 25 * y; }
    public static BlockPos root(BlockPos pos, BlockState state) { return pos.subtract(offset(state.getValue(PART))); }
    public static ShelterBlockEntity shelter(BlockGetter level, BlockPos pos) {
        if(level instanceof Level world&&!world.hasChunkAt(pos))return null;
        var state=level.getBlockState(pos);
        if(!(state.getBlock() instanceof ShelterBlock)) {
            for(var at:BlockPos.betweenClosed(pos.offset(-NaturalShelter.RADIUS,0,-NaturalShelter.RADIUS),pos.offset(NaturalShelter.RADIUS,0,NaturalShelter.RADIUS))) {
                if(level instanceof Level world&&!world.hasChunkAt(at))continue;
                if(level.getBlockEntity(at) instanceof ShelterBlockEntity home&&home.kind()==Kind.BURROW&&home.contains(pos))return home;
            }
            return null;
        }
        return state.getBlock() instanceof ShelterBlock && level.getBlockEntity(root(pos,state)) instanceof ShelterBlockEntity value
                &&value.getBlockState().is(state.getBlock())&&value.facing()==state.getValue(FACING)?value:null;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder) { builder.add(PART,FACING); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return state.getValue(PART)==CORE ? new ShelterBlockEntity(pos,state) : null; }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && state.getValue(PART)==CORE && type==ModBlocks.SHELTER_ENTITY.get()
                ? (world,pos,block,entity)->((ShelterBlockEntity)entity).tick() : null;
    }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape collision=getCollisionShape(state,level,pos,context);
        return collision.isEmpty() ? box(0,0,0,16,1,16) : collision;
    }
    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return kind==Kind.BURROW?Shapes.empty():ShelterGeometry.shape(kind,state.getValue(FACING),state.getValue(PART));
    }
    @Override protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }
    @Override protected int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return offset(state.getValue(PART)).getY() > 0 && !getCollisionShape(state,level,pos,CollisionContext.empty()).isEmpty() ? 15 : 0;
    }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type==PathComputationType.LAND&&kind!=null&&offset(state.getValue(PART)).getY()==0
                &&ShelterGeometry.shape(kind,state.getValue(FACING),state.getValue(PART)).isEmpty();
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState previous,boolean moving) {
        super.onPlace(state,level,pos,previous,moving);
        if(!level.isClientSide&&state.getValue(PART)!=CORE)level.scheduleTick(pos,this,200);
    }
    @Override protected void tick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random) {
        if(state.getValue(PART)==CORE)return;
        var root=root(pos,state);
        // Scheduled ticks survive chunk unloading; abandoned shell pieces disappear when their root is observable.
        if(level.hasChunkAt(root)&&(kind==Kind.BURROW||shelter(level,pos)==null))level.removeBlock(pos,false);
        else level.scheduleTick(pos,this,200);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        var home=shelter(level,pos);
        if(home==null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(stack.is(ModItems.WASH_CLOTH.get())) {
            if(!level.isClientSide) home.clean(player);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        if(home.accepts(stack)) {
            if(!level.isClientSide && home.deposit(stack.copyWithCount(1)) && !player.getAbilities().instabuild) stack.shrink(1);
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        var home=shelter(level,pos);
        if(!level.isClientSide && home!=null) {
            player.displayClientMessage(home.description(),false);
            // Sneaking with an empty hand reaches into the nest and takes the residents' treasures (they cry and learn to fear the taker).
            if(player.isShiftKeyDown()&&level instanceof ServerLevel server)
                for(var id:home.residentIds())if(server.getEntity(id) instanceof YukkuriEntity owner)owner.treasure().confiscate(player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if(!state.is(replacement.getBlock()) && !level.isClientSide) {
            var home=level.getBlockEntity(root(pos,state)) instanceof ShelterBlockEntity value?value:null;
            if(home!=null&&home.getBlockState().is(state.getBlock())&&home.facing()==state.getValue(FACING)) home.dismantle();
        }
        super.onRemove(state,level,pos,replacement,moving);
    }
}
