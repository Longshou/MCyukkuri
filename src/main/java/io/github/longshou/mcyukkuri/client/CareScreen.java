package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Vector2i;

public final class CareScreen extends AbstractContainerScreen<CareMenu> {
    private static final int MARGIN = 6, GAP = 5, CARD_PAD = 4, SECTION_HEAD = 15, FOOT_HEIGHT = 16;
    private static final int SECTIONS_TOP = MARGIN + StatusStrip.CARD_HEIGHT + GAP;
    private final int[] rows = new int[CareAction.GROUP_COUNT];
    private final int[] sectionX = new int[CareAction.GROUP_COUNT];
    private int columnWidth, rowPitch;
    private long actions;

    public CareScreen(CareMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
    }

    @Override protected void init() {
        super.init();
        buttons();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (actions != menu.actions()) buttons();
    }

    private static Component heading(int group) {
        return Component.translatable("operation.mcyukkuri.group." + group);
    }

    private int sectionHeight(int rowCount) {
        return SECTION_HEAD + rowCount * rowPitch + CARD_PAD;
    }

    private void buttons() {
        actions = menu.actions();
        clearWidgets();
        Arrays.fill(rows, 0);
        int idealColumn = 106;
        for (var action : CareAction.values()) {
            if ((actions & (1L << action.id())) == 0) continue;
            rows[action.group]++;
            idealColumn = Math.max(idealColumn, font.width(action.title()) + 2 * CARD_PAD + 10);
        }
        int groups = 0, longest = 0;
        for (int count : rows) {
            if (count > 0) groups++;
            longest = Math.max(longest, count);
        }
        int available = width - 4 * MARGIN;
        int gaps = Math.max(0, groups - 1) * GAP;
        columnWidth = Math.min(Math.min(132, idealColumn), (available - gaps) / Math.max(1, groups));
        int contentWidth = groups * columnWidth + gaps;
        imageWidth = Math.min(width - 2 * MARGIN, Math.max(218, contentWidth + 2 * MARGIN));

        int footer = FOOT_HEIGHT + GAP + MARGIN;
        int rowSpace = height - SECTIONS_TOP - footer - sectionHeight(0);
        rowPitch = Math.min(19, Math.max(10, rowSpace / Math.max(1, longest)));
        int sectionsHeight = longest == 0 ? 22 : sectionHeight(longest);
        imageHeight = SECTIONS_TOP + sectionsHeight + footer;
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;

        int x = (imageWidth - contentWidth) / 2;
        for (int group = 0; group < rows.length; group++) {
            if (rows[group] == 0) continue;
            sectionX[group] = x;
            x += columnWidth + GAP;
        }
        int[] nextRow = new int[rows.length];
        for (var action : CareAction.values()) {
            if ((actions & (1L << action.id())) == 0) continue;
            int group = action.group;
            addRenderableWidget(Button.builder(action.title(), button -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action.id()))
                    .bounds(leftPos + sectionX[group] + CARD_PAD, topPos + SECTIONS_TOP + SECTION_HEAD + nextRow[group]++ * rowPitch,
                            columnWidth - 2 * CARD_PAD, rowPitch - 1)
                    .tooltip(Tooltip.create(action.help())).build(GuiKit.ActionButton::new));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(leftPos + (imageWidth - 82) / 2, topPos + imageHeight - MARGIN - FOOT_HEIGHT, 82, FOOT_HEIGHT)
                .build(GuiKit.ActionButton::new));
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        GuiKit.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int group = 0; group < rows.length; group++) {
            if (rows[group] > 0) {
                GuiKit.card(g, leftPos + sectionX[group], topPos + SECTIONS_TOP, columnWidth, sectionHeight(rows[group]), false);
            }
        }
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int headerWidth = imageWidth - 2 * MARGIN;
        if (menu.target() == null) {
            GuiKit.statusCard(g, MARGIN, MARGIN, headerWidth, StatusStrip.CARD_HEIGHT);
            var name = Component.literal(title.getString());
            g.drawString(font, GuiKit.ellipsize(font, name, headerWidth - 12), MARGIN + 6, MARGIN + 6, GuiKit.INK, false);
        } else {
            var fullName = StatusStrip.of(menu.target()).renderCard(g, font, MARGIN, MARGIN, headerWidth, StatusStrip.CARD_HEIGHT);
            if (fullName != null && GuiKit.over(mouseX, mouseY, leftPos + MARGIN, topPos + MARGIN, headerWidth, 18)) {
                setTooltipForNextRenderPass(fullName);
            }
        }
        if (actions == 0) {
            var notice = Component.translatable("operation.mcyukkuri.no_actions");
            g.drawString(font, notice, (imageWidth - font.width(notice)) / 2, SECTIONS_TOP + 7, GuiKit.SOFT, false);
        } else {
            for (int group = 0; group < rows.length; group++) {
                if (rows[group] > 0) g.drawString(font, heading(group), sectionX[group] + CARD_PAD + 2, SECTIONS_TOP + 4, GuiKit.INK, false);
            }
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override public void setTooltipForNextRenderPass(List<FormattedCharSequence> lines,
                                                      ClientTooltipPositioner vanilla, boolean override) {
        super.setTooltipForNextRenderPass(lines, (w, h, x, y, tipW, tipH) -> {
            var position = vanilla.positionTooltip(w, h, x, y, tipW, tipH);
            // Keep vanilla horizontal placement and leave room for the tooltip border above the headings.
            int aboveSections = topPos + SECTIONS_TOP - tipH - 6;
            return new Vector2i(position.x(), Math.max(4, Math.min(position.y(), aboveSections)));
        }, override);
    }
}
