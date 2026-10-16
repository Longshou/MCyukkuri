package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;
import static io.github.longshou.mcyukkuri.client.GuiKit.*;

/** Tabbed shopkeeper screen: live stock, goods, buyback, consultation. Button and data slot ids are documented on ShopMenu (slot 56: highlighted live row, 1-4, 0 none). */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final int ROW = 58, ROW_X = 8, ROW_W = 256, ROW_H = 55, TOP = 34, FOOT = 268;
    private final Button[] actions = new Button[22];
    public ShopScreen(ShopMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 294; }
    @Override protected void init() {
        super.init();
        int[] icons = {ICON_LIVE, ICON_GOODS, ICON_BUYBACK, ICON_CONSULT};
        for (int i = 0; i < 4; i++) {
            int tabId = i;
            actions[i] = addRenderableWidget(new GuiKit.Tab(leftPos + 8 + i * 82, topPos + 6, 78, 22, Component.translatable("shop.mcyukkuri.tab." + i), icons[i], b -> press(tabId)));
            add(4 + i, 270, TOP + i * ROW + 4, 62, Component.translatable("shop.mcyukkuri.buy"));
            add(8 + i, 270, TOP + i * ROW + 27, 62, Component.translatable("shop.mcyukkuri.locate"));
            add(14 + i, 36, 54 + i * 36, 290, Component.empty());
        }
        add(12, 8, FOOT, 40, Component.literal("<")); add(13, 52, FOOT, 40, Component.literal(">"));
        add(20, 8, FOOT, 40, Component.literal("<")); add(21, 52, FOOT, 40, Component.literal(">"));
        add(18, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.sell"));
        add(19, 100, FOOT, 160, Component.translatable("shop.mcyukkuri.forecast"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 272, topPos + FOOT, 60, 20).build());
    }
    private void press(int id) { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); }
    private void add(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> press(id)).bounds(leftPos + x, topPos + y, w, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        int tab = menu.value(0); boolean day = menu.value(2) != 0, own = menu.value(6) > 0;
        for (int i = 0; i < 4; i++) {
            actions[i].active = tab != i;
            boolean has = menu.value(8 + i * 12) > 0, locked = (menu.value(10 + i * 12) & 16) != 0;
            actions[4 + i].visible = actions[8 + i].visible = tab == ShopMenu.LIVE;
            actions[4 + i].active = has && day && !locked; actions[8 + i].active = has;
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
            for (int i = 0; i < 4; i++) card(g, leftPos + ROW_X, topPos + TOP + i * ROW, ROW_W, ROW_H, menu.value(56) == i + 1 && menu.value(8 + i * 12) > 0);
        } else if (tab == ShopMenu.GOODS) {
            for (int i = 0; i < 4; i++) {
                var stack = ShopMenu.goodsStack(menu.value(3), i); if (stack.isEmpty()) continue;
                card(g, leftPos + 8, topPos + 48 + i * 36, 324, 30, false);
                g.renderItem(stack, leftPos + 14, topPos + 55 + i * 36);
            }
        } else card(g, leftPos + 8, topPos + 32, 324, menu.value(6) == 0 ? 28 : tab == ShopMenu.BUYBACK ? 120 : 178, false);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 16, y, color, false); }
    private static Component species(int v) { return v > 0 && v <= YukkuriVariant.values().length ? Component.translatable("entity.mcyukkuri." + YukkuriVariant.values()[v - 1].assetId()) : Component.empty(); }
    private static Component grade(int flags) {
        var c = GuiKit.gradeName(flags & 3);
        return (flags & 4) != 0 ? c.append(Component.translatable("shop.mcyukkuri.expired")) : c;
    }
    private static MutableComponent tags(int packed) {
        var c = Component.translatable("shop.mcyukkuri.tag." + (packed & 15)); int second = packed >> 4 & 15;
        return second == 15 ? c : c.append(" / ").append(Component.translatable("shop.mcyukkuri.tag." + second));
    }
    /** Four labelled ability bars (name, value, pattern-coded bar) starting at x, y; each cell is 60 wide. */
    private void abilityBars(GuiGraphics g, int x, int y, int base) {
        for (int i = 0; i < 4; i++) {
            int cx = x + i * 60, v = menu.value(base + i), w = font.width(Integer.toString(v));
            g.drawString(font, Component.translatable("shop.mcyukkuri.ab." + i), cx, y, INK, false);
            g.drawString(font, Integer.toString(v), cx + 54 - w, y, INK, false);
            bar(g, cx, y + 10, 54, i, v);
        }
    }
    private void drawRow(GuiGraphics g, int k, int y) {
        int f = menu.value(k + 2);
        chip(g, ROW_X + 6, y + 4, f & 3);
        g.drawString(font, Component.translatable("shop.mcyukkuri.row.head", grade(f), species(menu.value(k)), Component.translatable("human.mcyukkuri.stage." + menu.value(k + 1)), menu.value(k + 11)), ROW_X + 6 + CHIP_WIDTH, y + 5, INK, false);
        stars(g, ROW_X + ROW_W - 6 - STARS_WIDTH, y + 4, menu.value(k + 3));
        abilityBars(g, ROW_X + 6, y + 18, k + 4);
        g.drawString(font, tags(menu.value(k + 8)).append("  ").append(Component.translatable("shop.mcyukkuri.vitals", menu.value(k + 9), menu.value(k + 10))), ROW_X + 6, y + 33, SOFT, false);
        g.drawString(font, Component.translatable((f & 16) != 0 ? "shop.mcyukkuri.zone.sold" : (f & 8) != 0 ? "shop.mcyukkuri.zone.display" : "shop.mcyukkuri.zone.back"), ROW_X + 6, y + 44, (f & 16) != 0 ? WARN : SOFT, false);
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        int tab = menu.value(0);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < 4; i++) {
                int k = 8 + i * 12, y = TOP + i * ROW;
                if (menu.value(k) == 0) { if (i == 0) g.drawString(font, Component.translatable("human.mcyukkuri.empty"), ROW_X + 6, y + 6, SOFT, false); continue; }
                drawRow(g, k, y);
            }
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(3) + 1, menu.value(4)), 100, FOOT + 6, SOFT, false);
        } else if (tab == ShopMenu.GOODS) {
            line(g, 36, INK, Component.translatable("shop.mcyukkuri.category." + ShopMenu.goodsCategory(menu.value(3))));
            line(g, 198, SOFT, Component.translatable("shop.mcyukkuri.trades_left", menu.value(1)));
            g.drawString(font, Component.translatable("shop.mcyukkuri.page", menu.value(3) + 1, menu.value(4)), 100, FOOT + 6, SOFT, false);
        } else {
            if (menu.value(6) == 0) { line(g, 42, SOFT, Component.translatable("shop.mcyukkuri.no_subject")); return; }
            boolean buy = tab == ShopMenu.BUYBACK; int f = menu.value(10);
            line(g, 38, INK, Component.translatable("shop.mcyukkuri.subject", menu.value(7), menu.value(6), species(menu.value(8)), grade(f), Component.translatable("human.mcyukkuri.stage." + menu.value(9))));
            abilityBars(g, 16, 52, 12);
            chip(g, 16, 75, f & 3); stars(g, 29, 75, menu.value(11));
            g.drawString(font, tags(menu.value(35)), 86, 76, SOFT, false);
            line(g, 90, SOFT, Component.translatable("shop.mcyukkuri.vitals3", menu.value(16), menu.value(17), menu.value(18)));
            if (buy) {
                line(g, 108, INK, Component.translatable("shop.mcyukkuri.offer", menu.value(29)));
                line(g, 122, menu.value(34) == 0 ? SOFT : WARN, Component.translatable("shop.mcyukkuri.sell_state." + menu.value(34), menu.value(5), ShopMenu.STOCK_CAPACITY));
                line(g, 136, SOFT, Component.translatable("shop.mcyukkuri.sell_hint"));
                return;
            }
            line(g, 108, INK, Component.translatable("badge.mcyukkuri.profile.routines", menu.value(21), menu.value(22), menu.value(23)));
            line(g, 120, INK, Component.translatable("badge.mcyukkuri.profile.growth", menu.value(11), menu.value(19), menu.value(20), menu.value(24)));
            line(g, 132, INK, Component.translatable("shop.mcyukkuri.weakest", Component.translatable("shop.mcyukkuri.ability." + menu.value(25))));
            if (menu.value(33) == 0) line(g, 144, SOFT, Component.translatable("shop.mcyukkuri.forecast_hint"));
            else line(g, 144, INK, Component.translatable("shop.mcyukkuri.forecast_result", Component.translatable("shop.mcyukkuri.forecast." + menu.value(30)),
                    Component.translatable("shop.mcyukkuri.forecast." + menu.value(31)), Component.translatable("shop.mcyukkuri.forecast." + menu.value(32))));
            line(g, 160, INK, Component.translatable("shop.mcyukkuri.advice_title"));
            boolean any = false;
            for (int i = 0; i < 3; i++) if (menu.value(26 + i) > 0) { any = true; line(g, 172 + i * 12, INK, Component.literal("- ").append(Component.translatable("shop.mcyukkuri.advice." + menu.value(26 + i)))); }
            if (!any) line(g, 172, SOFT, Component.translatable("shop.mcyukkuri.advice.0"));
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); }
}
