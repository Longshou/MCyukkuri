package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.badge.BadgeMenu;
import io.github.longshou.mcyukkuri.pet.PetSkill;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

import static io.github.longshou.mcyukkuri.client.GuiKit.*;

public final class BadgeScreen extends AbstractContainerScreen<BadgeMenu> {
    private static final int LESSON_COLUMNS = 3, AWAITING_FEEDBACK = 7;
    private static final int SHEET_HEIGHT = 236, PROFILE_X = 14, BUTTON_X = 10, BUTTON_HEIGHT = 17, ROW = 19;
    private static final int ACTION_TOP = 115, LESSON_TOP = 155, BREED_TOP = 193, BREED_BUTTON_Y = 215, DONE_WIDTH = 56;
    private static final int[] BUTTONS = {BadgeMenu.ButtonId.DETACH, BadgeMenu.ButtonId.ATTACH, BadgeMenu.ButtonId.NEXT_SUBJECT,
            BadgeMenu.ButtonId.ORDER_PEACEFUL, BadgeMenu.ButtonId.ORDER_HUNT, BadgeMenu.ButtonId.ORDER_RETURN};
    private final Button[] buttons = new Button[BadgeMenu.ButtonId.COUNT];
    private final String[] lessonTip = new String[PetSkill.values().length];
    private int scroll;

