package io.github.longshou.mcyukkuri.human;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.interaction.InteractionDispatch;
import io.github.longshou.mcyukkuri.item.CarrierContents;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import io.github.longshou.mcyukkuri.shop.ShopPricing;
import io.github.longshou.mcyukkuri.shop.ShopPerformance;
import io.github.longshou.mcyukkuri.shop.ShopRegistry;
import io.github.longshou.mcyukkuri.shop.ShopStaff;
import io.github.longshou.mcyukkuri.shop.ShopTrade;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.status.PunishmentReaction;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Counter contact settles one live intake through the real player's carrier and the transport ledger. */
public final class PlantReceiving {
    private static final String RECEIVER = "plant_receive";

    private PlantReceiving() {}

    static boolean receiver(HumanNpc staff) {
        return staff.job() == HumanNpc.Job.FACTORY && staff.plantSite() != null && staff.siteRole().equals(RECEIVER) && staff.isAlive();
    }

    static void equip(HumanNpc staff, ItemStack basket) {
        staff.setItemInHand(InteractionHand.MAIN_HAND, basket);
        staff.setDropChance(EquipmentSlot.MAINHAND, 2);
    }

    static void dropOnDeath(ServerLevel level, HumanNpc staff) {
        var basket = staff.getMainHandItem();
        if (!staff.siteRole().equals(RECEIVER) || !CarrierContents.isBasket(basket)) return;
        var drop = ShopTrade.drop(level, null, staff.position(), basket);
        if (drop == null) return;
        staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        GameplayLog.event(staff, "factory", "action=receive_transfer result=dropped token={} item={} reason=staff_died",
                CarrierContents.singleToken(basket), drop.getUUID());
    }

    private static boolean eligible(YukkuriEntity animal) {
        return animal.status().identity().free() && !animal.profile().aquatic() && !animal.profile().dangerToYukkuri();
    }

    /** In the marked booth a clerk can help load a free animal without domesticating it. */
    public static boolean assists(ServerLevel level, YukkuriEntity animal) {
        if (!eligible(animal) || animal.body().dying()
                || !animal.onGround() || animal.isVehicle() || animal.operations().active()
                || InteractionDispatch.contactRefusal(animal) != null || ShopRegistry.get(level).protectedAt(level, animal.blockPosition()))
            return false;
        // The staff post is on the far side of the booth; its marked floor bounds decide where intake can happen.
        var staff = level.getEntitiesOfClass(HumanNpc.class, animal.getBoundingBox().inflate(8), PlantReceiving::receiver);
        for (var clerk : staff) {
            var layout = PlantSite.layout(level, clerk.plantSite());
            if (layout != null && layout.booth().isInside(animal.blockPosition())) return true;
        }
        return false;
    }

