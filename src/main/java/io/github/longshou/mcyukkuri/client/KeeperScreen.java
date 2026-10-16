package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.KeeperMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Keeper screen: studs, nursery and restock status, and the matchmaking service. Button and data slot ids are documented on KeeperMenu. */
public final class KeeperScreen extends AbstractContainerScreen<KeeperMenu> {
    private static final int INK = 0xff34453e, SOFT = 0xff6a7a70, WARN = 0xff9a3b32;
    private final Button[] actions = new Button[5];
    public KeeperScreen(KeeperMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 300; imageHeight = 220; }
    @Override protected void init() {
        super.init();
        add(3, 236, 24, 24, Component.literal("<")); add(4, 264, 24, 24, Component.literal(">"));
        add(0, 10, 144, 30, Component.literal("<")); add(1, 44, 144, 30, Component.literal(">"));
        add(2, 80, 144, 210, Component.translatable("shop.mcyukkuri.keeper.book", KeeperMenu.FEE));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 230, topPos + 192, 60, 20).build());
    }
    private void add(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        actions[0].active = actions[1].active = menu.value(6) > 1; actions[3].active = actions[4].active = menu.value(1) > 1; actions[2].active = menu.value(9) == 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff30484a);
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xfff1e7d2);
    }
    private static Component species(int v) { return v > 0 && v <= YukkuriVariant.values().length ? Component.translatable("entity.mcyukkuri." + YukkuriVariant.values()[v - 1].assetId()) : Component.empty(); }
    private static String stars(int pedigree) { int n = Math.min(5, pedigree / 20 + 1); return "★".repeat(n) + "☆".repeat(5 - n); }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.status", menu.value(3), menu.value(4), menu.value(2), menu.value(5)), 10, 10, INK, false);
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.studs", menu.value(1)), 10, 30, INK, false);
        if (menu.value(1) == 0) g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.no_stud"), 10, 44, SOFT, false);
        for (int i = 0; i < 4; i++) {
            int k = 12 + i * 4; if (menu.value(k) == 0) continue;
            int f = menu.value(k + 1);
            var line = Component.literal(menu.value(k + 3) == 1 ? "> " : "  ").append(Component.translatable("badge.mcyukkuri.profile.grade." + (f & 3)))
                    .append(" ").append(species(menu.value(k))).append("  " + stars(menu.value(k + 2)) + " " + menu.value(k + 2));
            if ((f & 8) != 0) line.append(Component.translatable("shop.mcyukkuri.keeper.occupied"));
            g.drawString(font, line, 10, 44 + i * 11, menu.value(k + 3) == 1 ? INK : SOFT, false);
        }
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.omiai"), 10, 98, INK, false);
        if (menu.value(7) == 0) g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.no_subject"), 10, 112, SOFT, false);
        else g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.subject", species(menu.value(28)), menu.value(29), menu.value(7), menu.value(6)), 10, 112, INK, false);
        int st = menu.value(9);
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.state." + st, menu.value(10)), 10, 126, st == 0 ? INK : WARN, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); }
}