    public BadgeScreen(BadgeMenu m, Inventory inv, Component title) { super(m, inv, title); imageHeight = SHEET_HEIGHT; }
    @Override protected void init() {
        imageWidth = Math.min(360, width - (height < SHEET_HEIGHT ? 8 : 0));
        scroll = Mth.clamp(scroll, 0, Math.max(0, SHEET_HEIGHT - height));
        super.init();
        if (height < SHEET_HEIGHT) topPos = -scroll;
        int pitch = (imageWidth - 2 * BUTTON_X + 4) / 3;
        int buttonWidth = pitch - 4;
        for (int n = 0; n < BUTTONS.length; n++) {
            final int id = BUTTONS[n];
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + id), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + BUTTON_X + n % 3 * pitch, topPos + ACTION_TOP + n / 3 * ROW, buttonWidth, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + id))).build(ActionButton::new));
        }
        for (int k = 0; k < PetSkill.values().length; k++) {
            final int id = BadgeMenu.ButtonId.LESSON_WAIT + k;
            var skill = PetSkill.values()[k];
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("pet.mcyukkuri.skill." + skill.key()), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + BUTTON_X + k % LESSON_COLUMNS * pitch, topPos + LESSON_TOP + k / LESSON_COLUMNS * ROW,
                    buttonWidth, BUTTON_HEIGHT).build(ActionButton::new));
        }
        for (int k = 0; k < 3; k++) {
            final int id = BadgeMenu.ButtonId.NEXT_PARTNER + k;
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + id), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + BUTTON_X + k * pitch, topPos + BREED_BUTTON_Y, buttonWidth, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + id))).build(ActionButton::new));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(leftPos + imageWidth - BUTTON_X - DONE_WIDTH, topPos + BREED_TOP + 1, DONE_WIDTH, BUTTON_HEIGHT).build(ActionButton::new));
    }

    // Forced Unicode can round the GUI scale up beyond the usual 320 × 240 logical minimum.
    private void scrollTo(int offset) {
        int next = Mth.clamp(offset, 0, SHEET_HEIGHT - height);
        int shift = scroll - next;
        scroll = next;
        topPos += shift;
        for (var child : children()) {
            if (child instanceof Button button) button.setY(button.getY() + shift);
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (height >= SHEET_HEIGHT || vertical == 0) return super.mouseScrolled(x, y, horizontal, vertical);
        scrollTo(scroll - (int) Math.round(vertical * ROW));
        return true;
    }

    @Override public void setFocused(GuiEventListener listener) {
        super.setFocused(listener);
        if (height >= SHEET_HEIGHT || !(listener instanceof Button button)) return;
        int y = button.getY();
        if (y < 0) scrollTo(scroll + y);
        else if (y + button.getHeight() > height) scrollTo(scroll + y + button.getHeight() - height);
    }
    @Override protected void containerTick() {
        super.containerTick(); boolean own = menu.value(BadgeMenu.Data.OWNED) == 1 && menu.value(BadgeMenu.Data.NEAR) == 1, idle = menu.value(BadgeMenu.Data.BUSY) == 0;
        buttons[BadgeMenu.ButtonId.DETACH].active = own && idle && menu.value(BadgeMenu.Data.GRADE) > 0 && menu.value(BadgeMenu.Data.WORN) == 1;
        buttons[BadgeMenu.ButtonId.ATTACH].active = own && idle && menu.value(BadgeMenu.Data.GRADE) > 0 && menu.value(BadgeMenu.Data.WORN) == 0 && menu.value(BadgeMenu.Data.ACCESSORY) == 1;
        buttons[BadgeMenu.ButtonId.NEXT_SUBJECT].active = menu.value(BadgeMenu.Data.SUBJECT_COUNT) > 1;
        boolean free = own && idle && menu.value(BadgeMenu.Data.LESSON_STATE) == 0;
        for (int k = 0; k < PetSkill.values().length; k++) {
            var skill = PetSkill.values()[k];
            String reason = !own ? "owner" : !idle || menu.value(BadgeMenu.Data.LESSON_STATE) != 0 ? "busy" : menu.value(BadgeMenu.Data.READY) == 0 ?
                    (menu.value(BadgeMenu.Data.COOLDOWN_SECONDS) > 0 ? "cooldown" : "not_ready") : (menu.value(BadgeMenu.Data.SKILL_MASK) >> k & 1) != 0 ? "already_known" :
                    (menu.value(BadgeMenu.Data.LESSON_MASK) >> k & 1) == 0 ? "need_" + skill.key() : "ok." + skill.key();
            buttons[BadgeMenu.ButtonId.LESSON_WAIT + k].active = free && reason.startsWith("ok.");
            if (!reason.equals(lessonTip[k])) {
                lessonTip[k] = reason;
                String key = reason.equals("already_known") ? "pet.mcyukkuri.lesson.refuse." + reason : "pet.mcyukkuri.ui.tip." + reason;
                buttons[BadgeMenu.ButtonId.LESSON_WAIT + k].setTooltip(Tooltip.create(Component.translatable("pet.mcyukkuri.skill." + skill.key())
                        .append("\n").append(Component.translatable(key, menu.value(BadgeMenu.Data.COOLDOWN_SECONDS)))));
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
        card(g, leftPos + 6, topPos + 6, imageWidth - 12, 106, false);
        card(g, leftPos + 6, topPos + BREED_TOP - 3, imageWidth - 12, 24, false);
        icon(g, leftPos + imageWidth - 26, topPos + 8, ICON_PROFILE);
        if (menu.value(BadgeMenu.Data.GRADE) >= 1) chip(g, leftPos + PROFILE_X, topPos + 21, menu.value(BadgeMenu.Data.GRADE));
        if (height < SHEET_HEIGHT) {
            int barX = leftPos + imageWidth + 2, thumb = height * height / SHEET_HEIGHT;
            int thumbY = scroll * (height - thumb) / (SHEET_HEIGHT - height);
            g.fill(barX, 0, barX + 3, height, 0xff493d2a);
            g.fill(barX, thumbY, barX + 3, thumbY + thumb, 0xffd3b983);
        }
    }
    private void line(GuiGraphics g, int y, String key, Object... args) { lineAt(g, PROFILE_X, y, key, args); }
    private void lineAt(GuiGraphics g, int x, int y, String key, Object... args) {
        int right = imageWidth - PROFILE_X - (y >= BREED_TOP ? DONE_WIDTH : 0);
        var label = Component.translatable("badge.mcyukkuri.profile." + key, args);
        g.drawString(font, ellipsize(font, label, right - x), x, y, INK, false);
    }
    private static Component skills(int mask) {
        var result = Component.empty(); boolean any = false;
        for (var skill : PetSkill.values()) if ((mask & 1 << skill.ordinal()) != 0) {
            if (any) result.append(", ");
            result.append(Component.translatable("pet.mcyukkuri.skill." + skill.key())); any = true;
        }
        return any ? result : Component.translatable("pet.mcyukkuri.profile.skills_none");
    }
    private static Component parentBadge(int grade) {
        return grade < 0 ? Component.translatable("pet.mcyukkuri.profile.unknown") : GuiKit.gradeName(grade);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(menu.subjectEntityId());
        var head = Component.translatable("badge.mcyukkuri.subject", entity == null ? Component.translatable("badge.mcyukkuri.no_subject") :
                entity.getDisplayName(), menu.value(BadgeMenu.Data.SUBJECT_INDEX), menu.value(BadgeMenu.Data.SUBJECT_COUNT));
        int headRight = imageWidth - 30;
        if (menu.value(BadgeMenu.Data.TITLE) > 0) { // title sits at the right end of the first line, clear of the grade and source lines
            var title = Component.translatable("badge.mcyukkuri.profile.title", Component.translatable("title.mcyukkuri.pet." + menu.value(BadgeMenu.Data.TITLE)));
            var shortTitle = ellipsize(font, title, imageWidth / 2);
            int titleX = headRight - font.width(shortTitle);
            g.drawString(font, shortTitle, titleX, 10, menu.value(BadgeMenu.Data.TITLE) == 2 ? GOOD : INK, false);
            headRight = titleX - 8;
        }
        g.drawString(font, ellipsize(font, head, headRight - PROFILE_X), PROFILE_X, 10, INK, false);
        int grade = menu.value(BadgeMenu.Data.GRADE);
        Component validity = grade < 2 || menu.value(BadgeMenu.Data.VALIDITY) == 0 && menu.value(BadgeMenu.Data.VALID_DAYS) == 0 ? Component.translatable("badge.mcyukkuri.profile.validity.never")
                : menu.value(BadgeMenu.Data.VALIDITY) == 2 ? Component.translatable("badge.mcyukkuri.profile.validity.expired") :
                        Component.translatable("badge.mcyukkuri.profile.validity.days", menu.value(BadgeMenu.Data.VALID_DAYS));
        if (grade == 0) line(g, 21, "grade_plain", GuiKit.gradeName(0));
        else lineAt(g, 14 + CHIP_WIDTH, 21, "grade", GuiKit.gradeName(grade), validity, Component.translatable("badge.mcyukkuri.wear." + menu.value(BadgeMenu.Data.WORN)));
        var source = Component.translatable("badge.mcyukkuri.profile.source." + menu.value(BadgeMenu.Data.SOURCE));
        int serial = menu.value(BadgeMenu.Data.SERIAL) & 0xffff;
        if (grade == 0 || serial == 0) line(g, 31, "source", source); else line(g, 31, "source_no", source, String.format("No.%04X", serial));
        Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(BadgeMenu.Data.SKILL_MASK)));
        g.drawString(font, ellipsize(font, knownSkills, imageWidth - 2 * PROFILE_X), PROFILE_X, 41, INK, false);
        g.drawString(font, Component.translatable("pet.mcyukkuri.profile.intelligence_stress", menu.value(BadgeMenu.Data.INTELLIGENCE), level(menu.value(BadgeMenu.Data.STRESS))), 14, 51, INK, false);
        if (menu.value(BadgeMenu.Data.LAST_OUTCOME) == 0) line(g, 61, "exam_none");
        else line(g, 61, "exam", Component.translatable("badge.mcyukkuri.profile.type."+menu.value(BadgeMenu.Data.LAST_TYPE)),
                Component.translatable("badge.mcyukkuri.profile.outcome."+(menu.value(BadgeMenu.Data.LAST_OUTCOME)-1)),
                GuiKit.gradeName(menu.value(BadgeMenu.Data.LAST_GRADE)));
        int ls = menu.value(BadgeMenu.Data.LESSON_STATE); // one status line: what it is doing now, plus rest time and today's lessons only when they matter
        Component state = ls == AWAITING_FEEDBACK ? Component.translatable("pet.mcyukkuri.ui.state.awaiting")
                : ls > 0 ? Component.translatable("pet.mcyukkuri.ui.state.running", Component.translatable("pet.mcyukkuri.skill." + PetSkill.values()[ls - 1].key()))
                : Component.translatable(menu.value(BadgeMenu.Data.BUSY) == 1 ? "pet.mcyukkuri.ui.state.busy" : "pet.mcyukkuri.ui.state.idle");
        var status = Component.translatable("badge.mcyukkuri.profile.status", state);
        if (menu.value(BadgeMenu.Data.COOLDOWN_SECONDS) > 0) status.append(" · ").append(Component.translatable("pet.mcyukkuri.ui.rest", menu.value(BadgeMenu.Data.COOLDOWN_SECONDS)));
        if (menu.value(BadgeMenu.Data.LESSONS_TODAY) > 0) status.append(" · ").append(Component.translatable("pet.mcyukkuri.ui.today", menu.value(BadgeMenu.Data.LESSONS_TODAY)));
        g.drawString(font, ellipsize(font, status, imageWidth - 2 * PROFILE_X), PROFILE_X, 71, INK, false);
        if (menu.value(BadgeMenu.Data.PREDATOR) == 1)
            g.drawString(font, Component.translatable("predator.mcyukkuri.control_state", Component.translatable("predator.mcyukkuri.order."+menu.value(BadgeMenu.Data.PREDATOR_ORDER)),
                    Component.translatable("predator.mcyukkuri.obedient."+menu.value(BadgeMenu.Data.OBEDIENT))), PROFILE_X, 81, INK, false);
        else line(g, 81, "hint");
        if (menu.value(BadgeMenu.Data.NEGLECT) > 0 || menu.value(BadgeMenu.Data.OWNER_STATE) > 0) line(g, 91, "owner_line",
                level(menu.value(BadgeMenu.Data.NEGLECT)), Component.translatable("badge.mcyukkuri.profile.owner_state." + menu.value(BadgeMenu.Data.OWNER_STATE)));
        g.drawString(font, Component.translatable("pet.mcyukkuri.profile.parent_badges", parentBadge(menu.value(BadgeMenu.Data.MOTHER_BADGE)),
                parentBadge(menu.value(BadgeMenu.Data.FATHER_BADGE))), PROFILE_X, 101, INK, false);
        var candidate = minecraft.level == null || menu.value(BadgeMenu.Data.PARTNER_INDEX) == 0 ? null :
                minecraft.level.getEntity((menu.value(BadgeMenu.Data.PARTNER_ID_LOW) & 0xffff) | (menu.value(BadgeMenu.Data.PARTNER_ID_HIGH) << 16));
        line(g, BREED_TOP, "breed_partner", candidate == null ? Component.translatable("badge.mcyukkuri.no_subject") : candidate.getDisplayName(),
                menu.value(BadgeMenu.Data.PARTNER_INDEX), menu.value(BadgeMenu.Data.PARTNER_COUNT));
        if (menu.value(BadgeMenu.Data.PERMIT_ACTIVE) == 0) line(g, BREED_TOP + 10, "permit_none", menu.value(BadgeMenu.Data.UNAUTHORIZED_BIRTHS));
        else {
            var holder = minecraft.level == null ? null : minecraft.level.getEntity((menu.value(BadgeMenu.Data.PERMIT_ID_LOW) & 0xffff) | (menu.value(BadgeMenu.Data.PERMIT_ID_HIGH) << 16));
            int hours = menu.value(BadgeMenu.Data.PERMIT_HOURS); // game hours left; a permit lasts one game day
            Component left = hours >= 24 ? Component.translatable("badge.mcyukkuri.profile.permit_left.day") : Component.translatable("badge.mcyukkuri.profile.permit_left.hours", Math.max(1, hours));
            line(g, BREED_TOP + 10, "permit", holder == null ? "?" : holder.getDisplayName(), left, menu.value(BadgeMenu.Data.UNAUTHORIZED_BIRTHS));
        }
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial); renderTooltip(g, x, y);
        Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(BadgeMenu.Data.SKILL_MASK)));
        int room = imageWidth - 2 * PROFILE_X;
        if (font.width(knownSkills) > room && over(x, y, leftPos + PROFILE_X, topPos + 41, room, font.lineHeight)) g.renderTooltip(font, knownSkills, x, y);
    }
}
