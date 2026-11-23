package io.github.longshou.mcyukkuri.play;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.TrampolineMotion;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.TrampolineSpring;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
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
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PlayBlock extends Block implements EntityBlock {
    public enum Kind {
        CERAMIC("ceramic_tunnel",1),SLIDE("yukkuri_slide",2),TRAMPOLINE("yukkuri_trampoline",1),POOL("yukkuri_pool",1);
        public final String id;public final int depth;
        Kind(String id,int depth){this.id=id;this.depth=depth;}
    }
    public static final IntegerProperty PART=IntegerProperty.create("part",0,49);
    public static final DirectionProperty FACING=BlockStateProperties.HORIZONTAL_FACING;
    public static final int CORE=12;
    public static final double BED_HEIGHT=8/16.0;
    static final double RIM_RADIUS=19/16.0;
    private static final int BED_IMPACT=1;
    private final Kind kind;
    public PlayBlock(Kind kind,Properties properties){super(properties);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(PART,CORE).setValue(FACING,
            Direction.SOUTH));}
    public Kind kind(){return kind;}
    public static BlockPos offset(int part){return new BlockPos(part%5-2,part/25,(part%25)/5-2);}
    public static int part(BlockPos offset){return offset.getX()+2+5*(offset.getZ()+2)+25*offset.getY();}
    public static BlockPos root(BlockPos pos,BlockState state){return pos.subtract(offset(state.getValue(PART)));}
    public static PlayBlockEntity facility(BlockGetter level,BlockPos pos){
        var state=level.getBlockState(pos);
        return state.getBlock() instanceof PlayBlock&&level.getBlockEntity(root(pos,state)) instanceof PlayBlockEntity e
                &&e.getBlockState().is(state.getBlock())&&e.facing()==state.getValue(FACING)?e:null;
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(PART,FACING);}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return state.getValue(PART)==CORE?new PlayBlockEntity(pos,state):null;}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.INVISIBLE;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return PlayGeometry.outline(kind,
            state.getValue(FACING),state.getValue(PART));}
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){return PlayGeometry.shape(kind,
            state.getValue(FACING),state.getValue(PART));}
    @Override protected VoxelShape getOcclusionShape(BlockState state,BlockGetter level,BlockPos pos){return Shapes.empty();}
    /**
     * Tunnel shell and trampoline rim/bed are driven by the session route, not the navigator: ordinary walkers plan around them, only
     * their empty entry and exit cells stay open so a session can still path to its first waypoint.
     */
    @Override protected boolean isPathfindable(BlockState state,PathComputationType type){
        if(type!=PathComputationType.LAND)return false;
        return (kind!=Kind.CERAMIC&&kind!=Kind.TRAMPOLINE)||PlayGeometry.shape(kind,state.getValue(FACING),state.getValue(PART)).isEmpty();}

    public static boolean onTrampolineBed(Entity entity) {
        var pos = entity.getOnPos();
        return onTrampolineBed(entity.level().getBlockState(pos), pos, entity);
    }

    private static boolean onTrampolineBed(BlockState state, BlockPos pos, Entity entity) {
        // The supporting collision has a top at y=8 only for the bed. Rim and legs are at y=9 and y=4.
        return state.getBlock() instanceof PlayBlock block && block.kind == Kind.TRAMPOLINE
                && entity.onGround() && Math.abs(entity.getY() - pos.getY() - BED_HEIGHT) < 1e-6;
    }

    @Override public void fallOn(Level level, BlockState state, BlockPos pos, Entity entity, float distance) {
        if (!onTrampolineBed(state, pos, entity) || entity.isSuppressingBounce()) {
            super.fallOn(level, state, pos, entity, distance);
        } else if (entity instanceof ServerPlayer) {
            // The server receives the player's landings as positions. Estimate the impact from its measured fall.
            double impact = Math.max(-entity.getKnownMovement().y, Math.sqrt(2 * Ballistic.GRAVITY * distance));
            if (TrampolineSpring.rebound(impact, TrampolineSpring.RESTITUTION, entity.getGravity()) > 0) {
                bedImpact(level, state, pos, impact);
            }
        }
    }

    @Override public void updateEntityAfterFallOn(BlockGetter level, Entity entity) {
        var pos = entity.getOnPos();
        var state = level.getBlockState(pos);
        double impact = -entity.getDeltaMovement().y;
        if (state.is(this) && onTrampolineBed(state, pos, entity) && !entity.isSuppressingBounce()
                && !(entity instanceof ServerPlayer)) {
            boolean rebounded;
            if (entity instanceof YukkuriEntity body) {
                var root = root(pos, state);
                if (body.playLife().stoppingAt(root)) {
                    super.updateEntityAfterFallOn(level, entity);
                    return;
                }
                boolean playing = body.playLife().springingAt(root);
                rebounded = playing
                        ? TrampolineMotion.activeRebound(body, impact, body.playLife().bounces())
                        : TrampolineMotion.rebound(body, impact, false);
                if (rebounded && playing) body.playLife().trampolineBounced();
            } else rebounded = TrampolineMotion.rebound(entity, impact);
            if (rebounded) {
                if (level instanceof ServerLevel server) bedImpact(server, state, pos, impact);
                return;
            }
        }
        super.updateEntityAfterFallOn(level, entity);
    }

    private void bedImpact(Level level, BlockState state, BlockPos pos, double speed) {
        var center = root(pos, state);
        // A solid landing (about three blocks of fall) presses the bed to its authored limit.
        double strength = Math.min(1, speed / .65);
        level.blockEvent(center, this, BED_IMPACT, (int) Math.round(255 * strength));
        level.playSound(null, center, SoundEvents.SLIME_JUMP, SoundSource.BLOCKS, (float) (.2 + .6 * strength), 1.45f);
    }

    @Override protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int amount) {
        if (id != BED_IMPACT || kind != Kind.TRAMPOLINE) return super.triggerEvent(state, level, pos, id, amount);
        if (!(level.getBlockEntity(pos) instanceof PlayBlockEntity trampoline)) return false;
        if (level.isClientSide) trampoline.pressBed(amount / 255f);
        return true;
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        var e=facility(level,pos);
        if(e==null||kind!=Kind.POOL||!stack.is(ModItems.ORANGE_JUICE.get()))return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if(JuiceTub.full(e.juice())){
            if(!level.isClientSide)player.displayClientMessage(Component.translatable("play.mcyukkuri.pool_full"),true);
            return ItemInteractionResult.FAIL;
        }
        if(!level.isClientSide){
            int before=e.juice();e.pour();
            // The bottle goes back the way a vanilla bottle does; creative keeps the item.
            if(!player.getAbilities().instabuild)player.setItemInHand(hand,ItemUtils.createFilledResult(stack,player,new ItemStack(Items.GLASS_BOTTLE)));
            level.playSound(null,pos,SoundEvents.BOTTLE_EMPTY,SoundSource.BLOCKS,1,1);
            GameplayLog.event(player,"play","tub poured player={} target={} juice={} -> {}",player.getScoreboardName(),root(pos,state),before,e.juice());
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        var e=facility(level,pos);if(kind!=Kind.POOL||e==null)return InteractionResult.PASS;
        if(!level.isClientSide)player.displayClientMessage(Component.translatable("play.mcyukkuri.pool",e.juice(),JuiceTub.CAPACITY),true);
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
