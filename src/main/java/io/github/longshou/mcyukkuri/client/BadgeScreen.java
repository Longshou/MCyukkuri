package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.badge.BadgeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class BadgeScreen extends AbstractContainerScreen<BadgeMenu> {
    private static final int[] BUTTONS = {5, 10, 8, 11, 12, 13};
    private final Button[] buttons = new Button[14];
    public BadgeScreen(BadgeMenu m, Inventory inv, Component title) { super(m, inv, title); imageWidth = 360; imageHeight = 250; }
    @Override protected void init() {
        super.init();
        for (int n = 0; n < BUTTONS.length; n++) {
            final int id = BUTTONS[n];
            buttons[id] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + id), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + n % 3 * 115, topPos + 176 + n / 3 * 24, 110, 20)
                    .tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + id))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos+240, topPos+224, 110, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick(); boolean own = menu.value(25) == 1 && menu.value(26) == 1, idle = menu.value(30) == 0;
        buttons[5].active = own && idle && menu.value(3) > 0 && menu.value(6) == 1;
        buttons[10].active = own && idle && menu.value(3) > 0 && menu.value(6) == 0 && menu.value(34) == 1;
        buttons[8].active = menu.value(1) > 1;
        for (int i = 11; i < 14; i++) { buttons[i].visible = menu.value(27) == 1; buttons[i].active = own && idle && menu.value(28) == 1; }
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        g.fill(leftPos, topPos, leftPos+imageWidth, topPos+imageHeight, 0xff334740);
        g.fill(leftPos+3, topPos+3, leftPos+imageWidth-3, topPos+imageHeight-3, 0xfff4ead5);
    }
    private void line(GuiGraphics g, int y, String key, Object... args) { g.drawString(font, Component.translatable("badge.mcyukkuri.profile."+key, args), 10, y, 0xff34453e, false); }
    private static String score(int v) { return v < 0 ? "-" : Integer.toString(v); }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(menu.subjectEntityId());
        g.drawString(font, Component.translatable("badge.mcyukkuri.subject", entity == null ? Component.translatable("badge.mcyukkuri.no_subject") : entity.getDisplayName(), menu.value(2), menu.value(1)), 10, 10, 0xff34453e, false);
        Component validity = menu.value(3) < 2 || menu.value(4) == 0 && menu.value(5) == 0 ? Component.translatable("badge.mcyukkuri.profile.validity.never")
                : menu.value(4) == 2 ? Component.translatable("badge.mcyukkuri.profile.validity.expired") : Component.translatable("badge.mcyukkuri.profile.validity.days", menu.value(5));
        line(g, 26, "grade", Component.translatable("badge.mcyukkuri.profile.grade."+menu.value(3)), validity, Component.translatable("badge.mcyukkuri.wear."+menu.value(6)));
        line(g, 43, "source", Component.translatable("badge.mcyukkuri.profile.source."+menu.value(7)), String.format("%04x", menu.value(8) & 0xffff));
        line(g, 59, "abilities", menu.value(9), menu.value(10), menu.value(11), menu.value(12));
        line(g, 76, "routines", menu.value(13), menu.value(14), menu.value(15));
        line(g, 92, "growth", menu.value(16), menu.value(17), menu.value(18), menu.value(24));
        if (menu.value(19) == 0) line(g, 108, "exam_none");
        else line(g, 108, "exam", Component.translatable("badge.mcyukkuri.profile.type."+menu.value(31)), Component.translatable("badge.mcyukkuri.profile.outcome."+(menu.value(19)-1)),
                Component.translatable("badge.mcyukkuri.profile.grade."+menu.value(32)));
        if (menu.value(19) != 0) line(g, 124, "scores", score(menu.value(20)), score(menu.value(21)), score(menu.value(22)), score(menu.value(23)));
        line(g, 140, menu.value(30) == 1 ? "busy" : "idle");
        if (menu.value(27) == 1)
            g.drawString(font, Component.translatable("predator.mcyukkuri.control_state", Component.translatable("predator.mcyukkuri.order."+menu.value(29)),
                    Component.translatable("predator.mcyukkuri.obedient."+menu.value(28))), 10, 156, 0xff34453e, false);
        else line(g, 156, "hint");
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g,x,y,partial); renderTooltip(g,x,y); }
}
