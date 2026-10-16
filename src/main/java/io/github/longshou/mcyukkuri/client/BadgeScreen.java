package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.badge.BadgeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import static io.github.longshou.mcyukkuri.client.GuiKit.*;

public final class BadgeScreen extends AbstractContainerScreen<BadgeMenu> {
    private static final int[] BUTTONS = {5, 10, 8, 11, 12, 13};
    private final Button[] buttons = new Button[33];
    private final String[] lessonTip = new String[4];
    public BadgeScreen(BadgeMenu m, Inventory inv, Component title) { super(m, inv, title); imageWidth = 360; imageHeight = 250; }
    @Override protected void init() {
        super.init();
        for (int n = 0; n < BUTTONS.length; n++) {
            final int id = BUTTONS[n];
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + id), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + n % 3 * 115, topPos + 126 + n / 3 * 22, 110, 20)
                    .tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + id))).build());
        }
        for (int k = 0; k < 4; k++) {
            final int id = 20 + k;
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("pet.mcyukkuri.ui.lesson." + k), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + k * 58, topPos + 172, 55, 20).build());
        }
        for (int k = 0; k < 3; k++) {
            final int id = 30 + k;
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + id), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + k * 115, topPos + 224, 110, 20).tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + id))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos+240, topPos+172, 110, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick(); boolean own = menu.value(25) == 1 && menu.value(26) == 1, idle = menu.value(30) == 0;
        buttons[5].active = own && idle && menu.value(3) > 0 && menu.value(6) == 1;
        buttons[10].active = own && idle && menu.value(3) > 0 && menu.value(6) == 0 && menu.value(34) == 1;
        buttons[8].active = menu.value(1) > 1;
        boolean free = own && idle && menu.value(40) == 0;
        for (int k = 0; k < 4; k++) {
            String reason = !own ? "owner" : !idle || menu.value(40) != 0 ? "busy" : menu.value(44) == 0 ? (menu.value(41) > 0 ? "cooldown" : "not_ready") : (menu.value(43) >> k & 1) == 0 ? "need_" + k : "ok";
            buttons[20 + k].active = free && reason.equals("ok");
            if (!reason.equals(lessonTip[k])) {
                lessonTip[k] = reason;
                buttons[20 + k].setTooltip(Tooltip.create(Component.translatable("pet.mcyukkuri.ui.tip." + (reason.equals("ok") ? "ok." + k : reason), menu.value(41))));
            }
        }
        buttons[30].active = own && idle && menu.value(45) > 0;
        buttons[31].active = own && idle && menu.value(46) > 0 && menu.value(53) == 0;
        buttons[32].active = own && menu.value(49) == 1;
        for (int i = 11; i < 14; i++) { buttons[i].visible = menu.value(27) == 1; buttons[i].active = own && idle && menu.value(28) == 1; }
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        card(g, leftPos + 6, topPos + 6, imageWidth - 12, 116, false);
        card(g, leftPos + 6, topPos + 196, imageWidth - 12, 26, false);
        icon(g, leftPos + imageWidth - 26, topPos + 8, ICON_PROFILE);
        if (menu.value(3) >= 1) chip(g, leftPos + 14, topPos + 21, menu.value(3));
    }
    private void line(GuiGraphics g, int y, String key, Object... args) { lineAt(g, 14, y, key, args); }
    private void lineAt(GuiGraphics g, int x, int y, String key, Object... args) { g.drawString(font, Component.translatable("badge.mcyukkuri.profile."+key, args), x, y, INK, false); }
    private static String score(int v) { return v < 0 ? "-" : Integer.toString(v); }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(menu.subjectEntityId());
        var head = Component.translatable("badge.mcyukkuri.subject", entity == null ? Component.translatable("badge.mcyukkuri.no_subject") : entity.getDisplayName(), menu.value(2), menu.value(1));
        g.drawString(font, head, 14, 10, INK, false);
        if (menu.value(58) > 0) { // title sits at the right end of the first line, clear of the grade and source lines
            var title = Component.translatable("badge.mcyukkuri.profile.title", Component.translatable("title.mcyukkuri.pet." + menu.value(58)));
            g.drawString(font, title, Math.max(14 + font.width(head) + 8, 328 - font.width(title)), 10, menu.value(58) == 2 ? GOOD : INK, false);
        }
        int grade = menu.value(3);
        Component validity = grade < 2 || menu.value(4) == 0 && menu.value(5) == 0 ? Component.translatable("badge.mcyukkuri.profile.validity.never")
                : menu.value(4) == 2 ? Component.translatable("badge.mcyukkuri.profile.validity.expired") : Component.translatable("badge.mcyukkuri.profile.validity.days", menu.value(5));
        if (grade == 0) line(g, 21, "grade_plain", GuiKit.gradeName(0));
        else lineAt(g, 14 + CHIP_WIDTH, 21, "grade", GuiKit.gradeName(grade), validity, Component.translatable("badge.mcyukkuri.wear." + menu.value(6)));
        var source = Component.translatable("badge.mcyukkuri.profile.source." + menu.value(7));
        int serial = menu.value(8) & 0xffff;
        if (grade == 0 || serial == 0) line(g, 31, "source", source); else line(g, 31, "source_no", source, String.format("No.%04X", serial));
        line(g, 41, "abilities", menu.value(9), menu.value(10), menu.value(11), menu.value(12));
        line(g, 51, "routines", menu.value(13), menu.value(14), menu.value(15));
        var pedigree = Component.translatable("badge.mcyukkuri.profile.pedigree");
        g.drawString(font, pedigree, 14, 61, INK, false); int px = 14 + font.width(pedigree) + 4;
        stars(g, px, 60, menu.value(16));
        lineAt(g, px + STARS_WIDTH + 8, 61, "growth", level(menu.value(17)), level(menu.value(18)));
        if (menu.value(19) == 0) line(g, 71, "exam_none");
        else line(g, 71, "exam", Component.translatable("badge.mcyukkuri.profile.type."+menu.value(31)), Component.translatable("badge.mcyukkuri.profile.outcome."+(menu.value(19)-1)),
                GuiKit.gradeName(menu.value(32)));
        if (menu.value(19) != 0) line(g, 81, "scores", score(menu.value(20)), score(menu.value(21)), score(menu.value(22)), score(menu.value(23)));
        int ls = menu.value(40); // one status line: what it is doing now, plus rest time and today's lessons only when they matter
        Component state = ls == 5 ? Component.translatable("pet.mcyukkuri.ui.state.awaiting")
                : ls > 0 ? Component.translatable("pet.mcyukkuri.ui.state.running", Component.translatable("pet.mcyukkuri.ui.lesson." + (ls - 1)))
                : Component.translatable(menu.value(30) == 1 ? "pet.mcyukkuri.ui.state.busy" : "pet.mcyukkuri.ui.state.idle");
        var status = Component.translatable("badge.mcyukkuri.profile.status", state);
        if (menu.value(41) > 0) status.append(" · ").append(Component.translatable("pet.mcyukkuri.ui.rest", menu.value(41)));
        if (menu.value(42) > 0) status.append(" · ").append(Component.translatable("pet.mcyukkuri.ui.today", menu.value(42)));
        g.drawString(font, status, 14, 91, INK, false);
        if (menu.value(27) == 1)
            g.drawString(font, Component.translatable("predator.mcyukkuri.control_state", Component.translatable("predator.mcyukkuri.order."+menu.value(29)),
                    Component.translatable("predator.mcyukkuri.obedient."+menu.value(28))), 14, 101, INK, false);
        else line(g, 101, "hint");
        if (menu.value(56) > 0 || menu.value(57) > 0) line(g, 111, "owner_line", level(menu.value(56)), Component.translatable("badge.mcyukkuri.profile.owner_state." + menu.value(57)));
        var candidate = minecraft.level == null || menu.value(46) == 0 ? null : minecraft.level.getEntity((menu.value(47) & 0xffff) | (menu.value(48) << 16));
        line(g, 200, "breed_partner", candidate == null ? Component.translatable("badge.mcyukkuri.no_subject") : candidate.getDisplayName(), menu.value(46), menu.value(45));
        if (menu.value(49) == 0) line(g, 211, "permit_none", menu.value(24));
        else {
            var holder = minecraft.level == null ? null : minecraft.level.getEntity((menu.value(50) & 0xffff) | (menu.value(51) << 16));
            int hours = menu.value(52); // game hours left; a permit lasts one game day
            Component left = hours >= 24 ? Component.translatable("badge.mcyukkuri.profile.permit_left.day") : Component.translatable("badge.mcyukkuri.profile.permit_left.hours", Math.max(1, hours));
            line(g, 211, "permit", holder == null ? "?" : holder.getDisplayName(), left, menu.value(24));
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial); renderTooltip(g, x, y);
        if (over(x, y, leftPos + 14 + font.width(Component.translatable("badge.mcyukkuri.profile.pedigree")) + 4, topPos + 60, STARS_WIDTH, 9))
            g.renderTooltip(font, Component.translatable("shop.mcyukkuri.pedigree_tip"), x, y);
    }
}
