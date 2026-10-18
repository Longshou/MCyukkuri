package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
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
    /** Animals busy entering or refusing the basket (id to game time): repeated clicks neither restart the scene nor queue a second capture. */
    private static final Map<UUID, Long> SCENES = new HashMap<>();
    public static void resetScenes() { SCENES.clear(); }
    private static boolean scene(ServerLevel level, UUID id) {
        long now = level.getGameTime(); Long since = SCENES.get(id);
        if (since != null && now >= since && now - since < 24) return true;
        if (SCENES.size() > 64) SCENES.values().removeIf(t -> t > now || now - t >= 24);
        SCENES.put(id, now); return false;
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
            // A sedated animal cannot resist; otherwise only a pet, a lost animal or a wild one that trusts the player climbs in by itself.
            if (entity.life().sedated()) { if (!capture(level, player, hand, entity)) return InteractionResult.FAIL; }
            else if (willing(player, entity)) welcome(level, player, hand, entity);
            else refuse(level, player, entity);
        }
        return InteractionResult.sidedSuccess(player.level().isClientSide);
    }
    /** Trust at which a wild animal accepts the player (the same standing threshold as adoption): trust 10, fear under 25. */
    private static boolean willing(Player player, YukkuriEntity entity) {
        var commerce = io.github.longshou.mcyukkuri.human.HumanCommerce.data(entity); UUID id = player.getUUID();
        boolean known = io.github.longshou.mcyukkuri.human.HumanCommerce.ownedBy(entity, id) || commerce.getString("Use").equals("lost")
                || !commerce.hasUUID("Owner") && entity.life().personality().trust(id) >= 10 && entity.life().personality().fear(id) < 25;
        return known && !entity.isPanicking() && !entity.isOnFire() && entity.hurtTime == 0 && !entity.predator().active();
    }
    private static void welcome(ServerLevel level, Player player, InteractionHand hand, YukkuriEntity entity) {
        if (scene(level, entity.getUUID())) return;
        entity.playAction("basket_in"); entity.speak("basket_enter");
        ShopPerformance.schedule(level, age -> {
            if (age < 16) return false;
            SCENES.remove(entity.getUUID());
            if (entity.isAlive() && !entity.isRemoved() && !entity.isPanicking() && player.isAlive() && player.level() == level && player.distanceToSqr(entity) <= 36)
                capture(level, player, hand, entity);
            return true;
        });
    }
    private static void refuse(ServerLevel level, Player player, YukkuriEntity entity) {
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.refuse", entity.getName()), true);
        if (scene(level, entity.getUUID())) return;
        entity.playAction("struggle"); entity.showReaction("teary", "basket_refuse", 60);
        Vec3 from = player.position();
        ShopPerformance.schedule(level, age -> {
            if (age < 20) return false;
            SCENES.remove(entity.getUUID());
            if (!entity.isAlive()) return true;
            if (entity.isCommandPlaying() && "struggle".equals(entity.getAnimationId())) entity.playAction("idle");
            Vec3 away = entity.isPanicking() ? null : DefaultRandomPos.getPosAway(entity, 6, 3, from);
            if (away != null) entity.getNavigation().moveTo(away.x, away.y, away.z, 1.1);
            return true;
        });
    }
    /** Puts the animal into the empty carrier in the player's hand; false if the hand or the animal no longer qualifies. */
    private static boolean capture(ServerLevel level, Player player, InteractionHand hand, YukkuriEntity entity) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof CarrierItem) || stack.getCount() != 1 || token(stack) != null || !entity.isAlive()) return false;
        var previousCustody = io.github.longshou.mcyukkuri.human.HumanCommerce.data(entity).copy();
        if (!previousCustody.getString("Use").equals("lost"))
            io.github.longshou.mcyukkuri.human.HumanCommerce.assign(entity, player.getUUID(), "companion");
        UUID token = YukkuriWorldData.get(level).capture(entity);
        if (token == null) {
            io.github.longshou.mcyukkuri.human.HumanCommerce.set(entity, previousCustody); return false;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
            data.putUUID("CaptiveToken", token); data.putUUID("EntityUUID", entity.getUUID());
            data.putString("Variant", entity.getVariant().assetId());
        });
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.captured", entity.getName()), true);
        return true;
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
            if (!entity.life().sedated()) { entity.playAction("basket_out"); entity.speak("basket_exit"); } // a sedated animal simply keeps sleeping
            context.getItemInHand().remove(DataComponents.CUSTOM_DATA);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(token(stack) == null ? "care.mcyukkuri.carrier.empty" : "care.mcyukkuri.carrier.full"));
    }
}
