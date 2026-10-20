package io.github.longshou.mcyukkuri.freezer;

import io.github.longshou.mcyukkuri.item.CarrierItem;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.notebook.SurvivalProgress;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;

/** Dedicated survival access point. Removing the cabinet never deletes the personal vault. */
public final class FreezerBlock extends HorizontalDirectionalBlock {
    public FreezerBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return simpleCodec(FreezerBlock::new); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ModItems.CARRIER.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level instanceof ServerLevel server) transfer(server, player, stack);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    public static boolean transfer(ServerLevel level, Player player, ItemStack stack) {
        if (!stack.is(ModItems.CARRIER.get()) || stack.getCount() != 1) {
            GameplayLog.event(player, "freezer", "result=refused reason=single_carrier_required count={}", stack.getCount());
            return false;
        }
        var world = YukkuriWorldData.get(level); var token = CarrierItem.token(stack);
        int before = world.freezer.count(player.getUUID());
        if (token != null) {
            if (!world.freezer.store(player.getUUID(), token)) {
                String reason = before >= FreezerStorage.CAPACITY ? "full" : world.freezer.contains(token) ? "already_stored" : "token_missing";
                GameplayLog.event(player, "freezer", "action=store result=refused reason={} token={} occupied={}", reason, token, before);
                player.displayClientMessage(Component.translatable("freezer.mcyukkuri.store_failed"), true); return false;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
                data.remove("CaptiveToken"); data.remove("EntityUUID"); data.remove("Variant");
            });
            SurvivalProgress.award(player, "cold_storage");
            GameplayLog.event(player, "freezer", "action=store result=committed retired_token={} occupied={}->{}", token, before, world.freezer.count(player.getUUID()));
        } else {
            token = world.freezer.retrieve(player.getUUID());
            if (token == null) {
                GameplayLog.event(player, "freezer", "action=retrieve result=refused reason={} occupied={}", before == 0 ? "empty" : "token_rotation_failed", before);
                player.displayClientMessage(Component.translatable("freezer.mcyukkuri.empty"), true); return false;
            }
            var newToken = token; var snapshot = world.captiveSnapshot(token);
            CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
                data.putUUID("CaptiveToken", newToken);
                if (snapshot.hasUUID("UUID")) data.putUUID("EntityUUID", snapshot.getUUID("UUID"));
                data.putString("Variant", snapshot.getString("id").replace("mcyukkuri:", ""));
            });
            SurvivalProgress.award(player, "retrieve");
            GameplayLog.event(player, "freezer", "action=retrieve result=committed token={} target={} occupied={}->{}", token,
                    snapshot.hasUUID("UUID") ? snapshot.getUUID("UUID") : null, before, world.freezer.count(player.getUUID()));
        }
        player.displayClientMessage(Component.translatable("freezer.mcyukkuri.count", world.freezer.count(player.getUUID()), FreezerStorage.CAPACITY), true);
        return true;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server) {
            var vault = YukkuriWorldData.get(server).freezer;
            player.displayClientMessage(Component.translatable("freezer.mcyukkuri.count", vault.count(player.getUUID()), FreezerStorage.CAPACITY), false);
            for (var captive : vault.occupants(player.getUUID())) {
                String id = captive.getString("id").replace(':', '.');
                player.displayClientMessage(Component.translatable("entity." + id), false);
            }
            player.displayClientMessage(Component.translatable("freezer.mcyukkuri.instructions"), false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
