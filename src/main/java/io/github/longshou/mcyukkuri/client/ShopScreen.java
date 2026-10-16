package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
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
    private final Button[] actions = new Button[25];
    public ShopScreen(ShopMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 250; }
    @Override protected void init() {
        super.init();
        int[] icons = {ICON_LIVE, ICON_GOODS, ICON_BUYBACK, ICON_CONSULT};
        for (int i = 0; i < 4; i++) {
            int tabId = i;
            actions[i] = addRenderableWidget(new GuiKit.Tab(leftPos + 8 + i * 82, topPos + 6, 78, 22, Component.translatable("shop.mcyukkuri.tab." + i), icons[i], b -> press(tabId)));
            add(14 + i, 36, 52 + i * 34, 290, Component.empty());
        }
        for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
            add(4 + i, 270, TOP + i * ROW + 3, 62, 16, Component.translatable("shop.mcyukkuri.buy"));
            add(8 + i, 270, TOP + i * ROW + 20, 62, 16, Component.translatable("shop.mcyukkuri.locate"));
            add(22 + i, 270, TOP + i * ROW + 37, 62, 16, Component.translatable("shop.mcyukkuri.about"));
        }
        add(12, 8, FOOT, 40, Component.literal("<")); add(13, 52, FOOT, 40, Component.literal(">"));
        add(20, 8, FOOT, 40, Component.literal("<")); add(21, 52, FOOT, 40, Component.literal(">"));
        add(18, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.sell"));
        add(19, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.forecast"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 272, topPos + FOOT, 60, 20).build());
    }
    private void press(int id) { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); }
    private void add(int id, int x, int y, int w, Component title) { add(id, x, y, w, 20, title); }
    private void add(int id, int x, int y, int w, int h, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> press(id)).bounds(leftPos + x, topPos + y, w, h).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        int tab = menu.value(0); boolean day = menu.value(2) != 0, own = menu.value(6) > 0;
        for (int i = 0; i < 4; i++) {
            actions[i].active = tab != i;
            if (i < ShopMenu.PAGE_ROWS) {
                boolean has = menu.value(8 + i * 12) > 0, locked = (menu.value(10 + i * 12) & 16) != 0;
                actions[4 + i].visible = actions[8 + i].visible = actions[22 + i].visible = tab == ShopMenu.LIVE;
                actions[4 + i].active = has && day && !locked; actions[8 + i].active = actions[22 + i].active = has;
                if (has) actions[4 + i].setMessage(Component.translatable("shop.mcyukkuri.buy_price", menu.value(19 + i * 12)));
            }
            var stack = ShopMenu.goodsStack(menu.value(3), i); int price = ShopMenu.goodsPrice(menu.value(3), i);
            actions[14 + i].visible = tab == ShopMenu.GOODS && !stack.isEmpty();
            actions[14 + i].active = day && menu.value(1) > 0;
            if (!stack.isEmpty()) actions[14 + i].setMessage(Component.translatable("human.mcyukkuri.buy_supply", stack.getHoverName(), stack.getCount(), price));
        }
        actions[12].visible = actions[13].visible = tab <= ShopMenu.GOODS; actions[12].active = actions[13].active = menu.value(4) > 1;
        actions[20].visible = actions[21].visible = tab >= ShopMenu.BUYBACK; actions[20].active = actions[21].active = menu.value(6) > 1;
        actions[18].visible = tab == ShopMenu.BUYBACK; actions[18].active = own && day && menu.value(34) == 0;
        actions[19].visible = tab == ShopMenu.CONSULT; actions[19].active = own && day && menu.value(33) == 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        int tab = menu.value(0);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) card(g, leftPos + ROW_X, topPos + TOP + i * ROW, ROW_W, ROW_H, menu.value(56) == i + 1 && menu.value(8 + i * 12) > 0);
        } else if (tab == ShopMenu.GOODS) {
            for (int i = 0; i < 4; i++) {
                var stack = ShopMenu.goodsStack(menu.value(3), i); if (stack.isEmpty()) continue;
                card(g, leftPos + 8, topPos + 46 + i * 34, 324, 30, false);
                g.renderItem(stack, leftPos + 14, topPos + 53 + i * 34);
            }
        } else card(g, leftPos + 8, topPos + 32, 324, menu.value(6) == 0 ? 28 : tab == ShopMenu.BUYBACK ? 120 : 178, false);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 16, y, color, false); }
    /** Short species name (no Yukkuri suffix) when the lang file has one, else the entity name. */
    private static Component species(int v) {
        if (v <= 0 || v > YukkuriVariant.values().length) return Component.empty();
        String id = YukkuriVariant.values()[v - 1].assetId();
        return Component.translatable(I18n.exists("shop.mcyukkuri.sp." + id) ? "shop.mcyukkuri.sp." + id : "entity.mcyukkuri." + id);
    }
    private static Component grade(int flags) {
        var c = GuiKit.gradeName(flags & 3);
        return (flags & 4) != 0 ? c.append(Component.translatable("shop.mcyukkuri.expired")) : c;
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
        int f = menu.value(k + 2); boolean expired = (f & 4) != 0;
        if (expired) expiredChip(g, ROW_X + 6, y + 4); else chip(g, ROW_X + 6, y + 4, f & 3);
        g.drawString(font, Component.translatable("shop.mcyukkuri.row.head2", expired ? GuiKit.expiredGradeName(f & 3) : GuiKit.gradeName(f & 3), species(menu.value(k)), Component.translatable("human.mcyukkuri.stage." + menu.value(k + 1))), ROW_X + 6 + CHIP_WIDTH, y + 5, INK, false);
        stars(g, ROW_X + ROW_W - 6 - STARS_WIDTH, y + 4, menu.value(k + 3));
        abilityBars(g, ROW_X + 6, y + 17, k + 4, 60, false);
        var tag = tags(menu.value(k + 8)); int tx = ROW_X + 6;
        g.drawString(font, tag, tx, y + 35, SOFT, false); tx += font.width(tag) + 10;
        if (menu.value(k + 9) < LOW_HEALTH) { var w = Component.translatable("shop.mcyukkuri.warn.hurt"); g.drawString(font, w, tx, y + 35, WARN, false); tx += font.width(w) + 8; }
        if (menu.value(k + 10) < LOW_NUTRITION) g.drawString(font, Component.translatable("shop.mcyukkuri.warn.hungry"), tx, y + 35, WARN, false);
        boolean sold = (f & 16) != 0, factory = (f & 32) != 0;
        if (sold || (f & 8) != 0 || factory) {
            MutableComponent zone = Component.empty();
            if (sold || (f & 8) != 0) zone.append(Component.translatable(sold ? "shop.mcyukkuri.zone.sold" : "shop.mcyukkuri.zone.display"));
            if (factory) { if (sold || (f & 8) != 0) zone.append("  "); zone.append(Component.translatable("shop.mcyukkuri.origin.factory")); }
            g.drawString(font, zone, ROW_X + 6, y + 44, sold ? WARN : SOFT, false);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        int tab = menu.value(0);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++) {
                int k = 8 + i * 12, y = TOP + i * ROW;
                if (menu.value(k) == 0) { if (i == 0) g.drawString(font, Component.translatable("human.mcyukkuri.empty"), ROW_X + 6, y + 6, SOFT, false); continue; }
                drawRow(g, k, y);
            }
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(3) + 1, menu.value(4)), 100, FOOT + 6, SOFT, false);
        } else if (tab == ShopMenu.GOODS) {
            line(g, 36, INK, Component.translatable("shop.mcyukkuri.category." + ShopMenu.goodsCategory(menu.value(3))));
            line(g, 184, SOFT, Component.translatable("shop.mcyukkuri.trades_left", menu.value(1)));
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(3) + 1, menu.value(4)), 100, FOOT + 6, SOFT, false);
        } else {
            if (menu.value(6) == 0) { line(g, 42, SOFT, Component.translatable("shop.mcyukkuri.no_subject")); return; }
            boolean buy = tab == ShopMenu.BUYBACK; int f = menu.value(10);
            line(g, 38, INK, Component.translatable("shop.mcyukkuri.subject", menu.value(7), menu.value(6), species(menu.value(8)), grade(f), Component.translatable("human.mcyukkuri.stage." + menu.value(9))));
            abilityBars(g, 16, 52, 12, 78, true);
            chip(g, 16, 75, f & 3); stars(g, 29, 75, menu.value(11));
            g.drawString(font, tags(menu.value(35)), 86, 76, SOFT, false);
            line(g, 90, SOFT, Component.translatable("shop.mcyukkuri.vitals3", level(Math.min(100, menu.value(16) * 10)), level(menu.value(17)), level(menu.value(18))));
            if (buy) {
                line(g, 108, INK, Component.translatable("shop.mcyukkuri.offer", menu.value(29)));
                line(g, 122, menu.value(34) == 0 ? SOFT : WARN, Component.translatable("shop.mcyukkuri.sell_state." + menu.value(34), menu.value(5), ShopMenu.STOCK_CAPACITY));
                line(g, 136, SOFT, Component.translatable("shop.mcyukkuri.sell_hint"));
                return;
            }
            line(g, 106, INK, Component.translatable("badge.mcyukkuri.profile.routines", menu.value(21), menu.value(22), menu.value(23)));
            var growth = Component.translatable("badge.mcyukkuri.profile.growth", level(menu.value(19)), level(menu.value(20)));
            if (menu.value(24) > 0) growth.append("  ").append(Component.translatable("badge.mcyukkuri.profile.unauthorized", menu.value(24)));
            line(g, 118, INK, growth);
            if (menu.value(33) == 0) line(g, 134, SOFT, Component.translatable("shop.mcyukkuri.forecast_hint"));
            else line(g, 134, INK, Component.translatable("shop.mcyukkuri.forecast_result", Component.translatable("shop.mcyukkuri.forecast." + menu.value(30)),
                    Component.translatable("shop.mcyukkuri.forecast." + menu.value(31)), Component.translatable("shop.mcyukkuri.forecast." + menu.value(32))));
            line(g, 154, INK, Component.translatable("shop.mcyukkuri.advice_title"));
            boolean any = false;
            for (int i = 0; i < 3; i++) if (menu.value(26 + i) > 0) { any = true; line(g, 166 + i * 12, INK, Component.literal("- ").append(Component.translatable("shop.mcyukkuri.advice." + menu.value(26 + i)))); }
            if (!any) line(g, 166, SOFT, Component.translatable("shop.mcyukkuri.advice.0"));
            if (menu.value(36) > 0) g.drawString(font, Component.translatable("shop.mcyukkuri.title", Component.translatable("title.mcyukkuri.pet." + menu.value(36))), 130, 154, menu.value(36) == 2 ? GOOD : INK, false);
        }
    }
    /** Hover text on the pedigree stars (live rows and the consult/buyback subject). */
    private void pedigreeTip(GuiGraphics g, int mx, int my) {
        int tab = menu.value(0); boolean hit = false;
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < ShopMenu.PAGE_ROWS; i++)
                if (menu.value(8 + i * 12) > 0 && over(mx, my, leftPos + ROW_X + ROW_W - 6 - STARS_WIDTH, topPos + TOP + i * ROW + 4, STARS_WIDTH, 9)) hit = true;
        } else if (tab >= ShopMenu.BUYBACK && menu.value(6) > 0) hit = over(mx, my, leftPos + 29, topPos + 75, STARS_WIDTH, 9);
        if (hit) g.renderTooltip(font, Component.translatable("shop.mcyukkuri.pedigree_tip"), mx, my);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); pedigreeTip(g, x, y); }
}
