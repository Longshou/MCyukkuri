package io.github.longshou.mcyukkuri.item;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.coordination.LeaseTable;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.social.Bonds;
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
    /** Click suppression follows game time; callback identity survives natural expiry until its scheduled age. */
    private static final int SCENE_WINDOW = 24;
    private static final LeaseTable<UUID, Object> SCENES = new LeaseTable<>(SCENE_WINDOW);
    private static final Map<UUID, Object> RUNNING = new HashMap<>();
    public static void resetScenes() { SCENES.clear(); RUNNING.clear(); }
    private static Object scene(ServerLevel level, UUID id) {
        long now = level.getGameTime();
        if (!SCENES.free(List.of(id), now)) return null;
        if (SCENES.size() > 64) SCENES.prune(now);
        var session = new Object();
        if (!SCENES.acquireAll(List.of(id), session, now + SCENE_WINDOW, now)) return null;
        RUNNING.put(id, session); return session;
    }
    private static boolean finishScene(UUID id, Object session) {
        if (!RUNNING.remove(id, session)) return false;
        SCENES.release(id, session); return true;
    }
    @Override public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        // Vanilla passes a disposable copy here in creative mode. Custody must reach the actual hand.
        stack = player.getItemInHand(hand);
        if (!stack.is(this) || stack.getCount() != 1) {
            GameplayLog.event(player,"carrier","action=capture result=refused reason=single_carrier_required count={}",stack.getCount());
            return InteractionResult.FAIL;
        }
        if (!(target instanceof YukkuriEntity entity) || token(stack) != null) return InteractionResult.PASS;
        if (player.level() instanceof ServerLevel level && player.distanceToSqr(entity) <= 16) {
            if (!HumanCommerce.mayCapture(player, entity)) {
                GameplayLog.event(player,"carrier","action=capture result=refused target={} reason={}",entity.getUUID(),
                        Custody.view(entity).hasPendingStaffRole()?"pending_staff":"owned_by_other");
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
        var commerce = Custody.view(entity); UUID id = player.getUUID();
        boolean known = HumanCommerce.ownedBy(entity, id) || commerce.role() == Custody.Role.LOST
                || commerce.owner() == null && Bonds.trustedPlayer(entity, id);
        return known && !entity.isPanicking() && !entity.isOnFire() && entity.hurtTime == 0 && !entity.predator().active();
    }
    private static void welcome(ServerLevel level, Player player, InteractionHand hand, YukkuriEntity entity) {
        var session = scene(level, entity.getUUID());
        if (session == null) {
            GameplayLog.limited(player,"carrier","capture_scene_busy",40,"action=capture result=refused target={} reason=scene_busy",entity.getUUID());
            return;
        }
        GameplayLog.event(player,"carrier","action=capture result=accepted target={} completion=pending",entity.getUUID());
        entity.playAction("basket_in"); entity.speak("basket_enter");
        ShopPerformance.schedule(level, age -> {
            if (age < 16) return false;
            if (!finishScene(entity.getUUID(), session)) return true;
            if (entity.isAlive() && !entity.isRemoved() && !entity.isPanicking() && player.isAlive() && player.level() == level && player.distanceToSqr(entity) <= 36)
                capture(level, player, hand, entity);
            else GameplayLog.event(player,"carrier","action=capture result=cancelled target={} reason={} alive={} removed={} panic={} player_alive={} same_level={} distance_sqr={}",
                    entity.getUUID(),!entity.isAlive()||entity.isRemoved()?"target_unavailable":entity.isPanicking()?"panic":!player.isAlive()?"player_dead":player.level()!=level?"different_level":"too_far",
                    entity.isAlive(),entity.isRemoved(),entity.isPanicking(),player.isAlive(),player.level()==level,player.distanceToSqr(entity));
            return true;
        });
    }
    private static void refuse(ServerLevel level, Player player, YukkuriEntity entity) {
        GameplayLog.limited(player,"carrier","unwilling",40,"action=capture result=refused target={} reason=unwilling panic={} fire={} hurt_ticks={} predator_active={} owner={} role={}",
                entity.getUUID(),entity.isPanicking(),entity.isOnFire(),entity.hurtTime,entity.predator().active(),Custody.view(entity).owner(),Custody.view(entity).role());
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.refuse", entity.getName()), true);
        var session = scene(level, entity.getUUID());
        if (session == null) return;
        entity.playAction("struggle"); entity.showReaction("teary", "basket_refuse", 60);
        Vec3 from = player.position();
        ShopPerformance.schedule(level, age -> {
            if (age < 20) return false;
            if (!finishScene(entity.getUUID(), session)) return true;
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
        if (!(stack.getItem() instanceof CarrierItem) || stack.getCount() != 1 || token(stack) != null || !entity.isAlive()) {
            GameplayLog.event(player,"carrier","action=capture result=cancelled target={} reason={}",entity.getUUID(),!entity.isAlive()?"target_dead":!(stack.getItem() instanceof CarrierItem)?"hand_changed":stack.getCount()!=1?"stack_changed":"carrier_full");
            return false;
        }
        var previousCustody = HumanCommerce.data(entity).copy();
        if (Custody.fromData(previousCustody).role() != Custody.Role.LOST)
            Custody.assign(entity, player, Custody.Role.COMPANION);
        UUID token = YukkuriWorldData.get(level).capture(entity);
        if (token == null) {
            Custody.restore(entity, previousCustody);
            GameplayLog.event(player,"carrier","action=capture result=rolled_back target={} reason=capture_failed",entity.getUUID());
            return false;
        }
        CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
            data.putUUID("CaptiveToken", token); data.putUUID("EntityUUID", entity.getUUID());
            data.putString("Variant", entity.getVariant().assetId());
        });
        player.displayClientMessage(Component.translatable("care.mcyukkuri.carrier.captured", entity.getName()), true);
        GameplayLog.event(player,"carrier","action=capture result=committed target={} token={}",entity.getUUID(),token);
        return true;
    }
    @Override public InteractionResult useOn(UseOnContext context) {
        UUID token = token(context.getItemInHand());
        if (token == null) return InteractionResult.PASS;
        if (context.getLevel() instanceof ServerLevel level) {
            var pos = context.getClickedPos().relative(context.getClickedFace());
            if (!level.hasChunkAt(pos) || !level.getBlockState(pos.below()).isSolidRender(level, pos.below())) {
                GameplayLog.event(context.getPlayer(),"carrier","action=release result=refused token={} destination={} reason={}",token,pos.toShortString(),!level.hasChunkAt(pos)?"unloaded":"unsupported_ground");
                return InteractionResult.FAIL;
            }
            YukkuriEntity entity = YukkuriWorldData.get(level).release(token, level, Vec3.atBottomCenterOf(pos), context.getRotation());
            if (entity == null) {
                if (context.getPlayer() != null) context.getPlayer().displayClientMessage(Component.translatable("care.mcyukkuri.carrier.blocked"), true);
                return InteractionResult.FAIL;
            }
            if (!entity.life().sedated()) { entity.playAction("basket_out"); entity.speak("basket_exit"); } // a sedated animal simply keeps sleeping
            context.getItemInHand().remove(DataComponents.CUSTOM_DATA);
            GameplayLog.event(context.getPlayer(),"carrier","action=release result=committed target={} token={}",entity.getUUID(),token);
        }
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(token(stack) == null ? "care.mcyukkuri.carrier.empty" : "care.mcyukkuri.carrier.full"));
    }
}
