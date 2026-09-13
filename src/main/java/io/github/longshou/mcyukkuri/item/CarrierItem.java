package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.Vec3;

public final class CarrierItem extends Item {
    public CarrierItem(Properties properties) { super(properties); }
    public static UUID token(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.hasUUID("CaptiveToken") ? data.getUUID("CaptiveToken") : null;
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // Vanilla passes a disposable copy here in creative mode. Custody must reach the actual hand.
        stack = player.getItemInHand(hand);
        if (!stack.is(this) || stack.getCount() != 1) return InteractionResult.FAIL;
        if (!(target instanceof YukkuriEntity entity) || token(stack) != null) return InteractionResult.PASS;
        if (player.level() instanceof ServerLevel level && player.distanceToSqr(entity) <= 16) {
            if (!io.github.longshou.mcyukkuri.human.HumanCommerce.mayCapture(player, entity)) {
                player.displayClientMessage(Component.translatable("human.mcyukkuri.owned"), true); return InteractionResult.FAIL;
            }
            var previousCustody = io.github.longshou.mcyukkuri.human.HumanCommerce.data(entity).copy();
            if (!previousCustody.getString("Use").equals("lost"))
                io.github.longshou.mcyukkuri.human.HumanCommerce.assign(entity, player.getUUID(), "companion");
            UUID token = YukkuriWorldData.get(level).capture(entity);
            if (token == null) {
                io.github.longshou.mcyukkuri.human.HumanCommerce.set(entity, previousCustody); return InteractionResult.FAIL;
            }
            CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
                data.putUUID("CaptiveToken", token); data.putUUID("EntityUUID", entity.getUUID());
                data.putString("Variant", entity.getVariant().assetId());
            });
            player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.captured", entity.getName()), true);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        UUID token = token(context.getItemInHand());
        if (token == null) return InteractionResult.PASS;
        if (context.getLevel() instanceof ServerLevel level) {
            var pos = context.getClickedPos().relative(context.getClickedFace());
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) return InteractionResult.FAIL;
            YukkuriEntity entity = YukkuriWorldData.get(level).release(token, level, Vec3.atBottomCenterOf(pos), context.getRotation());
            if (entity == null) {
                if (context.getPlayer() != null) context.getPlayer().displayClientMessage(Component.translatable("care.mcyukkuri.carrier.blocked"), true);
                return InteractionResult.FAIL;
            }
            context.getItemInHand().remove(DataComponents.CUSTOM_DATA);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(token(stack) == null ? "care.mcyukkuri.carrier.empty" : "care.mcyukkuri.carrier.full"));
    }
}
