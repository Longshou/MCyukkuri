package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.freezer.FreezerBlock;
import io.github.longshou.mcyukkuri.interaction.RestraintTableBlockEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.network.CarrierPayload;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** The player-facing way out of a basket: a sole occupant leaves at once, several ask which one. */
public final class CarrierRelease {
    /** Where the chosen occupant goes: in front of the player, onto the clicked cell, or onto a fixture. */
    public enum Target { FRONT, GROUND, TABLE, FREEZER }

    private CarrierRelease() {}

    /** A right-click with a non-empty basket; the target is used only when the occupant leaves. */
    public static void click(ServerLevel level, Player player, ItemStack stack, InteractionHand hand,
                             Target target, BlockPos pos, float yaw) {
        var world = YukkuriWorldData.get(level);
        CarrierItem.prepareBasket(world, stack);
        var tokens = CarrierContents.tokens(stack);
        if (tokens.isEmpty()) { blocked(player); return; }
        if (tokens.size() == 1) { release(level, player, stack, hand, target, pos, yaw, tokens.getFirst()); return; }
        if (player instanceof ServerPlayer server)
            PacketDistributor.sendToPlayer(server, menu(level, world, tokens, hand, target, pos));
    }

    /** The screen's answer, checked against the basket still in that hand. */
    public static InteractionResult choose(ServerPlayer player, InteractionHand hand, UUID token, Target target, BlockPos pos) {
        var stack = player.getItemInHand(hand);
        if (!CarrierContents.isBasket(stack) || stack.getCount() != 1) return InteractionResult.FAIL;
        if (!CarrierContents.tokens(stack).contains(token)) return InteractionResult.FAIL;
        var level = player.serverLevel();
        if (target != Target.FRONT && (!level.hasChunkAt(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 49)) return blocked(player);
        if (target == Target.FREEZER && !(level.getBlockState(pos).getBlock() instanceof FreezerBlock)) return blocked(player);
        return release(level, player, stack, hand, target, pos, player.getYRot(), token);
    }

    private static CarrierPayload.Menu menu(ServerLevel level, YukkuriWorldData world, List<UUID> tokens,
                                            InteractionHand hand, Target target, BlockPos pos) {
        var rows = new ArrayList<CarrierPayload.Row>(tokens.size());
        for (UUID token : tokens) {
            var snapshot = world.captiveSnapshot(token);
            var species = Component.translatable("entity." + snapshot.getString("id").replace(':', '.'));
            var stage = Component.translatable("stage.mcyukkuri." + CarrierContents.stage(snapshot));
            var name = snapshot.get("CustomName") instanceof StringTag
                    ? Component.Serializer.fromJson(snapshot.getString("CustomName"), level.registryAccess()) : null;
            rows.add(new CarrierPayload.Row(token, name, species, stage, Math.round(snapshot.getFloat("Health"))));
        }
        return new CarrierPayload.Menu(hand == InteractionHand.OFF_HAND, target, pos, rows);
    }

    private static InteractionResult release(ServerLevel level, Player player, ItemStack stack, InteractionHand hand,
                                             Target target, BlockPos pos, float yaw, UUID token) {
        return switch (target) {
            case FRONT -> ground(level, player, stack, token, player.blockPosition().relative(player.getDirection()), yaw);
            case GROUND -> ground(level, player, stack, token, pos, yaw);
            case TABLE -> table(level, player, stack, token, pos, yaw);
            case FREEZER -> FreezerBlock.store(level, player, stack, token) ? InteractionResult.CONSUME : InteractionResult.FAIL;
        };
    }

    private static InteractionResult ground(ServerLevel level, Player player, ItemStack stack, UUID token, BlockPos pos, float yaw) {
        if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
            GameplayLog.event(player, "carrier", "action=release result=refused token={} destination={} reason={}", token,
                    pos.toShortString(), !level.hasChunkAt(pos) ? "unloaded" : "unsupported_ground");
            return blocked(player);
        }
        int remaining = CarrierContents.tokens(stack).size() - 1;
        YukkuriEntity entity = YukkuriWorldData.get(level).releaseOnGround(token, level, Vec3.atBottomCenterOf(pos), yaw);
        if (entity == null) return blocked(player);
        // A sedated animal simply keeps sleeping, and a body frozen stiff is set down as stiff as it was lifted.
        if (!entity.life().sedated() && !entity.coldStiff()) { entity.playAction("basket_out"); entity.speak("basket_exit"); }
        CarrierContents.remove(stack, token);
        GameplayLog.event(player, "carrier", "action=release result=committed target={} token={} remaining={}",
                entity.getUUID(), token, remaining);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult table(ServerLevel level, Player player, ItemStack stack, UUID token, BlockPos pos, float yaw) {
        if (!(level.getBlockEntity(pos) instanceof RestraintTableBlockEntity table)) return blocked(player);
        int remaining = CarrierContents.tokens(stack).size() - 1;
        var result = table.placeFrom(player, stack, token, yaw);
        if (result.animal() == null) {
            GameplayLog.event(player, "carrier", "action=table_release result=refused token={} reason={}", token, result.refusal());
            return blocked(player);
        }
        GameplayLog.event(player, "carrier", "action=release result=committed target={} token={} remaining={} table={}",
                result.animal().getUUID(), token, remaining, pos);
        return InteractionResult.CONSUME;
    }

    private static InteractionResult blocked(Player player) {
        if (player != null) player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.blocked"), true);
        return InteractionResult.FAIL;
    }
}
