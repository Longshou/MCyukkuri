package io.github.longshou.mcyukkuri.shop;

import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.human.Custody;
import io.github.longshou.mcyukkuri.human.HumanCommerce;
import io.github.longshou.mcyukkuri.human.HumanNpc;
import io.github.longshou.mcyukkuri.human.HumanWorld;
import io.github.longshou.mcyukkuri.life.YukkuriWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The grim side of the pet trade (komicolle 11518786, docs/PET_SHOP.md section 13): stock nobody buys is fed less and less and finally sent to a
 * factory, factory parent animals wear down, a failed exam is punished and core filling is a valuable raw material. Everything scales with
 * {@link HumanWorld#COMMODITY_HARSHNESS} (0 off, 1 mild default, 2 harsh, 3 brutal). Server only; no client class is touched.
 */
public final class CommodityLife {
    private CommodityLife() {}
    /** Unease ceiling these pressures push towards; the mind breaks at 95, and the trade wants a working animal, not a frenzied one. */
    public static final float UNEASE_CAP = 80;

    public static int harshness() { try { return HumanWorld.COMMODITY_HARSHNESS.get(); } catch (RuntimeException e) { return 1; } }
    private static int recallDays() { try { return HumanWorld.STALE_RECALL_DAYS.get(); } catch (RuntimeException e) { return 20; } }

    /** Unease added without ever pushing the mind past the cap (a cap already exceeded is left alone). */
    public static void pressure(YukkuriEntity y, float amount, String cause) {
        float room = UNEASE_CAP - y.mind().unease();
        if (room > 0) y.mind().add(Math.min(amount, room), cause);
    }

    // ---- unsold stock ----------------------------------------------------------------------------------------------

    /** Whole game days this individual has been on the shelf, or -1 when it is not stock or the clock has not started. */
    public static int forSaleDays(YukkuriEntity y) {
        var tag = HumanCommerce.data(y);
        if (Custody.fromData(tag).role() != Custody.Role.STOCK || !tag.contains("ForSaleSince")) return -1;
        return (int) Math.max(0, (y.level().getGameTime() - tag.getLong("ForSaleSince")) / ShopStock.DAY);
    }
    /** Historical ration-cut count. It remains readable from old saves, but no longer changes care or mood. */
    public static int rationCuts(YukkuriEntity y) { return HumanCommerce.data(y).getInt("StaleCuts"); }

    /** Every 200 ticks per shopkeeper: start the unsold clock and retain the independent factory-recall rule. */
    public static void tickStock(HumanNpc shop) {
        if (!(shop.level() instanceof ServerLevel level) || harshness() == 0) return;
        long now = level.getGameTime(); int recall = recallDays();
        YukkuriEntity oldest = null; int oldestDays = -1;
        for (var y : level.getEntitiesOfClass(YukkuriEntity.class, shop.getBoundingBox().inflate(48), a -> a.isAlive() && ShopMenu.stockOf(a, shop.getUUID()))) {
            var tag = HumanCommerce.data(y);
            if (!tag.contains("ForSaleSince")) { tag = tag.copy(); tag.putLong("ForSaleSince", now); HumanCommerce.set(y, tag); continue; }
            if (y.pet().busy() || y.isPassenger()) continue;
            int days = (int) Math.max(0, (now - tag.getLong("ForSaleSince")) / ShopStock.DAY);
            if (days >= recall && days > oldestDays) { oldest = y; oldestDays = days; }
        }
        if (oldest != null) recall(level, shop, oldest);
    }

    /** The keeper carries the animal out in a basket (or, with no keeper, the shopkeeper packs it away himself); the factory collects it later. */
    private static void recall(ServerLevel level, HumanNpc shop, YukkuriEntity y) {
        var data = ShopDeliveries.get(level);
        var factory = data.nearestFactory(level, shop.blockPosition());
        if (factory == null || data.recallsFor(factory.getKey()) >= 2) return; // nobody takes it: it stays on the shelf, hungry
        if (ShopCourier.hasKeeper(level, shop)) {
            var keeper = ShopCourier.idleKeeper(level, shop);
            if (keeper != null) ShopCourier.recall(level, keeper, shop, y, factory.getKey());
            return;
        }
        if (y.pet().busy()) return;
        var token = YukkuriWorldData.get(level).capture(y);
        if (token == null) return;
        shop.swing(InteractionHand.MAIN_HAND);
        data.addRecall(new ShopDeliveries.Recall(token, shop.getUUID(), factory.getKey(), level.getGameTime()));
    }

    // ---- failed exams ----------------------------------------------------------------------------------------------

    /** The examiner's punishment after a failed or demoted exam; returns the lang key of the log line, or null when nothing was done. */
    public static String disciplineExam(YukkuriEntity candidate, HumanNpc examiner) {
        int h = harshness();
        if (h == 0 || !candidate.isAlive()) return null;
        pressure(candidate, 10f * h, "exam_discipline");
        float meal = Math.min(candidate.life().nutrition(), 10f * h); // a meal is held back
        if (meal > 0) candidate.life().spendNutrition(meal);
        if (h >= 2) candidate.life().applyEnvironment(0, 0, 6 * h);
        if (examiner != null) examiner.swing(InteractionHand.MAIN_HAND);
        return "exam.mcyukkuri.log.discipline";
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
        if (buyer.remainingTrades() <= 0 || buyer.resting()) { player.displayClientMessage(Component.translatable("human.mcyukkuri.core.refused"), true); return true; }
        int price = corePrice(hand, factory);
        hand.shrink(1); buyer.usedTrade();
        if (factory) buyer.production().receiveCore();
        var pay = new ItemStack(Items.EMERALD, price);
        if (!player.getInventory().add(pay)) player.drop(pay, false);
        buyer.swing(InteractionHand.MAIN_HAND);
        player.level().playSound(null, buyer.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1f, 1f);
        player.displayClientMessage(Component.translatable(factory ? "human.mcyukkuri.core.sold_factory" : "human.mcyukkuri.core.sold_shop", price), true);
        return true;
    }
}
