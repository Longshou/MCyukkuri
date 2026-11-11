package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.pet.PetSkill;
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
    private static final int SERVICE_TOP = 111, SERVICE_ROW = 21, FOOT = 216;
    private static final int TASKS_Y = 74, TASKS_WIDTH = 308;
    private final Button[] actions = new Button[ExamMenu.ButtonId.COUNT];
    public ExamScreen(ExamMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 240; }
    @Override protected void init() {
        super.init();
        add(ExamMenu.ButtonId.PREVIOUS_SUBJECT, 8, 6, 40, 20, Component.literal("<"));
        add(ExamMenu.ButtonId.NEXT_SUBJECT, 52, 6, 40, 20, Component.literal(">"));
        for (int i = 0; i < 5; i++) add(ExamMenu.ButtonId.SIMPLE + i, 8, SERVICE_TOP + 1 + i * SERVICE_ROW, 110, 18,
                Component.translatable("exam.mcyukkuri.btn." + (i + 3)));
        add(ExamMenu.ButtonId.LOG, 150, FOOT, 116, 18, Component.translatable("exam.mcyukkuri.btn.log"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 272, topPos + FOOT, 60, 18).build(ActionButton::new));
    }
    private void add(int id, int x, int y, int w, int h, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, h).build(ActionButton::new));
    }
    @Override protected void containerTick() {
        super.containerTick();
        boolean has = menu.value(ExamMenu.Data.SUBJECT_COUNT) > 0;
        actions[ExamMenu.ButtonId.PREVIOUS_SUBJECT].active = actions[ExamMenu.ButtonId.NEXT_SUBJECT].active = menu.value(ExamMenu.Data.SUBJECT_COUNT) > 1;
        for (int i = 0; i < 5; i++) actions[ExamMenu.ButtonId.SIMPLE + i].active = has && menu.value(ExamMenu.Data.REFUSAL_BASE + i) == 0;
        actions[ExamMenu.ButtonId.LOG].active = has && menu.value(ExamMenu.Data.HAS_NOTES) != 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        icon(g, leftPos + imageWidth - 24, topPos + 6, ICON_EXAM);
        card(g, leftPos + 8, topPos + 28, 324, 80, false);
        if (menu.value(ExamMenu.Data.SUBJECT_COUNT) > 0) for (int i = 0; i < 5; i++) card(g, leftPos + 122, topPos + SERVICE_TOP + i * SERVICE_ROW, 210, 20, false);
    }
    private void line(GuiGraphics g, int y, int color, Component text) { g.drawString(font, text, 16, y, color, false); }
    private static Component grade(int id) { return GuiKit.gradeName(id); }
    private static Component skills(int mask) {
        var result = Component.empty(); boolean any = false;
        for (var skill : PetSkill.values()) if ((mask & 1 << skill.ordinal()) != 0) {
            if (any) result.append(", ");
            result.append(Component.translatable("pet.mcyukkuri.skill." + skill.key())); any = true;
        }
        return any ? result : Component.translatable("pet.mcyukkuri.profile.skills_none");
    }
    private static Component taskAreas(int mask) {
        String[] areas = {"care", "practice", "challenge"};
        var result = Component.translatable("exam.mcyukkuri.ui.tasks");
        for (int i = 0; i < areas.length; i++) result.append("  ").append(Component.translatable("exam.mcyukkuri.ui.task_area." + areas[i]))
                .append((mask >> i & 1) != 0 ? " ✓" : " ✗");
        return result;
    }
    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        if (menu.value(ExamMenu.Data.SUBJECT_COUNT) == 0) { line(g, 40, SOFT, Component.translatable("exam.mcyukkuri.ui.no_subject")); line(g, 54,
                SOFT, Component.translatable("exam.mcyukkuri.ui.no_subject_hint")); }
        else {
            int sp = menu.value(ExamMenu.Data.SPECIES), validity = menu.value(ExamMenu.Data.VALIDITY);
            var species = sp > 0 && sp <= YukkuriVariant.values().length ? Component.translatable("entity.mcyukkuri." + YukkuriVariant.values()[sp - 1].assetId()) : Component.empty();
            g.drawString(font, Component.translatable("exam.mcyukkuri.ui.subject", menu.value(ExamMenu.Data.SUBJECT_INDEX),
                    menu.value(ExamMenu.Data.SUBJECT_COUNT), species, Component.translatable("exam.mcyukkuri.ui.relation." + menu.value(ExamMenu.Data.RELATION))), 100, 12, INK, false);
            var state = validity == 0 ? Component.translatable("exam.mcyukkuri.ui.valid_forever") : validity == 2 ?
                    Component.translatable("exam.mcyukkuri.ui.expired") : Component.translatable("exam.mcyukkuri.ui.valid_days", menu.value(ExamMenu.Data.VALID_DAYS));
            chip(g, 16, 32, menu.value(ExamMenu.Data.GRADE));
            g.drawString(font, Component.translatable("exam.mcyukkuri.ui.grade", grade(menu.value(ExamMenu.Data.GRADE)),
                    menu.value(ExamMenu.Data.GRADE) == 0 ? Component.empty() : state), 16 + CHIP_WIDTH, 32, INK, false);
            Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(ExamMenu.Data.SKILL_MASK)));
            line(g, 42, INK, ellipsize(font, knownSkills, 300));
            var traits = Component.translatable("exam.mcyukkuri.profile.traits", menu.value(ExamMenu.Data.INTELLIGENCE),
                    menu.value(ExamMenu.Data.FRIENDLINESS), menu.value(ExamMenu.Data.SELF_CONTROL));
            g.drawString(font, traits, 16, 52, SOFT, false);
            var vitals = Component.translatable("exam.mcyukkuri.ui.vitals", level(menu.value(ExamMenu.Data.STRESS)));
            if (menu.value(ExamMenu.Data.UNAUTHORIZED_BIRTHS) > 0)
                    vitals.append("  ").append(Component.translatable("badge.mcyukkuri.profile.unauthorized", menu.value(ExamMenu.Data.UNAUTHORIZED_BIRTHS)));
            g.drawString(font, vitals, 16, 62, SOFT, false);
            int mask = menu.value(ExamMenu.Data.HALL_MASK);
            if ((mask & ExamMenu.HALL_FOUND) == 0) line(g, TASKS_Y, WARN, Component.translatable("exam.mcyukkuri.ui.no_hall"));
            else line(g, TASKS_Y, (mask & ExamMenu.HALL_TASK_AREAS) == ExamMenu.HALL_TASK_AREAS ? GOOD : SOFT,
                    ellipsize(font, taskAreas(mask), TASKS_WIDTH));
            if (menu.value(ExamMenu.Data.LAST_OUTCOME) == 0) line(g, 87, SOFT, Component.translatable("badge.mcyukkuri.profile.exam_none"));
            else line(g, 87, INK, Component.translatable("exam.mcyukkuri.ui.last", Component.translatable("exam.mcyukkuri.type." + menu.value(ExamMenu.Data.LAST_TYPE)),
                    Component.translatable("exam.mcyukkuri.outcome." + (menu.value(ExamMenu.Data.LAST_OUTCOME) - 1), grade(menu.value(ExamMenu.Data.LAST_GRADE)))));
            if (menu.value(ExamMenu.Data.DAYTIME) == 0) line(g, 98, WARN, Component.translatable("exam.mcyukkuri.refuse.night"));
            else if (menu.value(ExamMenu.Data.BUSY) != 0) line(g, 98, WARN, Component.translatable("exam.mcyukkuri.refuse.busy"));
        }
        for (int i = 0; i < 5; i++) {
            int code = menu.value(ExamMenu.Data.REFUSAL_BASE + i), type = i + 3, fee = ExamSession.fee(type);
            if (menu.value(ExamMenu.Data.SUBJECT_COUNT) == 0) continue;
            Component text = code == 0 ? (fee == 0 ? Component.translatable("exam.mcyukkuri.ui.free") :
                    Component.translatable("exam.mcyukkuri.ui.fee", fee)).append("  ").append(Component.translatable("exam.mcyukkuri.hint." + type))
                    : Component.translatable("exam.mcyukkuri.refuse." + ExamSession.REASONS.get(Math.min(code, ExamSession.REASONS.size() - 1)));
            var lines = font.split(text, 198); int shown = Math.min(2, lines.size()), top = SERVICE_TOP + i * SERVICE_ROW + (shown == 1 ? 6 : 1);
            for (int n = 0; n < shown; n++) g.drawString(font, lines.get(n), 128, top + n * font.lineHeight, code == 0 ? SOFT : WARN, false);
        }
        g.drawString(font, Component.translatable("exam.mcyukkuri.ui.emeralds", menu.value(ExamMenu.Data.EMERALDS)), 16, FOOT + 5, SOFT, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) {
        super.render(g, x, y, partial); renderTooltip(g, x, y);
        Component knownSkills = Component.translatable("pet.mcyukkuri.profile.skills", skills(menu.value(ExamMenu.Data.SKILL_MASK)));
        if (menu.value(ExamMenu.Data.SUBJECT_COUNT) > 0 && font.width(knownSkills) > 300 && over(x, y, leftPos + 16, topPos + 42, 300, font.lineHeight))
            g.renderTooltip(font, knownSkills, x, y);
        int mask = menu.value(ExamMenu.Data.HALL_MASK);
        if (menu.value(ExamMenu.Data.SUBJECT_COUNT) > 0 && (mask & ExamMenu.HALL_FOUND) != 0
                && over(x, y, leftPos + 16, topPos + TASKS_Y, TASKS_WIDTH, font.lineHeight)) {
            var tasks = taskAreas(mask);
            if (font.width(tasks) > TASKS_WIDTH) setTooltipForNextRenderPass(tasks);
        }
    }
}
