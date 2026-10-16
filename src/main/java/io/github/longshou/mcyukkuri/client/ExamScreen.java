package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import static io.github.longshou.mcyukkuri.client.GuiKit.*;

/** Examiner screen: subject card, hall support, last result and the five services. Button and data slot ids are documented on ExamMenu. */
public final class ExamScreen extends AbstractContainerScreen<ExamMenu> {
    private final Button[] actions = new Button[8];
    public ExamScreen(ExamMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 250; }
    @Override protected void init() {
        super.init();
        add(0, 8, 6, 40, Component.literal("<")); add(1, 52, 6, 40, Component.literal(">"));
        for (int i = 0; i < 5; i++) add(2 + i, 8, 112 + i * 22, 110, Component.translatable("exam.mcyukkuri.btn." + (i + 3)));
        add(7, 150, 224, 116, Component.translatable("exam.mcyukkuri.btn.log"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 272, topPos + 224, 60, 20).build());
    }
    private void add(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        boolean has = menu.value(0) > 0;
        actions[0].active = actions[1].active = menu.value(0) > 1;
        for (int i = 0; i < 5; i++) actions[2 + i].active = has && menu.value(26 + i) == 0;
        actions[7].active = has && menu.value(24) != 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        icon(g, leftPos + imageWidth - 24, topPos + 6, ICON_EXAM);
        card(g, leftPos + 8, topPos + 28, 324, 80, false);
        if (menu.value(0) > 0) for (int i = 0; i < 5; i++) card(g, leftPos + 122, topPos + 111 + i * 22, 210, 22, false);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 16, y, color, false); }
    private static Component grade(int id) { return GuiKit.gradeName(id); }
    private static String score(int v) { return v < 0 ? "--" : Integer.toString(v); }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        if (menu.value(0) == 0) { line(g, 40, SOFT, Component.translatable("exam.mcyukkuri.ui.no_subject")); line(g, 54, SOFT, Component.translatable("exam.mcyukkuri.ui.no_subject_hint")); }
        else {
            int sp = menu.value(16), validity = menu.value(6);
            var species = sp > 0 && sp <= YukkuriVariant.values().length ? Component.translatable("entity.mcyukkuri." + YukkuriVariant.values()[sp - 1].assetId()) : Component.empty();
            g.drawString(font, Component.translatable("exam.mcyukkuri.ui.subject", menu.value(1), menu.value(0), species, Component.translatable("exam.mcyukkuri.ui.relation." + menu.value(4))), 100, 12, INK, false);
            var state = validity == 0 ? Component.translatable("exam.mcyukkuri.ui.valid_forever") : validity == 2 ? Component.translatable("exam.mcyukkuri.ui.expired") : Component.translatable("exam.mcyukkuri.ui.valid_days", menu.value(7));
            chip(g, 16, 32, menu.value(5));
            g.drawString(font, Component.translatable("exam.mcyukkuri.ui.grade", grade(menu.value(5)), menu.value(5) == 0 ? Component.empty() : state), 16 + CHIP_WIDTH, 32, INK, false);
            line(g, 42, INK, Component.translatable("shop.mcyukkuri.abilities", menu.value(8), menu.value(9), menu.value(10), menu.value(11)));
            line(g, 52, SOFT, Component.translatable("exam.mcyukkuri.ui.vitals", menu.value(31), menu.value(13), menu.value(25), menu.value(12)));
            int mask = menu.value(14);
            if ((mask & 16) == 0) line(g, 64, WARN, Component.translatable("exam.mcyukkuri.ui.no_hall"));
            else {
                var h = Component.translatable("exam.mcyukkuri.ui.hall");
                for (int i = 0; i < 4; i++) h.append("  ").append(Component.translatable("shop.mcyukkuri.ability." + i)).append((mask >> i & 1) != 0 ? " ✓" : " ✗");
                line(g, 64, (mask & 15) == 15 ? GOOD : SOFT, h);
            }
            if (menu.value(21) == 0) line(g, 77, SOFT, Component.translatable("badge.mcyukkuri.profile.exam_none"));
            else {
                line(g, 77, INK, Component.translatable("exam.mcyukkuri.ui.last", Component.translatable("exam.mcyukkuri.type." + menu.value(22)),
                        Component.translatable("exam.mcyukkuri.outcome." + (menu.value(21) - 1), grade(menu.value(23)))));
                line(g, 87, SOFT, Component.translatable("exam.mcyukkuri.ui.last_scores", score(menu.value(17)), score(menu.value(18)), score(menu.value(19)), score(menu.value(20))));
            }
            if (menu.value(2) == 0) line(g, 98, WARN, Component.translatable("exam.mcyukkuri.refuse.night"));
            else if (menu.value(3) != 0) line(g, 98, WARN, Component.translatable("exam.mcyukkuri.refuse.busy"));
        }
        for (int i = 0; i < 5; i++) {
            int code = menu.value(26 + i), type = i + 3, fee = ExamSession.fee(type);
            if (menu.value(0) == 0) continue;
            Component text = code == 0 ? Component.translatable("exam.mcyukkuri.ui.fee", fee).append("  ").append(Component.translatable("exam.mcyukkuri.hint." + type))
                    : Component.translatable("exam.mcyukkuri.refuse." + ExamSession.REASONS.get(Math.min(code, ExamSession.REASONS.size() - 1)));
            var lines = font.split(text, 198); int shown = Math.min(2, lines.size()), top = 111 + i * 22 + (shown == 1 ? 7 : 3);
            for (int n = 0; n < shown; n++) g.drawString(font, lines.get(n), 128, top + n * 8, code == 0 ? SOFT : WARN, false);
        }
        g.drawString(font, Component.translatable("exam.mcyukkuri.ui.emeralds", menu.value(15)), 16, 230, SOFT, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); }
}
