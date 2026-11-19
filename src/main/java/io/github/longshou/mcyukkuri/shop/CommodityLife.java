package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Stock shelf time and the shop's trade in core filling. */
public final class CommodityLife {
    private CommodityLife() {}

    // ---- days on the shelf ----------------------------------------------------------------------------------------------

    /** Whole game days this individual has been on the shelf, or -1 when it is not stock or the clock has not started. */
    public static int forSaleDays(YukkuriEntity y) {
        var tag = HumanCommerce.data(y);
        if (Custody.fromData(tag).role() != Custody.Role.STOCK || !tag.contains(ShopTrade.FOR_SALE_SINCE)) return -1;
        return (int) Math.max(0, (y.level().getGameTime() - tag.getLong(ShopTrade.FOR_SALE_SINCE)) / ShopStock.DAY);
    }

    // ---- core filling as raw material ------------------------------------------------------------------------------

    /** Emeralds the shop pays for one core filling. */
    private static int corePrice(ItemStack stack) {
        int sweetness = Math.clamp(FillingResource.data(stack).getInt("Sweetness"), 0, 100);
        return 4 + sweetness / 40;
    }
    /** A sneaking player offers the core filling in hand to a shopkeeper. Returns true when the offer was handled. */
    public static boolean sellCore(Player player, HumanNpc buyer) {
        ItemStack hand = player.getMainHandItem();
        if (buyer.job() != HumanNpc.Job.SHOPKEEPER || !FillingResource.isCore(hand)) return false;
        if (player instanceof ServerPlayer server) {
            var level = server.serverLevel();
            var site = ShopRegistry.get(level).of(buyer);
            if (site != null && ShopAccess.refuseService(level, site, server)) return true;
        }
        if (buyer.remainingTrades() <= 0 || buyer.resting()) {
            GameplayLog.event(player,"shop","action=sell_core result=refused buyer={} reason={}",buyer.getUUID(),buyer.remainingTrades()<=0?"trade_limit":"resting");
            player.displayClientMessage(Component.translatable("human.mcyukkuri.core.refused"), true); return true;
        }
        int price = corePrice(hand);
        hand.shrink(1); buyer.usedTrade();
        var pay = new ItemStack(Items.EMERALD, price);
        if (!player.getInventory().add(pay)) player.drop(pay, false);
        buyer.swing(InteractionHand.MAIN_HAND);
        player.level().playSound(null, buyer.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1f, 1f);
        player.displayClientMessage(Component.translatable("human.mcyukkuri.core.sold_shop", price), true);
        GameplayLog.event(player,"shop","action=sell_core result=committed buyer={} cores_used=1 emeralds_received={} trades_left={}",buyer.getUUID(),price,buyer.remainingTrades());
        return true;
    }
}