    static InteractionResult interact(Player player, HumanNpc staff, InteractionHand hand) {
        var basket = player.getItemInHand(hand);
        if (!(staff.level() instanceof ServerLevel level) || !staff.siteRole().equals(RECEIVER))
            return InteractionResult.PASS;
        var staffBasket = staff.getMainHandItem();
        var carried = CarrierContents.singleToken(staffBasket);
        if (!CarrierContents.isBasket(basket) && !(basket.isEmpty() && carried != null)) return InteractionResult.PASS;
        if (!receiver(staff) || !player.isAlive() || player.isSpectator()
                || player.distanceToSqr(staff) > player.entityInteractionRange() * player.entityInteractionRange()
                || !player.hasLineOfSight(staff)) return refuse(staff, player, null, "reach");
        if (basket.isEmpty()) {
            staff.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            player.setItemInHand(hand, staffBasket);
            GameplayLog.event(staff, "factory", "action=receive_transfer result=taken player={} token={}", player.getUUID(), carried);
            return InteractionResult.CONSUME;
        }
        var token = CarrierContents.singleToken(basket);
        if (staffBasket.isEmpty() && basket.getCount() == 1 && CarrierContents.tokens(basket).isEmpty()) {
            equip(staff, basket);
            player.setItemInHand(hand, ItemStack.EMPTY);
            return InteractionResult.CONSUME;
        }
        if (basket.getCount() != 1 || token == null) return refuse(staff, player, null, "one_animal");
        var world = YukkuriWorldData.get(level);
        var snapshot = world.captiveSnapshot(token);
        var identity = world.captiveIdentity(token);
        if (snapshot.isEmpty() || world.freezer.contains(token)) return refuse(staff, player, identity, "not_live");
        var custody = Custody.fromSnapshot(snapshot);
        if (custody.hasPendingStaffRole() || custody.role() == Custody.Role.STOCK || custody.role() == Custody.Role.SHOP_NURSERY
                || custody.role() == Custody.Role.PLANT_STOCK) return refuse(staff, player, identity, "protected");
        if (custody.owner() != null || custody.role() != Custody.Role.NONE && custody.role() != Custody.Role.LOST)
            return refuse(staff, player, identity, "owned");
        UUID site = staff.plantSite();
        var layout = PlantSite.layout(level, site);
        if (layout == null) return refuse(staff, player, identity, "unavailable");

        var floor = layout.boothFloor().stream().filter(cell -> visibleFloor(level, player, staff, cell)).toList();
        var released = world.releaseOnGround(token, level, staff.position(), player.getYRot() + 180, floor, animal -> {
            if (animal.body().dying()) return "not_live";
            if (!eligible(animal)) return "land";
            return paymentSlot(player, new ItemStack(Items.EMERALD, ShopPricing.rawPrice(animal))) < 0 ? "inventory" : null;
        });
        var animal = released.animal();
        if (animal == null) {
            var reason = switch (released.refusal()) {
                case "not_live", "snapshot_not_live_yukkuri", "identity_already_loaded" -> "not_live";
                case "land", "inventory" -> released.refusal();
                case "entity_add_rejected" -> "unavailable";
                default -> "no_space";
            };
            return refuse(staff, player, identity, reason);
        }

        Custody.assign(animal, site, Custody.OwnerKind.NPC, Custody.Role.PLANT_STOCK);
        CarrierContents.remove(basket, token);
        int price = ShopPricing.rawPrice(animal);
        var payment = new ItemStack(Items.EMERALD, price);
        player.getInventory().add(paymentSlot(player, payment), payment);
        staff.swing(InteractionHand.OFF_HAND);
        if (!animal.life().sedated()) showOpening(level, animal, staff);
        player.displayClientMessage(Component.translatable("factory.mcyukkuri.receive.paid", animal.getName(), price), true);
        GameplayLog.event(animal, "factory", "action=receive_live result=committed player={} staff={} site={} token={} carrier_count=1 occupants=1->0 emeralds_paid={}",
                player.getUUID(), staff.getUUID(), site, token, price);
        return InteractionResult.CONSUME;
    }

    private static boolean visibleFloor(ServerLevel level, Player player, HumanNpc staff, BlockPos cell) {
        var point = Vec3.atBottomCenterOf(cell).add(0, .2, 0);
        double reach = player.entityInteractionRange();
        return player.distanceToSqr(point) <= reach * reach && ShopStaff.reaches(staff, point, cell)
                && level.clip(new ClipContext(player.getEyePosition(), point, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
    }

    private static void showOpening(ServerLevel level, YukkuriEntity animal, HumanNpc staff) {
        animal.playAction(YukkuriAction.BASKET_OUT.id());
        // Presentation only. Payment and custody have already settled.
        ShopPerformance.schedule(level, animal.getUUID(), age -> {
            if (level.getEntity(animal.getUUID()) != animal || !animal.isAlive() || animal.life().sedated()) return true;
            if (age < YukkuriAction.BASKET_OUT.durationTicks()) return false;
            var response = PunishmentReaction.handover(animal, staff, "factory_receive_insult");
            GameplayLog.event(animal, "factory", "action=receive_reaction staff={} response={} self_control={} unease={}",
                    staff.getUUID(), response, animal.life().personality().selfControl(), animal.mind().unease());
            return true;
        });
    }

    private static int paymentSlot(Player player, ItemStack payment) {
        var inventory = player.getInventory();
        int slot = inventory.getSlotWithRemainingSpace(payment);
        if (slot >= 0 && inventory.getItem(slot).getCount() + payment.getCount() <= payment.getMaxStackSize()) return slot;
        return inventory.getFreeSlot();
    }

    private static InteractionResult refuse(HumanNpc staff, Player player, UUID animal, String reason) {
        player.displayClientMessage(Component.translatable("factory.mcyukkuri.receive.refused." + reason), true);
        GameplayLog.limited(staff, "factory", "receive:" + player.getUUID() + ":" + reason, 20,
                "action=receive_live result=refused player={} target={} site={} reason={}", player.getUUID(), animal, staff.plantSite(), reason);
        return InteractionResult.FAIL;
    }
}
