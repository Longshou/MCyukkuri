package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.ShopMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.player.Inventory;

/** Tabbed shopkeeper screen: live stock, goods, buyback, consultation. Button and data slot ids are documented on ShopMenu. */
public final class ShopScreen extends AbstractContainerScreen<ShopMenu> {
    private static final int INK = 0xff34453e, SOFT = 0xff6a7a70, WARN = 0xff9a3b32;
    private final Button[] actions = new Button[22];
    public ShopScreen(ShopMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 250; }
    @Override protected void init() {
        super.init();
        for (int i = 0; i < 4; i++) {
            add(i, 10 + i * 82, 6, 78, Component.translatable("shop.mcyukkuri.tab." + i));
            add(4 + i, 272, 32 + i * 44 + 1, 58, Component.translatable("shop.mcyukkuri.buy"));
            add(8 + i, 272, 32 + i * 44 + 22, 58, Component.translatable("shop.mcyukkuri.locate"));
            add(14 + i, 10, 48 + i * 30, 320, Component.empty());
        }
        add(12, 10, 222, 40, Component.literal("<")); add(13, 54, 222, 40, Component.literal(">"));
        add(20, 10, 196, 40, Component.literal("<")); add(21, 54, 196, 40, Component.literal(">"));
        add(18, 100, 196, 150, Component.translatable("shop.mcyukkuri.sell"));
        add(19, 100, 196, 150, Component.translatable("shop.mcyukkuri.forecast"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 270, topPos + 222, 60, 20).build());
    }
    private void add(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, 20).build());
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
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff30484a);
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xfff1e7d2);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 10, y, color, false); }
    private static Component species(int v) { return v > 0 && v <= YukkuriVariant.values().length ? Component.translatable("entity.mcyukkuri." + YukkuriVariant.values()[v - 1].assetId()) : Component.empty(); }
    private static Component grade(int flags) {
        var c = Component.translatable("badge.mcyukkuri.profile.grade." + (flags & 3));
        return (flags & 4) != 0 ? c.append(Component.translatable("shop.mcyukkuri.expired")) : c;
    }
    private static MutableComponent tags(int packed) {
        var c = Component.translatable("shop.mcyukkuri.tag." + (packed & 15)); int second = packed >> 4 & 15;
        return second == 15 ? c : c.append(" / ").append(Component.translatable("shop.mcyukkuri.tag." + second));
    }
    private static String stars(int pedigree) { int n = Math.min(5, pedigree / 20 + 1); return "★".repeat(n) + "☆".repeat(5 - n); }
    private Component abilities(int base) { return Component.translatable("shop.mcyukkuri.abilities", menu.value(base), menu.value(base + 1), menu.value(base + 2), menu.value(base + 3)); }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        int tab = menu.value(0);
        if (tab == ShopMenu.LIVE) {
            for (int i = 0; i < 4; i++) {
                int k = 8 + i * 12, y = 32 + i * 44;
                if (i > 0) g.fill(10, y - 3, 266, y - 2, 0x33000000);
                if (menu.value(k) == 0) { if (i == 0) line(g, y + 4, SOFT, Component.translatable("human.mcyukkuri.empty")); continue; }
                int f = menu.value(k + 2);
                line(g, y, INK, Component.translatable("shop.mcyukkuri.row.head", grade(f), species(menu.value(k)), Component.translatable("human.mcyukkuri.stage." + menu.value(k + 1)), menu.value(k + 11)));
                line(g, y + 11, INK, Component.literal(stars(menu.value(k + 3)) + "  ").append(abilities(k + 4)));
                line(g, y + 22, SOFT, tags(menu.value(k + 8)).append("  ").append(Component.translatable("shop.mcyukkuri.vitals", menu.value(k + 9), menu.value(k + 10))));
                line(g, y + 33, (f & 16) != 0 ? WARN : SOFT, Component.translatable((f & 16) != 0 ? "shop.mcyukkuri.zone.sold" : (f & 8) != 0 ? "shop.mcyukkuri.zone.display" : "shop.mcyukkuri.zone.back"));
            }
            line(g, 228, SOFT, Component.translatable("shop.mcyukkuri.page", menu.value(3) + 1, menu.value(4)));
        } else if (tab == ShopMenu.GOODS) {
            line(g, 30, INK, Component.translatable("shop.mcyukkuri.category." + ShopMenu.goodsCategory(menu.value(3))));
            line(g, 190, SOFT, Component.translatable("shop.mcyukkuri.trades_left", menu.value(1)));
            line(g, 228, SOFT, Component.translatable("shop.mcyukkuri.page", menu.value(3) + 1, menu.value(4)));
        } else {
            if (menu.value(6) == 0) { line(g, 40, SOFT, Component.translatable("shop.mcyukkuri.no_subject")); return; }
            boolean buy = tab == ShopMenu.BUYBACK; int f = menu.value(10);
            line(g, 32, INK, Component.translatable("shop.mcyukkuri.subject", menu.value(7), menu.value(6), species(menu.value(8)), grade(f), Component.translatable("human.mcyukkuri.stage." + menu.value(9))));
            line(g, 46, INK, abilities(12));
            line(g, 58, SOFT, Component.literal(stars(menu.value(11)) + "  ").append(tags(menu.value(35))));
            line(g, 70, SOFT, Component.translatable("shop.mcyukkuri.vitals3", menu.value(16), menu.value(17), menu.value(18)));
            if (buy) {
                line(g, 90, INK, Component.translatable("shop.mcyukkuri.offer", menu.value(29)));
                line(g, 104, menu.value(34) == 0 ? SOFT : WARN, Component.translatable("shop.mcyukkuri.sell_state." + menu.value(34), menu.value(5), ShopMenu.STOCK_CAPACITY));
                line(g, 118, SOFT, Component.translatable("shop.mcyukkuri.sell_hint"));
                return;
            }
            line(g, 82, INK, Component.translatable("badge.mcyukkuri.profile.routines", menu.value(21), menu.value(22), menu.value(23)));
            line(g, 94, INK, Component.translatable("badge.mcyukkuri.profile.growth", menu.value(11), menu.value(19), menu.value(20), menu.value(24)));
            line(g, 106, INK, Component.translatable("shop.mcyukkuri.weakest", Component.translatable("shop.mcyukkuri.ability." + menu.value(25))));
            if (menu.value(33) == 0) line(g, 118, SOFT, Component.translatable("shop.mcyukkuri.forecast_hint"));
            else line(g, 118, INK, Component.translatable("shop.mcyukkuri.forecast_result", Component.translatable("shop.mcyukkuri.forecast." + menu.value(30)),
                    Component.translatable("shop.mcyukkuri.forecast." + menu.value(31)), Component.translatable("shop.mcyukkuri.forecast." + menu.value(32))));
            line(g, 134, INK, Component.translatable("shop.mcyukkuri.advice_title"));
            boolean any = false;
            for (int i = 0; i < 3; i++) if (menu.value(26 + i) > 0) { any = true; line(g, 146 + i * 12, INK, Component.literal("- ").append(Component.translatable("shop.mcyukkuri.advice." + menu.value(26 + i)))); }
            if (!any) line(g, 146, SOFT, Component.translatable("shop.mcyukkuri.advice.0"));
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); }
}
