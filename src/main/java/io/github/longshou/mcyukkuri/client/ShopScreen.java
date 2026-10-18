package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import io.github.longshou.mcyukkuri.shop.ShopOrders;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import static io.github.longshou.mcyukkuri.client.GuiKit.*;

/** Tabbed shopkeeper screen: live stock, goods, buyback, consultation. Button and data slot ids are documented on ShopMenu (slot 56: highlighted live row, 1-4, 0 none). */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final int ROW = 57, ROW_X = 8, ROW_W = 256, ROW_H = 56, TOP = 32, FOOT = 224, LOW_HEALTH = 6, LOW_NUTRITION = 30;
    private final Button[] actions = new Button[ShopMenu.ButtonId.COUNT];
    public ShopScreen(ShopMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 250; }
    @Override protected void init() {
        super.init();
        int[] icons = {ICON_LIVE, ICON_GOODS, ICON_BUYBACK, ICON_CONSULT, ICON_PROFILE};
        for (int i = 0; i < 5; i++) {
            int tabId = i == 4 ? ShopMenu.ButtonId.ORDERS_TAB : i;
            var tab = addRenderableWidget(new GuiKit.Tab(leftPos + 8 + i * 65, topPos + 6, 64, 22, Component.translatable("shop.mcyukkuri.tab." + i), icons[i], b -> press(tabId)));
            if (i < 4) actions[i] = tab; else actions[ShopMenu.ButtonId.ORDERS_TAB] = tab;
        }
        for (int i = 0; i < ShopMenu.GOODS_ROWS; i++) add(ShopMenu.ButtonId.BUY_GOODS_BASE + i, 36, 52 + i * 34, 290, Component.empty());
        for (int i = 0; i < ShopOrders.PER_DAY; i++) add(ShopMenu.ButtonId.DELIVER_ORDER_BASE + i, 270, 49 + i * 34, 62, 22, Component.translatable("shop.mcyukkuri.order.deliver"));
        for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
            add(ShopMenu.ButtonId.BUY_LIVE_BASE + i, 270, TOP + i * ROW + 3, 62, 16, Component.translatable("shop.mcyukkuri.buy"));
            add(ShopMenu.ButtonId.LOCATE_BASE + i, 270, TOP + i * ROW + 20, 62, 16, Component.translatable("shop.mcyukkuri.locate"));
            add(ShopMenu.ButtonId.ABOUT_BASE + i, 270, TOP + i * ROW + 37, 62, 16, Component.translatable("shop.mcyukkuri.about"));
        }
        add(ShopMenu.ButtonId.PREVIOUS_PAGE, 8, FOOT, 40, Component.literal("<")); add(ShopMenu.ButtonId.NEXT_PAGE, 52, FOOT, 40, Component.literal(">"));
        add(ShopMenu.ButtonId.PREVIOUS_SUBJECT, 8, FOOT, 40, Component.literal("<")); add(ShopMenu.ButtonId.NEXT_SUBJECT, 52, FOOT, 40, Component.literal(">"));
        add(ShopMenu.ButtonId.SELL, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.sell"));
        add(ShopMenu.ButtonId.FORECAST, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.forecast"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 272, topPos + FOOT, 60, 20).build());
    }
    private void press(int id) { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); }
    private void add(int id, int x, int y, int w, Component title) { add(id, x, y, w, 20, title); }
    private void add(int id, int x, int y, int w, int h, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> press(id)).bounds(leftPos + x, topPos + y, w, h).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        int tab = menu.value(ShopMenu.Data.TAB); boolean day = menu.value(ShopMenu.Data.DAYTIME) != 0, own = menu.value(ShopMenu.Data.SUBJECT_COUNT) > 0;
        actions[ShopMenu.ButtonId.ORDERS_TAB].active = tab != ShopMenu.ORDERS;
        for (int i = 0; i < ShopOrders.PER_DAY; i++) {
            actions[ShopMenu.ButtonId.DELIVER_ORDER_BASE + i].visible = tab == ShopMenu.ORDERS;
            actions[ShopMenu.ButtonId.DELIVER_ORDER_BASE + i].active = day && (menu.value(ShopMenu.Data.ORDERS_DONE) >> i & 1) == 0 && menu.value(ShopMenu.Data.ORDER_BASE + i) > 0;
        }
        for (int i = 0; i < ShopMenu.GOODS_ROWS; i++) {
            actions[i].active = tab != i;
            if (i < ShopMenu.PAGE_ROWS) {
                boolean has = menu.value(ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE) > 0, locked = (menu.value(ShopMenu.LiveRow.FLAGS_BASE + i * ShopMenu.LiveRow.STRIDE) & ShopMenu.Flags.LOCKED) != 0;
                actions[ShopMenu.ButtonId.BUY_LIVE_BASE + i].visible = actions[ShopMenu.ButtonId.LOCATE_BASE + i].visible = actions[ShopMenu.ButtonId.ABOUT_BASE + i].visible = tab == ShopMenu.LIVE;
                actions[ShopMenu.ButtonId.BUY_LIVE_BASE + i].active = has && day && !locked; actions[ShopMenu.ButtonId.LOCATE_BASE + i].active = actions[ShopMenu.ButtonId.ABOUT_BASE + i].active = has;
                if (has) actions[ShopMenu.ButtonId.BUY_LIVE_BASE + i].setMessage(Component.translatable("shop.mcyukkuri.buy_price", menu.value(ShopMenu.LiveRow.PRICE_BASE + i * ShopMenu.LiveRow.STRIDE)));
            }
            var stack = ShopMenu.goodsStack(menu.value(ShopMenu.Data.PAGE), i); int price = ShopMenu.goodsPrice(menu.value(ShopMenu.Data.PAGE), i);
            actions[ShopMenu.ButtonId.BUY_GOODS_BASE + i].visible = tab == ShopMenu.GOODS && !stack.isEmpty();
            int left = menu.value(ShopMenu.Data.GOODS_STOCK_BASE + i), need = menu.value(ShopMenu.Data.GOODS_UNLOCK_BASE + i), standing = menu.value(ShopMenu.Data.STANDING);
            boolean soldOut = left <= 0, locked = standing < need;
            actions[ShopMenu.ButtonId.BUY_GOODS_BASE + i].active = day && menu.value(ShopMenu.Data.TRADES_LEFT) > 0 && !soldOut && !locked;
            if (!stack.isEmpty()) actions[ShopMenu.ButtonId.BUY_GOODS_BASE + i].setMessage(soldOut ? Component.translatable("shop.mcyukkuri.sold_out_label", stack.getHoverName())
                    : locked ? Component.translatable("shop.mcyukkuri.locked_label." + need, stack.getHoverName())
                    : Component.translatable("shop.mcyukkuri.buy_stock", stack.getHoverName(), stack.getCount(), price, left));
        }
        actions[ShopMenu.ButtonId.PREVIOUS_PAGE].visible = actions[ShopMenu.ButtonId.NEXT_PAGE].visible = tab <= ShopMenu.GOODS; actions[ShopMenu.ButtonId.PREVIOUS_PAGE].active = actions[ShopMenu.ButtonId.NEXT_PAGE].active = menu.value(ShopMenu.Data.PAGE_COUNT) > 1;
        actions[ShopMenu.ButtonId.PREVIOUS_SUBJECT].visible = actions[ShopMenu.ButtonId.NEXT_SUBJECT].visible = tab == ShopMenu.BUYBACK || tab == ShopMenu.CONSULT; actions[ShopMenu.ButtonId.PREVIOUS_SUBJECT].active = actions[ShopMenu.ButtonId.NEXT_SUBJECT].active = menu.value(ShopMenu.Data.SUBJECT_COUNT) > 1;
        actions[ShopMenu.ButtonId.SELL].visible = tab == ShopMenu.BUYBACK; actions[ShopMenu.ButtonId.SELL].active = own && day && menu.value(ShopMenu.Data.BUYBACK_REFUSAL) == 0;
        actions[ShopMenu.ButtonId.FORECAST].visible = tab == ShopMenu.CONSULT; actions[ShopMenu.ButtonId.FORECAST].active = own && day && menu.value(ShopMenu.Data.FORECAST_BOUGHT) == 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        int tab = menu.value(ShopMenu.Data.TAB);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) card(g, leftPos + ROW_X, topPos + TOP + i * ROW, ROW_W, ROW_H, menu.value(ShopMenu.Data.FOCUS_ROW) == i + 1 && menu.value(ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE) > 0);
        } else if (tab == ShopMenu.GOODS) {
            for (int i = 0; i < ShopMenu.GOODS_ROWS; i++) {
                var stack = ShopMenu.goodsStack(menu.value(ShopMenu.Data.PAGE), i); if (stack.isEmpty()) continue;
                card(g, leftPos + 8, topPos + 46 + i * 34, 324, 30, false);
                g.renderItem(stack, leftPos + 14, topPos + 53 + i * 34);
            }
        } else if (tab == ShopMenu.ORDERS) {
            for (int i = 0; i < ShopOrders.PER_DAY; i++) {
                card(g, leftPos + 8, topPos + 46 + i * 34, 324, 30, false);
                if (menu.value(ShopMenu.Data.ORDER_BASE + i) > 0) g.renderItem(ShopOrders.icon(menu.value(ShopMenu.Data.ORDER_BASE + i) - 1), leftPos + 14, topPos + 53 + i * 34);
            }
        } else card(g, leftPos + 8, topPos + 32, 324, menu.value(ShopMenu.Data.SUBJECT_COUNT) == 0 ? 28 : tab == ShopMenu.BUYBACK ? 120 : 178, false);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 16, y, color, false); }
    /** Short species name (no Yukkuri suffix) when the lang file has one, else the entity name. */
    private static Component species(int v) {
        if (v <= 0 || v > YukkuriVariant.values().length) return Component.empty();
        String id = YukkuriVariant.values()[v - 1].assetId();
        return Component.translatable(I18n.exists("shop.mcyukkuri.sp." + id) ? "shop.mcyukkuri.sp." + id : "entity.mcyukkuri." + id);
    }
    private static Component grade(int flags) {
        var c = GuiKit.gradeName(flags & ShopMenu.Flags.GRADE_MASK);
        return (flags & ShopMenu.Flags.EXPIRED) != 0 ? c.append(Component.translatable("shop.mcyukkuri.expired")) : c;
    }
    private static MutableComponent tags(int packed) {
        var c = Component.translatable("shop.mcyukkuri.tag." + (packed & 15)); int second = packed >> 4 & 15;
        return second == 15 ? c : c.append(" / ").append(Component.translatable("shop.mcyukkuri.tag." + second));
    }
    /** Four labelled ability bars (name, pattern-coded bar, value when nums) starting at x, y; each cell is cell wide. */
    private void abilityBars(GuiGraphics g, int x, int y, int base, int cell, boolean nums) {
        for (int i = 0; i < 4; i++) {
            int cx = x + i * cell, v = menu.value(base + i), w = font.width(Integer.toString(v));
            g.drawString(font, Component.translatable("shop.mcyukkuri.ab." + i), cx, y, INK, false);
            if (nums) g.drawString(font, Integer.toString(v), cx + cell - 6 - w, y, INK, false);
            bar(g, cx, y + 10, cell - 6, i, v);
        }
    }
    private void drawRow(GuiGraphics g, int k, int y) {
        int f = menu.value(k + ShopMenu.LiveRow.FLAGS); boolean expired = (f & ShopMenu.Flags.EXPIRED) != 0;
        if (expired) expiredChip(g, ROW_X + 6, y + 4); else chip(g, ROW_X + 6, y + 4, f & ShopMenu.Flags.GRADE_MASK);
        g.drawString(font, Component.translatable("shop.mcyukkuri.row.head2", expired ? GuiKit.expiredGradeName(f & ShopMenu.Flags.GRADE_MASK) : GuiKit.gradeName(f & ShopMenu.Flags.GRADE_MASK), species(menu.value(k)), Component.translatable("human.mcyukkuri.stage." + menu.value(k + ShopMenu.LiveRow.STAGE))), ROW_X + 6 + CHIP_WIDTH, y + 5, INK, false);
        stars(g, ROW_X + ROW_W - 6 - STARS_WIDTH, y + 4, menu.value(k + ShopMenu.LiveRow.PEDIGREE));
        abilityBars(g, ROW_X + 6, y + 17, k + ShopMenu.LiveRow.ABILITY_BASE, 60, false);
        var tag = tags(menu.value(k + ShopMenu.LiveRow.TAGS)); int tx = ROW_X + 6;
        g.drawString(font, tag, tx, y + 35, SOFT, false); tx += font.width(tag) + 10;
        if (menu.value(k + ShopMenu.LiveRow.HEALTH) < LOW_HEALTH) { var w = Component.translatable("shop.mcyukkuri.warn.hurt"); g.drawString(font, w, tx, y + 35, WARN, false); tx += font.width(w) + 8; }
        if (menu.value(k + ShopMenu.LiveRow.NUTRITION) < LOW_NUTRITION) g.drawString(font, Component.translatable("shop.mcyukkuri.warn.hungry"), tx, y + 35, WARN, false);
        boolean sold = (f & ShopMenu.Flags.LOCKED) != 0, factory = (f & ShopMenu.Flags.FACTORY) != 0;
        if (sold || (f & ShopMenu.Flags.DISPLAY) != 0 || factory) {
            MutableComponent zone = Component.empty();
            if (sold || (f & ShopMenu.Flags.DISPLAY) != 0) zone.append(Component.translatable(sold ? "shop.mcyukkuri.zone.sold" : "shop.mcyukkuri.zone.display"));
            if (factory) { if (sold || (f & ShopMenu.Flags.DISPLAY) != 0) zone.append("  "); zone.append(Component.translatable("shop.mcyukkuri.origin.factory")); }
            g.drawString(font, zone, ROW_X + 6, y + 44, sold ? WARN : SOFT, false);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        int tab = menu.value(ShopMenu.Data.TAB);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
                int k = ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE, y = TOP + i * ROW;
                if (menu.value(k) == 0) { if (i == 0) g.drawString(font, Component.translatable("human.mcyukkuri.empty"), ROW_X + 6, y + 6, SOFT, false); continue; }
                drawRow(g, k, y);
            }
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(ShopMenu.Data.PAGE) + 1, menu.value(ShopMenu.Data.PAGE_COUNT)), 100, FOOT + 6, SOFT, false);
        } else if (tab == ShopMenu.GOODS) {
            line(g, 36, INK, Component.translatable("shop.mcyukkuri.category." + ShopMenu.goodsCategory(menu.value(ShopMenu.Data.PAGE))));
            line(g, 184, SOFT, Component.translatable("shop.mcyukkuri.trades_left", menu.value(ShopMenu.Data.TRADES_LEFT)));
            line(g, 196, SOFT, Component.translatable("shop.mcyukkuri.restock_hint"));
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(ShopMenu.Data.PAGE) + 1, menu.value(ShopMenu.Data.PAGE_COUNT)), 100, FOOT + 6, SOFT, false);
        } else if (tab == ShopMenu.ORDERS) {
            line(g, 36, INK, Component.translatable("shop.mcyukkuri.order.title"));
            for (int i = 0; i < ShopOrders.PER_DAY; i++) {
                int pool = menu.value(ShopMenu.Data.ORDER_BASE + i) - 1; if (pool < 0) continue;
                var order = ShopOrders.order(pool); boolean done = (menu.value(ShopMenu.Data.ORDERS_DONE) >> i & 1) != 0;
                var item = Component.translatable(order.item().get().getDescriptionId());
                g.drawString(font, Component.translatable("shop.mcyukkuri.order.line", item, order.count(), order.reward()), 36, 56 + i * 34, done ? SOFT : INK, false);
                if (done) g.drawString(font, Component.translatable("shop.mcyukkuri.order.done"), 36, 66 + i * 34, GOOD, false);
            }
            line(g, 156, SOFT, Component.translatable("shop.mcyukkuri.order.hint"));
        } else {
            if (menu.value(ShopMenu.Data.SUBJECT_COUNT) == 0) { line(g, 42, SOFT, Component.translatable("shop.mcyukkuri.no_subject")); return; }
            boolean buy = tab == ShopMenu.BUYBACK; int f = menu.value(ShopMenu.Data.SUBJECT_FLAGS);
            line(g, 38, INK, Component.translatable("shop.mcyukkuri.subject", menu.value(ShopMenu.Data.SUBJECT_INDEX), menu.value(ShopMenu.Data.SUBJECT_COUNT), species(menu.value(ShopMenu.Data.SUBJECT_SPECIES)), grade(f), Component.translatable("human.mcyukkuri.stage." + menu.value(ShopMenu.Data.SUBJECT_STAGE))));
            abilityBars(g, 16, 52, 12, 78, true);
            chip(g, 16, 75, f & ShopMenu.Flags.GRADE_MASK); stars(g, 29, 75, menu.value(ShopMenu.Data.SUBJECT_PEDIGREE));
            g.drawString(font, tags(menu.value(ShopMenu.Data.SUBJECT_TAGS)), 86, 76, SOFT, false);
            line(g, 90, SOFT, Component.translatable("shop.mcyukkuri.vitals3", level(Math.min(100, menu.value(ShopMenu.Data.SUBJECT_HEALTH) * 10)), level(menu.value(ShopMenu.Data.SUBJECT_NUTRITION)), level(menu.value(ShopMenu.Data.SUBJECT_HYGIENE))));
            if (buy) {
                line(g, 104, INK, Component.translatable("shop.mcyukkuri.offer", menu.value(ShopMenu.Data.PRICE)));
                line(g, 116, menu.value(ShopMenu.Data.BUYBACK_PENALTY) > 0 ? WARN : SOFT, Component.translatable("shop.mcyukkuri.offer_detail", menu.value(ShopMenu.Data.BUYBACK_RATE), menu.value(ShopMenu.Data.BUYBACK_PENALTY)));
                line(g, 128, menu.value(ShopMenu.Data.BUYBACK_REFUSAL) == 0 ? SOFT : WARN, Component.translatable("shop.mcyukkuri.sell_state." + menu.value(ShopMenu.Data.BUYBACK_REFUSAL), menu.value(ShopMenu.Data.STOCK_COUNT), ShopMenu.STOCK_CAPACITY));
                line(g, 140, SOFT, Component.translatable("shop.mcyukkuri.sell_hint"));
                return;
            }
            line(g, 106, INK, Component.translatable("badge.mcyukkuri.profile.routines", menu.value(ShopMenu.Data.BOWL_ROUTINE), menu.value(ShopMenu.Data.TOILET_ROUTINE), menu.value(ShopMenu.Data.SLEEP_ROUTINE)));
            var growth = Component.translatable("badge.mcyukkuri.profile.growth", level(menu.value(ShopMenu.Data.SUBJECT_STRESS)), level(menu.value(ShopMenu.Data.SUBJECT_INDULGENCE)));
            if (menu.value(ShopMenu.Data.UNAUTHORIZED_BIRTHS) > 0) growth.append("  ").append(Component.translatable("badge.mcyukkuri.profile.unauthorized", menu.value(ShopMenu.Data.UNAUTHORIZED_BIRTHS)));
            line(g, 118, INK, growth);
            if (menu.value(ShopMenu.Data.FORECAST_BOUGHT) == 0) line(g, 134, SOFT, Component.translatable("shop.mcyukkuri.forecast_hint"));
            else line(g, 134, INK, Component.translatable("shop.mcyukkuri.forecast_result", Component.translatable("shop.mcyukkuri.forecast." + menu.value(ShopMenu.Data.FORECAST_BRONZE)),
                    Component.translatable("shop.mcyukkuri.forecast." + menu.value(ShopMenu.Data.FORECAST_SILVER)), Component.translatable("shop.mcyukkuri.forecast." + menu.value(ShopMenu.Data.FORECAST_GOLD))));
            line(g, 154, INK, Component.translatable("shop.mcyukkuri.advice_title"));
            boolean any = false;
            for (int i = 0; i < 3; i++) if (menu.value(ShopMenu.Data.ADVICE_BASE + i) > 0) { any = true; line(g, 166 + i * 12, INK, Component.literal("- ").append(Component.translatable("shop.mcyukkuri.advice." + menu.value(ShopMenu.Data.ADVICE_BASE + i)))); }
            if (!any) line(g, 166, SOFT, Component.translatable("shop.mcyukkuri.advice.0"));
            if (menu.value(ShopMenu.Data.TITLE) > 0) g.drawString(font, Component.translatable("shop.mcyukkuri.title", Component.translatable("title.mcyukkuri.pet." + menu.value(ShopMenu.Data.TITLE))), 130, 154, menu.value(ShopMenu.Data.TITLE) == 2 ? GOOD : INK, false);
        }
    }
    /** Hover text on the pedigree stars (live rows and the consult/buyback subject). */
    private void pedigreeTip(GuiGraphics g, int mx, int my) {
        int tab = menu.value(ShopMenu.Data.TAB); boolean hit = false;
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++)
                if (menu.value(ShopMenu.LiveRow.BASE + i * ShopMenu.LiveRow.STRIDE) > 0 && over(mx, my, leftPos + ROW_X + ROW_W - 6 - STARS_WIDTH, topPos + TOP + i * ROW + 4, STARS_WIDTH, 9)) hit = true;
        } else if ((tab == ShopMenu.BUYBACK || tab == ShopMenu.CONSULT) && menu.value(ShopMenu.Data.SUBJECT_COUNT) > 0) hit = over(mx, my, leftPos + 29, topPos + 75, STARS_WIDTH, 9);
        if (hit) g.renderTooltip(font, Component.translatable("shop.mcyukkuri.pedigree_tip"), mx, my);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); pedigreeTip(g, x, y); }
}
