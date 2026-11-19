package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.badge.BadgeRequirements.Need;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.pet.BadgeGrade;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu.ButtonId;
import io.github.longshou.mcyukkuri.pet.exam.ExamMenu.Data;
import io.github.longshou.mcyukkuri.pet.exam.ExamSession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import static io.github.longshou.mcyukkuri.client.GuiKit.*;

public final class ExamScreen extends AbstractContainerScreen<ExamMenu> {
    private static final int CERTIFY_Y = 65, REISSUE_Y = 107, FOOT = 140;
    private final Button[] actions = new Button[ButtonId.COUNT];

    public ExamScreen(ExamMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 340;
        imageHeight = 164;
    }

    @Override protected void init() {
        super.init();
        add(ButtonId.PREVIOUS, 8, 6, 28, Component.literal("<"));
        add(ButtonId.NEXT, 40, 6, 28, Component.literal(">"));
        add(ButtonId.CERTIFY, 12, CERTIFY_Y + 3, 138, Component.translatable("exam.mcyukkuri.btn.certify"));
        add(ButtonId.REISSUE, 12, REISSUE_Y + 3, 138, Component.translatable("exam.mcyukkuri.btn.reissue"));
        add(ButtonId.LOG, 150, FOOT, 116, Component.translatable("exam.mcyukkuri.btn.log"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(leftPos + 272, topPos + FOOT, 60, 18).build(ActionButton::new));
        updateButtons();
    }

    private void add(int id, int x, int y, int width, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> {
            if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }).bounds(leftPos + x, topPos + y, width, 18).build(ActionButton::new));
    }

    @Override protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    private void updateButtons() {
        boolean has = menu.value(Data.SUBJECT_COUNT) > 0;
        actions[ButtonId.PREVIOUS].active = actions[ButtonId.NEXT].active = menu.value(Data.SUBJECT_COUNT) > 1;
        actions[ButtonId.CERTIFY].active = has && menu.value(Data.CERTIFY_REFUSAL) == 0;
        actions[ButtonId.REISSUE].active = has && menu.value(Data.REISSUE_REFUSAL) == 0;
        actions[ButtonId.LOG].active = has && menu.value(Data.LAST_OUTCOME) != 0;
        if (has) {
            var target = BadgeGrade.of(menu.value(Data.TARGET));
            actions[ButtonId.CERTIFY].setMessage(Component.translatable("exam.mcyukkuri.ui.certify", gradeName(target.id()), ExamSession.fee(target)));
            actions[ButtonId.REISSUE].setMessage(Component.translatable("exam.mcyukkuri.ui.reissue", ExamSession.REISSUE_FEE));
        }
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        panel(g, leftPos, topPos, imageWidth, imageHeight);
        icon(g, leftPos + imageWidth - 24, topPos + 6, ICON_EXAM);
        card(g, leftPos + 8, topPos + 28, 324, 31, false);
        if (menu.value(Data.SUBJECT_COUNT) > 0) {
            card(g, leftPos + 8, topPos + CERTIFY_Y, 324, 38, false);
            card(g, leftPos + 8, topPos + REISSUE_Y, 324, 24, false);
        }
    }

    @Override protected void renderLabels(GuiGraphics g, int mx, int my) {
        if (menu.value(Data.SUBJECT_COUNT) == 0) {
            g.drawString(font, Component.translatable("exam.mcyukkuri.ui.no_subject"), 16, 33, SOFT, false);
            g.drawString(font, Component.translatable("exam.mcyukkuri.ui.no_subject_hint"), 16, 45, SOFT, false);
        } else {
            int speciesId = menu.value(Data.SPECIES);
            var species = speciesId == 0 ? Component.empty()
                    : Component.translatable("entity.mcyukkuri." + YukkuriVariant.values()[speciesId - 1].assetId());
            var subject = Component.translatable("exam.mcyukkuri.ui.subject", menu.value(Data.SUBJECT_INDEX),
                    menu.value(Data.SUBJECT_COUNT), species, Component.translatable("exam.mcyukkuri.ui.relation." + menu.value(Data.RELATION)));
            g.drawString(font, ellipsize(font, subject, 232), 76, 11, INK, false);
            int grade = menu.value(Data.GRADE), outcome = menu.value(Data.LAST_OUTCOME) - 1;
            chip(g, 16, 33, grade);
            g.drawString(font, gradeName(grade), 16 + CHIP_WIDTH, 33, INK, false);
            var last = outcome < 0 ? Component.translatable("badge.mcyukkuri.profile.exam_none")
                    : Component.translatable("exam.mcyukkuri.outcome." + outcome, gradeName(menu.value(Data.LAST_GRADE)));
            g.drawString(font, ellipsize(font, last, 300), 16, 45, SOFT, false);
            refusal(g, Data.CERTIFY_REFUSAL, CERTIFY_Y + 7);
            refusal(g, Data.REISSUE_REFUSAL, REISSUE_Y + 7);
            int need = menu.value(Data.NEED);
            if (need != 0 && grade != BadgeGrade.PLATINUM.id())
                g.drawString(font, ellipsize(font, Component.translatable("exam.mcyukkuri.need." + Need.values()[need - 1].key()), 300),
                        16, CERTIFY_Y + 25, WARN, false);
        }
        g.drawString(font, Component.translatable("exam.mcyukkuri.ui.emeralds", menu.value(Data.EMERALDS)), 16, FOOT + 5, SOFT, false);
    }

    private void refusal(GuiGraphics g, int slot, int y) {
        int code = menu.value(slot);
        if (code == 0) return;
        var reason = Component.translatable("exam.mcyukkuri.refuse." + ExamSession.REASONS.get(code));
        var lines = font.split(reason, 166);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), 156, y + i * font.lineHeight, WARN, false);
    }
}
