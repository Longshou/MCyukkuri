package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.status.GameplayLog;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The grim side of the pet trade (komicolle 11518786, docs/FEATURES.md): factory parent animals wear down, a failed exam is punished and
 * core filling is a valuable raw material; the days a stock animal has waited on the shelf are counted here. Everything scales with
 * {@link HumanWorld#COMMODITY_HARSHNESS} (0 off, 1 mild default, 2 harsh, 3 brutal). Server only; no client class is touched.
 */
public final class CommodityLife {
    private CommodityLife() {}
    /** Unease ceiling these pressures push towards; the mind breaks at 95, and the trade wants a working animal, not a frenzied one. */
    public static final float UNEASE_CAP = 80;

    public static int harshness() { try { return HumanWorld.COMMODITY_HARSHNESS.get(); } catch (RuntimeException e) { return 1; } }

    /** Unease added without ever pushing the mind past the cap (a cap already exceeded is left alone). */
    public static void pressure(YukkuriEntity y, float amount, String cause) {
        float room = UNEASE_CAP - y.mind().unease();
        if (room > 0) y.mind().add(Math.min(amount, room), cause);
    }

    // ---- days on the shelf ----------------------------------------------------------------------------------------------

    /** Whole game days this individual has been on the shelf, or -1 when it is not stock or the clock has not started. */
    public static int forSaleDays(YukkuriEntity y) {
        var tag = HumanCommerce.data(y);
        if (Custody.fromData(tag).role() != Custody.Role.STOCK || !tag.contains(ShopTrade.FOR_SALE_SINCE)) return -1;
        return (int) Math.max(0, (y.level().getGameTime() - tag.getLong(ShopTrade.FOR_SALE_SINCE)) / ShopStock.DAY);
    }

    // ---- core filling as raw material ------------------------------------------------------------------------------

    /** Emeralds a buyer pays for one core filling: the factory pays more than the shop. */
    public static int corePrice(ItemStack stack, boolean factory) {
        int sweetness = Math.clamp(FillingResource.data(stack).getInt("Sweetness"), 0, 100);
        return factory ? 6 + sweetness / 25 : 4 + sweetness / 40;
    }
    /** A sneaking player offers the core filling in hand to a shopkeeper or a factory worker. Returns true when the offer was handled. */
    public static boolean sellCore(Player player, HumanNpc buyer) {
        ItemStack hand = player.getMainHandItem();
        boolean factory = buyer.job() == HumanNpc.Job.FACTORY, shop = buyer.job() == HumanNpc.Job.SHOPKEEPER;
        if (!(factory || shop) || !FillingResource.isCore(hand)) return false;
        if (shop && player instanceof ServerPlayer server) {
            var level = server.serverLevel();
            var site = ShopRegistry.get(level).of(buyer);
            if (site != null && ShopAccess.refuseService(level, site, server)) return true;
        }
        if (buyer.remainingTrades() <= 0 || buyer.resting()) {
            GameplayLog.event(player,"shop","action=sell_core result=refused buyer={} reason={}",buyer.getUUID(),buyer.remainingTrades()<=0?"trade_limit":"resting");
            player.displayClientMessage(Component.translatable("human.mcyukkuri.core.refused"), true); return true;
        }
        int price = corePrice(hand, factory);
        hand.shrink(1); buyer.usedTrade();
        if (factory) buyer.production().receiveCore();
        var pay = new ItemStack(Items.EMERALD, price);
        if (!player.getInventory().add(pay)) player.drop(pay, false);
        buyer.swing(InteractionHand.MAIN_HAND);
        player.level().playSound(null, buyer.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1f, 1f);
        player.displayClientMessage(Component.translatable(factory ? "human.mcyukkuri.core.sold_factory" : "human.mcyukkuri.core.sold_shop", price), true);
        GameplayLog.event(player,"shop","action=sell_core result=committed buyer={} cores_used=1 emeralds_received={} trades_left={}",buyer.getUUID(),price,buyer.remainingTrades());
        return true;
    }
}
