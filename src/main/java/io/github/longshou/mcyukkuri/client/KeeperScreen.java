package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.shop.KeeperMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import static io.github.longshou.mcyukkuri.client.GuiKit.*;

/** Keeper screen: studs, nursery and restock status, and the matchmaking service. Button and data slot ids are documented on KeeperMenu. */
public final class KeeperScreen extends AbstractContainerScreen<KeeperMenu> {
    private final Button[] actions = new Button[KeeperMenu.ButtonId.COUNT];
    public KeeperScreen(KeeperMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 300; imageHeight = 220; }
    @Override protected void init() {
        super.init();
        add(KeeperMenu.ButtonId.NEXT_STUD, 236, 24, 24, Component.literal("<")); add(KeeperMenu.ButtonId.PREVIOUS_STUD, 264, 24, 24, Component.literal(">"));
        add(KeeperMenu.ButtonId.PREVIOUS_SUBJECT, 8, 144, 30, Component.literal("<")); add(KeeperMenu.ButtonId.NEXT_SUBJECT, 42, 144, 30, Component.literal(">"));
        add(KeeperMenu.ButtonId.BOOK, 78, 144, 214, Component.translatable("shop.mcyukkuri.keeper.book", KeeperMenu.FEE));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 230, topPos + 192, 60, 20).build());
    }
    private void add(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        actions[KeeperMenu.ButtonId.PREVIOUS_SUBJECT].active = actions[KeeperMenu.ButtonId.NEXT_SUBJECT].active =
                menu.value(KeeperMenu.Data.SUBJECT_COUNT) > 1; actions[KeeperMenu.ButtonId.NEXT_STUD].active =
                actions[KeeperMenu.ButtonId.PREVIOUS_STUD].active = menu.value(KeeperMenu.Data.STUD_COUNT) > 1;
                actions[KeeperMenu.ButtonId.BOOK].active = menu.value(KeeperMenu.Data.BOOKING_STATE) == 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        card(g, leftPos + 8, topPos + 40, 284, 54, false);
        for (int i = 0; i < KeeperMenu.StudRow.COUNT; i++) if (menu.value(KeeperMenu.Data.STUD_BASE + i * KeeperMenu.StudRow.STRIDE) != 0 &&
                menu.value(KeeperMenu.StudRow.SELECTED_BASE + i * KeeperMenu.StudRow.STRIDE) == 1) card(g, leftPos + 10, topPos + 42 + i * 12, 280, 12, true);
        icon(g, leftPos + 8, topPos + 96, ICON_KEEPER);
        card(g, leftPos + 8, topPos + 114, 284, 28, false);
    }
    private static Component species(int v) { return v > 0 && v <= YukkuriVariant.values().length ? Component.translatable("entity.mcyukkuri." +
            YukkuriVariant.values()[v - 1].assetId()) : Component.empty(); }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.status", menu.value(KeeperMenu.Data.POPULATION),
                menu.value(KeeperMenu.Data.CAPACITY), menu.value(KeeperMenu.Data.NURSERY_COUNT), menu.value(KeeperMenu.Data.RESTOCK_DAYS)), 12, 10, INK, false);
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.studs", menu.value(KeeperMenu.Data.STUD_COUNT)), 12, 30, INK, false);
        if (menu.value(KeeperMenu.Data.STUD_COUNT) == 0) g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.no_stud"), 14, 46, SOFT, false);
        for (int i = 0; i < KeeperMenu.StudRow.COUNT; i++) {
            int k = KeeperMenu.Data.STUD_BASE + i * KeeperMenu.StudRow.STRIDE; if (menu.value(k) == 0) continue;
            int f = menu.value(k + KeeperMenu.StudRow.FLAGS);
            var line = GuiKit.gradeName(f & KeeperMenu.Flags.GRADE_MASK).append(" ").append(species(menu.value(k)));
            if ((f & KeeperMenu.Flags.BUSY) != 0) line.append(Component.translatable("shop.mcyukkuri.keeper.occupied"));
            chip(g, 14, 43 + i * 12, f & KeeperMenu.Flags.GRADE_MASK);
            g.drawString(font, line, 14 + CHIP_WIDTH, 44 + i * 12, menu.value(k + KeeperMenu.StudRow.SELECTED) == 1 ? INK : SOFT, false);
            stars(g, 292 - 6 - STARS_WIDTH, 43 + i * 12, menu.value(k + KeeperMenu.StudRow.PEDIGREE));
        }
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.omiai"), 28, 100, INK, false);
        if (menu.value(KeeperMenu.Data.SUBJECT_INDEX) == 0) g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.no_subject"), 14, 118, SOFT, false);
        else {
            g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.subject", species(menu.value(KeeperMenu.Data.SUBJECT_SPECIES)),
                    menu.value(KeeperMenu.Data.SUBJECT_INDEX), menu.value(KeeperMenu.Data.SUBJECT_COUNT)), 14, 118, INK, false);
            stars(g, 292 - 6 - STARS_WIDTH, 117, menu.value(KeeperMenu.Data.SUBJECT_PEDIGREE));
        }
        int st = menu.value(KeeperMenu.Data.BOOKING_STATE);
        g.drawString(font, Component.translatable("shop.mcyukkuri.keeper.state." + st, menu.value(KeeperMenu.Data.FEE)), 14, 130, st == 0 ? INK : WARN, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial); renderTooltip(g, x, y);
        boolean hit = menu.value(KeeperMenu.Data.SUBJECT_INDEX) != 0 && over(x, y, leftPos + 292 - 6 - STARS_WIDTH, topPos + 117, STARS_WIDTH, 9);
        for (int i = 0; i < 4 && !hit; i++) hit = menu.value(KeeperMenu.Data.STUD_BASE + i * KeeperMenu.StudRow.STRIDE) != 0 && over(x, y, leftPos +
                292 - 6 - STARS_WIDTH, topPos + 43 + i * 12, STARS_WIDTH, 9);
        if (hit) g.renderTooltip(font, Component.translatable("shop.mcyukkuri.pedigree_tip"), x, y);
    }
}
