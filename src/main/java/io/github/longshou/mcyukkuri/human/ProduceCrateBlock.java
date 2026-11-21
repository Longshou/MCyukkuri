package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An open crate whose low side faces FACING. A counter crate (flagged) trades over its public side through the keeper, see {@link Counter}.
 */
public class ProduceCrateBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 9, 16);

    public ProduceCrateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(VillageWood.WOOD, VillageWood.OAK));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, VillageWood.WOOD); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(VillageWood.WOOD, VillageWood.beside(context.getLevel(), context.getClickedPos(), this));
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPE; }
    @Override protected boolean isPathfindable(BlockState state, PathComputationType type) { return false; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProduceCrateBlockEntity(pos, state); }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                                        InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty() || !(level.getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (crate.counter()) {
            if (new Counter(pos, state.getValue(FACING)).publicSide(player, hit)) {
                trade(crate, player, stack, hit);
                return ItemInteractionResult.CONSUME;
            }
            // The back side is the shop's own: goods go in as a deposit, nothing is paid.
            if (!ProduceCrateBlockEntity.sells(stack)) { refuse(player, pos, "wrong_item"); return ItemInteractionResult.FAIL; }
            if (!crate.put(stack)) { refuse(player, pos, "crate_full"); return ItemInteractionResult.FAIL; }
            stack.consume(1, player);
            level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .8f, 1f);
            return ItemInteractionResult.CONSUME;
        }
        if (!crate.put(stack)) return ItemInteractionResult.FAIL;
        stack.consume(1, player);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .8f, 1f);
        return ItemInteractionResult.CONSUME;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate)) return InteractionResult.PASS;
        if (crate.counter()) {
            if (!level.isClientSide) {
                if (!new Counter(pos, state.getValue(FACING)).publicSide(player, hit)) message(player, "back_take");
                else {
                    var offer = crate.counterOffer();
                    if (offer.isEmpty()) message(player, "empty");
                    else message(player, "offer", offer.getHoverName(), ProduceCrateBlockEntity.batch(offer));
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (crate.isEmpty()) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var taken = crate.takeOne();
        if (!player.getInventory().add(taken)) player.drop(taken, false);
        level.playSound(null, pos, SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.BLOCKS, .8f, 1f);
        return InteractionResult.CONSUME;
    }

    /** The public side of a counter crate: an emerald in hand buys one good, a good the counter handles is sold back for an emerald. */
    private static void trade(ProduceCrateBlockEntity crate, Player player, ItemStack held, BlockHitResult hit) {
        var level = player.level();
        var pos = crate.getBlockPos();
        var facing = crate.getBlockState().getValue(FACING);
        boolean buying = held.is(Items.EMERALD);
        if (!buying && !ProduceCrateBlockEntity.sells(held)) {
            refuse(player, pos, "wrong_item");
            return;
        }
        var sight = level.clip(new ClipContext(player.getEyePosition(), hit.getLocation(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (!player.canInteractWithBlock(pos, 0) || sight.getType() != HitResult.Type.MISS && !pos.equals(sight.getBlockPos())) {
            refuse(player, pos, "blocked");
            return;
        }
        // The keeper is whoever stands at a window whose run this crate belongs to (a crate two cells off the keeper's own still counts).
        var keeper = level.getEntitiesOfClass(HumanNpc.class, new AABB(pos).inflate(4, 3, 4),
                        npc -> npc.counterWindow() != null && npc.counterWindow().owns(pos, facing))
                .stream().findFirst().orElse(null);
        if (keeper == null) {
            refuse(player, pos, "unstaffed");
            return;
        }
        if (keeper.counterBusy() || !keeper.hasLineOfSight(player)) {
            refuse(player, pos, keeper.counterBusy() ? "busy" : "blocked");
            return;
        }
        var run = keeper.counterWindow().run(level, pos);
        var goods = (buying ? crate.counterOffer() : held).copyWithCount(1);
        int tillBefore = CounterTrade.till(run);
        int batch = ProduceCrateBlockEntity.batch(held);
        var result = buying ? CounterTrade.buy(run, player, held) : CounterTrade.buyBack(run, player, held);
        if (result != CounterTrade.Result.DONE) {
            refuse(player, pos, result.name().toLowerCase(Locale.ROOT), batch);
            return;
        }
        keeper.getLookControl().setLookAt(player, 30, 30);
        keeper.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, pos, buying ? SoundEvents.ITEM_FRAME_REMOVE_ITEM : SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, .8f, 1f);
        if (buying) {
            message(player, "sold", goods.getHoverName());
            GameplayLog.event(keeper, "shop", "action=counter_sale buyer={} crate={} goods={} count=1 emeralds=1 till={}->{}",
                    player.getUUID(), pos.toShortString(), goods.getItem(), tillBefore, CounterTrade.till(run));
        } else {
            message(player, "bought", batch, goods.getHoverName());
            GameplayLog.event(keeper, "shop", "action=counter_buyback seller={} crate={} goods={} count={} emeralds=1 till={}->{}",
                    player.getUUID(), pos.toShortString(), goods.getItem(), batch, tillBefore, CounterTrade.till(run));
        }
    }

    private static void refuse(Player player, BlockPos pos, String reason, Object... args) {
        message(player, reason, args);
        GameplayLog.limited(player, "shop", "counter." + reason, 40, "action=counter_refused crate={} reason={}", pos.toShortString(), reason);
    }

    private static void message(Player player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("human.mcyukkuri.counter." + key, args), true);
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof ProduceCrateBlockEntity crate)
            Containers.dropContents(level, pos, crate);
        super.onRemove(state, level, pos, replacement, moving);
    }
}
