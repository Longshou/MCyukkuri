package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.badge.BadgeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import static io.github.longshou.mcyukkuri.client.GuiKit.*;

public final class BadgeScreen extends AbstractContainerScreen<BadgeMenu> {
    private static final int[] BUTTONS = {BadgeMenu.ButtonId.DETACH, BadgeMenu.ButtonId.ATTACH, BadgeMenu.ButtonId.NEXT_SUBJECT,
            BadgeMenu.ButtonId.ORDER_PEACEFUL, BadgeMenu.ButtonId.ORDER_HUNT, BadgeMenu.ButtonId.ORDER_RETURN};
    private final Button[] buttons = new Button[BadgeMenu.ButtonId.COUNT];
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
            final int id = BadgeMenu.ButtonId.LESSON_MANNERS + k;
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("pet.mcyukkuri.ui.lesson." + k), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + k * 58, topPos + 172, 55, 20).build());
        }
        for (int k = 0; k < 3; k++) {
            final int id = BadgeMenu.ButtonId.NEXT_PARTNER + k;
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + id), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + k * 115, topPos + 224, 110, 20).tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + id))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos+240, topPos+172, 110, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick(); boolean own = menu.value(BadgeMenu.Data.OWNED) == 1 && menu.value(BadgeMenu.Data.NEAR) == 1, idle = menu.value(BadgeMenu.Data.BUSY) == 0;
        buttons[BadgeMenu.ButtonId.DETACH].active = own && idle && menu.value(BadgeMenu.Data.GRADE) > 0 && menu.value(BadgeMenu.Data.WORN) == 1;
        buttons[BadgeMenu.ButtonId.ATTACH].active = own && idle && menu.value(BadgeMenu.Data.GRADE) > 0 && menu.value(BadgeMenu.Data.WORN) == 0 && menu.value(BadgeMenu.Data.ACCESSORY) == 1;
        buttons[BadgeMenu.ButtonId.NEXT_SUBJECT].active = menu.value(BadgeMenu.Data.SUBJECT_COUNT) > 1;
        boolean free = own && idle && menu.value(BadgeMenu.Data.LESSON_STATE) == 0;
        for (int k = 0; k < 4; k++) {
            String reason = !own ? "owner" : !idle || menu.value(BadgeMenu.Data.LESSON_STATE) != 0 ? "busy" : menu.value(BadgeMenu.Data.READY) == 0 ?
                    (menu.value(BadgeMenu.Data.COOLDOWN_SECONDS) > 0 ? "cooldown" : "not_ready") : (menu.value(BadgeMenu.Data.LESSON_MASK) >> k & 1) == 0 ? "need_" + k : "ok";
            buttons[BadgeMenu.ButtonId.LESSON_MANNERS + k].active = free && reason.equals("ok");
            if (!reason.equals(lessonTip[k])) {
                lessonTip[k] = reason;
                buttons[BadgeMenu.ButtonId.LESSON_MANNERS + k].setTooltip(Tooltip.create(Component.translatable("pet.mcyukkuri.ui.tip." +
                        (reason.equals("ok") ? "ok." + k : reason), menu.value(BadgeMenu.Data.COOLDOWN_SECONDS))));
            }
        }
        buttons[BadgeMenu.ButtonId.NEXT_PARTNER].active = own && idle && menu.value(BadgeMenu.Data.PARTNER_COUNT) > 0;
        buttons[BadgeMenu.ButtonId.PERMIT].active = own && idle && menu.value(BadgeMenu.Data.PARTNER_INDEX) > 0 && menu.value(BadgeMenu.Data.PARTNER_PERMITTED) == 0;
        buttons[BadgeMenu.ButtonId.REVOKE].active = own && menu.value(BadgeMenu.Data.PERMIT_ACTIVE) == 1;
        for (int i = BadgeMenu.ButtonId.ORDER_PEACEFUL; i < BadgeMenu.ButtonId.ORDER_END; i++) { buttons[i].visible =
                menu.value(BadgeMenu.Data.PREDATOR) == 1; buttons[i].active = own && idle && menu.value(BadgeMenu.Data.OBEDIENT) == 1; }
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        card(g, leftPos + 6, topPos + 6, imageWidth - 12, 116, false);
        card(g, leftPos + 6, topPos + 196, imageWidth - 12, 26, false);
        icon(g, leftPos + imageWidth - 26, topPos + 8, ICON_PROFILE);
        if (menu.value(BadgeMenu.Data.GRADE) >= 1) chip(g, leftPos + 14, topPos + 21, menu.value(BadgeMenu.Data.GRADE));
    }
    private void line(GuiGraphics g, int y, String key, Object... args) { lineAt(g, 14, y, key, args); }
    private void lineAt(GuiGraphics g, int x, int y, String key, Object... args) { g.drawString(font, Component.translatable("badge.mcyukkuri.profile."+key, args), x, y, INK, false); }
    private static String score(int v) { return v < 0 ? "-" : Integer.toString(v); }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(menu.subjectEntityId());
        var head = Component.translatable("badge.mcyukkuri.subject", entity == null ? Component.translatable("badge.mcyukkuri.no_subject") :
                entity.getDisplayName(), menu.value(BadgeMenu.Data.SUBJECT_INDEX), menu.value(BadgeMenu.Data.SUBJECT_COUNT));
        g.drawString(font, head, 14, 10, INK, false);
        if (menu.value(BadgeMenu.Data.TITLE) > 0) { // title sits at the right end of the first line, clear of the grade and source lines
            var title = Component.translatable("badge.mcyukkuri.profile.title", Component.translatable("title.mcyukkuri.pet." + menu.value(BadgeMenu.Data.TITLE)));
            g.drawString(font, title, Math.max(14 + font.width(head) + 8, 328 - font.width(title)), 10, menu.value(BadgeMenu.Data.TITLE) == 2 ? GOOD : INK, false);
        }
        int grade = menu.value(BadgeMenu.Data.GRADE);
        Component validity = grade < 2 || menu.value(BadgeMenu.Data.VALIDITY) == 0 && menu.value(BadgeMenu.Data.VALID_DAYS) == 0 ? Component.translatable("badge.mcyukkuri.profile.validity.never")
                : menu.value(BadgeMenu.Data.VALIDITY) == 2 ? Component.translatable("badge.mcyukkuri.profile.validity.expired") :
                        Component.translatable("badge.mcyukkuri.profile.validity.days", menu.value(BadgeMenu.Data.VALID_DAYS));
        if (grade == 0) line(g, 21, "grade_plain", GuiKit.gradeName(0));
        else lineAt(g, 14 + CHIP_WIDTH, 21, "grade", GuiKit.gradeName(grade), validity, Component.translatable("badge.mcyukkuri.wear." + menu.value(BadgeMenu.Data.WORN)));
        var source = Component.translatable("badge.mcyukkuri.profile.source." + menu.value(BadgeMenu.Data.SOURCE));
        int serial = menu.value(BadgeMenu.Data.SERIAL) & 0xffff;
        if (grade == 0 || serial == 0) line(g, 31, "source", source); else line(g, 31, "source_no", source, String.format("No.%04X", serial));
        line(g, 41, "abilities", menu.value(BadgeMenu.Data.MANNERS), menu.value(BadgeMenu.Data.ACADEMICS), menu.value(BadgeMenu.Data.WITS), menu.value(BadgeMenu.Data.POISE));
        line(g, 51, "routines", menu.value(BadgeMenu.Data.BOWL_ROUTINE), menu.value(BadgeMenu.Data.TOILET_ROUTINE), menu.value(BadgeMenu.Data.SLEEP_ROUTINE));
        var pedigree = Component.translatable("badge.mcyukkuri.profile.pedigree");
        g.drawString(font, pedigree, 14, 61, INK, false); int px = 14 + font.width(pedigree) + 4;
        stars(g, px, 60, menu.value(BadgeMenu.Data.PEDIGREE));
        lineAt(g, px + STARS_WIDTH + 8, 61, "growth", level(menu.value(BadgeMenu.Data.STRESS)), level(menu.value(BadgeMenu.Data.INDULGENCE)));
        if (menu.value(BadgeMenu.Data.LAST_OUTCOME) == 0) line(g, 71, "exam_none");
        else line(g, 71, "exam", Component.translatable("badge.mcyukkuri.profile.type."+menu.value(BadgeMenu.Data.LAST_TYPE)),
                Component.translatable("badge.mcyukkuri.profile.outcome."+(menu.value(BadgeMenu.Data.LAST_OUTCOME)-1)),
                GuiKit.gradeName(menu.value(BadgeMenu.Data.LAST_GRADE)));
        if (menu.value(BadgeMenu.Data.LAST_OUTCOME) != 0) line(g, 81, "scores", score(menu.value(BadgeMenu.Data.LAST_MANNERS)),
                score(menu.value(BadgeMenu.Data.LAST_ACADEMICS)), score(menu.value(BadgeMenu.Data.LAST_WITS)), score(menu.value(BadgeMenu.Data.LAST_POISE)));
        int ls = menu.value(BadgeMenu.Data.LESSON_STATE); // one status line: what it is doing now, plus rest time and today's lessons only when they matter
        Component state = ls == 5 ? Component.translatable("pet.mcyukkuri.ui.state.awaiting")
                : ls > 0 ? Component.translatable("pet.mcyukkuri.ui.state.running", Component.translatable("pet.mcyukkuri.ui.lesson." + (ls - 1)))
                : Component.translatable(menu.value(BadgeMenu.Data.BUSY) == 1 ? "pet.mcyukkuri.ui.state.busy" : "pet.mcyukkuri.ui.state.idle");
        var status = Component.translatable("badge.mcyukkuri.profile.status", state);
        if (menu.value(BadgeMenu.Data.COOLDOWN_SECONDS) > 0) status.append(" · ").append(Component.translatable("pet.mcyukkuri.ui.rest", menu.value(BadgeMenu.Data.COOLDOWN_SECONDS)));
        if (menu.value(BadgeMenu.Data.LESSONS_TODAY) > 0) status.append(" · ").append(Component.translatable("pet.mcyukkuri.ui.today", menu.value(BadgeMenu.Data.LESSONS_TODAY)));
        g.drawString(font, status, 14, 91, INK, false);
        if (menu.value(BadgeMenu.Data.PREDATOR) == 1)
            g.drawString(font, Component.translatable("predator.mcyukkuri.control_state", Component.translatable("predator.mcyukkuri.order."+menu.value(BadgeMenu.Data.PREDATOR_ORDER)),
                    Component.translatable("predator.mcyukkuri.obedient."+menu.value(BadgeMenu.Data.OBEDIENT))), 14, 101, INK, false);
        else line(g, 101, "hint");
        if (menu.value(BadgeMenu.Data.NEGLECT) > 0 || menu.value(BadgeMenu.Data.OWNER_STATE) > 0) line(g, 111, "owner_line",
                level(menu.value(BadgeMenu.Data.NEGLECT)), Component.translatable("badge.mcyukkuri.profile.owner_state." + menu.value(BadgeMenu.Data.OWNER_STATE)));
        var candidate = minecraft.level == null || menu.value(BadgeMenu.Data.PARTNER_INDEX) == 0 ? null :
                minecraft.level.getEntity((menu.value(BadgeMenu.Data.PARTNER_ID_LOW) & 0xffff) | (menu.value(BadgeMenu.Data.PARTNER_ID_HIGH) << 16));
        line(g, 200, "breed_partner", candidate == null ? Component.translatable("badge.mcyukkuri.no_subject") : candidate.getDisplayName(),
                menu.value(BadgeMenu.Data.PARTNER_INDEX), menu.value(BadgeMenu.Data.PARTNER_COUNT));
        if (menu.value(BadgeMenu.Data.PERMIT_ACTIVE) == 0) line(g, 211, "permit_none", menu.value(BadgeMenu.Data.UNAUTHORIZED_BIRTHS));
        else {
            var holder = minecraft.level == null ? null : minecraft.level.getEntity((menu.value(BadgeMenu.Data.PERMIT_ID_LOW) & 0xffff) | (menu.value(BadgeMenu.Data.PERMIT_ID_HIGH) << 16));
            int hours = menu.value(BadgeMenu.Data.PERMIT_HOURS); // game hours left; a permit lasts one game day
            Component left = hours >= 24 ? Component.translatable("badge.mcyukkuri.profile.permit_left.day") : Component.translatable("badge.mcyukkuri.profile.permit_left.hours", Math.max(1, hours));
            line(g, 211, "permit", holder == null ? "?" : holder.getDisplayName(), left, menu.value(BadgeMenu.Data.UNAUTHORIZED_BIRTHS));
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial); renderTooltip(g, x, y);
        if (over(x, y, leftPos + 14 + font.width(Component.translatable("badge.mcyukkuri.profile.pedigree")) + 4, topPos + 60, STARS_WIDTH, 9))
            g.renderTooltip(font, Component.translatable("shop.mcyukkuri.pedigree_tip"), x, y);
    }
}
