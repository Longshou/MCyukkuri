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
    private static final int TAB_HEIGHT = 19;
    private enum Page { ACTIONS, OBSERVE, LINEAGE }
    private final int[] rows = new int[CareAction.GROUP_COUNT];
    private final int[] sectionX = new int[CareAction.GROUP_COUNT];
    private final CareInspection inspection;
    private int columnWidth, rowPitch, contentTop, contentHeight, sectionsTop;
    private long actions;
    private Page page = Page.ACTIONS;
    private boolean pageChanged;

    public CareScreen(CareMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        inspection = new CareInspection(menu);
    }

    @Override protected void init() {
        super.init();
        buttons();
    }

    @Override protected void containerTick() {
        super.containerTick();
        if (pageChanged || page == Page.ACTIONS && actions != menu.actions()) {
            pageChanged = false;
            buttons();
        }
    }

    private static Component heading(int group) {
        return Component.translatable("operation.mcyukkuri.group." + group);
    }

    private int sectionHeight(int rowCount) {
        return SECTION_HEAD + rowCount * rowPitch + CARD_PAD;
    }

    private void buttons() {
        actions = menu.actions();
        setFocused(null);
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
        imageWidth = Math.min(width - 2 * MARGIN, Math.max(menu.cutter() ? 218 : 366, contentWidth + 2 * MARGIN));

        contentTop = MARGIN + (menu.cutter() ? 0 : TAB_HEIGHT + GAP);
        sectionsTop = contentTop + StatusStrip.CARD_HEIGHT + GAP;
        int footer = FOOT_HEIGHT + GAP + MARGIN;
        int rowSpace = height - 2 * MARGIN - sectionsTop - footer - sectionHeight(0);
        rowPitch = Math.min(19, Math.max(10, rowSpace / Math.max(1, longest)));
        int sectionsHeight = longest == 0 ? 22 : sectionHeight(longest);
        contentHeight = StatusStrip.CARD_HEIGHT + GAP + sectionsHeight;
        if (!menu.cutter()) contentHeight = Math.max(contentHeight, Math.min(126, height - contentTop - footer - 2 * MARGIN));
        imageHeight = contentTop + contentHeight + footer;
        leftPos = (width - imageWidth) / 2;
        topPos = (height - imageHeight) / 2;

        if (!menu.cutter()) {
            tab(Page.ACTIONS, "actions", GuiKit.ICON_STATUS);
            tab(Page.OBSERVE, "observe", GuiKit.ICON_LIVE);
            tab(Page.LINEAGE, "lineage", GuiKit.ICON_PROFILE);
        }
        if (page == Page.ACTIONS) actionButtons(contentWidth);
        int footerY = topPos + imageHeight - MARGIN - FOOT_HEIGHT;
        if (page == Page.OBSERVE) {
            addRenderableWidget(Button.builder(CareInspection.text("reset"), button -> inspection.reset())
                    .bounds(leftPos + MARGIN, footerY, Math.min(112, (imageWidth - 82) / 2 - MARGIN - GAP), FOOT_HEIGHT)
                    .build(GuiKit.ActionButton::new));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(leftPos + (imageWidth - 82) / 2, footerY, 82, FOOT_HEIGHT).build(GuiKit.ActionButton::new));
    }

    private void tab(Page target, String key, int icon) {
        int tabWidth = (imageWidth - 2 * MARGIN - 2 * GAP) / 3;
        var tab = new GuiKit.Tab(leftPos + MARGIN + target.ordinal() * (tabWidth + GAP), topPos + MARGIN,
                tabWidth, TAB_HEIGHT, CareInspection.text(key), icon, button -> {
                    page = target;
                    inspection.changeTab();
                    pageChanged = true;
                });
        tab.active = page != target;
        addRenderableWidget(tab);
    }

    private void actionButtons(int contentWidth) {
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
                    .bounds(leftPos + sectionX[group] + CARD_PAD, topPos + sectionsTop + SECTION_HEAD + nextRow[group]++ * rowPitch,
                            columnWidth - 2 * CARD_PAD, rowPitch - 1)
                    .tooltip(Tooltip.create(action.help())).build(GuiKit.ActionButton::new));
        }
    }

    @Override protected void renderBg(GuiGraphics g, float partial, int mouseX, int mouseY) {
        GuiKit.panel(g, leftPos, topPos, imageWidth, imageHeight);
        if (page != Page.ACTIONS) {
            inspection.render(g, font, leftPos + MARGIN, topPos + contentTop, imageWidth - 2 * MARGIN, contentHeight, page == Page.LINEAGE);
            return;
        }
        for (int group = 0; group < rows.length; group++) {
            if (rows[group] > 0) {
                GuiKit.card(g, leftPos + sectionX[group], topPos + sectionsTop, columnWidth, sectionHeight(rows[group]), false);
            }
        }
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        if (page != Page.ACTIONS) return;
        int headerWidth = imageWidth - 2 * MARGIN;
        if (menu.target() == null) {
            GuiKit.statusCard(g, MARGIN, contentTop, headerWidth, StatusStrip.CARD_HEIGHT);
            var name = Component.literal(title.getString());
            g.drawString(font, GuiKit.ellipsize(font, name, headerWidth - 12), MARGIN + 6, contentTop + 6, GuiKit.INK, false);
        } else {
            var fullName = StatusStrip.of(menu.target()).renderCard(g, font, MARGIN, contentTop, headerWidth, StatusStrip.CARD_HEIGHT);
            if (fullName != null && GuiKit.over(mouseX, mouseY, leftPos + MARGIN, topPos + contentTop, headerWidth, 18)) {
                setTooltipForNextRenderPass(fullName);
            }
        }
        if (actions == 0) {
            var notice = Component.translatable("operation.mcyukkuri.no_actions");
            g.drawString(font, notice, (imageWidth - font.width(notice)) / 2, sectionsTop + 7, GuiKit.SOFT, false);
        } else {
            for (int group = 0; group < rows.length; group++) {
                if (rows[group] > 0) g.drawString(font, heading(group), sectionX[group] + CARD_PAD + 2, sectionsTop + 4, GuiKit.INK, false);
            }
        }
    }

    private boolean overContent(double x, double y, int w) {
        return x >= leftPos + MARGIN && x < leftPos + MARGIN + w
                && y >= topPos + contentTop && y < topPos + contentTop + contentHeight;
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        if (page == Page.OBSERVE && button == 0 && overContent(x, y, CareInspection.previewWidth(imageWidth - 2 * MARGIN))) {
            inspection.startDrag();
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return (button == 0 && inspection.drag(dx, dy)) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        return (button == 0 && inspection.endDrag()) || super.mouseReleased(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        int w = imageWidth - 2 * MARGIN;
        if (page != Page.ACTIONS && vertical != 0 && overContent(x, y, w)) {
            if (page == Page.OBSERVE && overContent(x, y, CareInspection.previewWidth(w))) inspection.zoom(vertical);
            else inspection.scroll(vertical);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
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
            int aboveSections = topPos + sectionsTop - tipH - 6;
            return new Vector2i(position.x(), Math.max(4, Math.min(position.y(), aboveSections)));
        }, override);
    }
}
