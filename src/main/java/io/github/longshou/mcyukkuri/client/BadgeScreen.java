package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.badge.BadgeMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class BadgeScreen extends AbstractContainerScreen<BadgeMenu> {
    private final Button[] buttons = new Button[14];
    public BadgeScreen(BadgeMenu m, Inventory inv, Component title) { super(m, inv, title); imageWidth = 360; imageHeight = 314; }
    @Override protected void init() {
        super.init();
        for (int i = 0; i < buttons.length; i++) {
            final int id = i;
            buttons[i] = addRenderableWidget(Button.builder(Component.translatable("badge.mcyukkuri.action." + i), b -> {
                if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
            }).bounds(leftPos + 10 + (i < 11 ? i%3 : i-11)*115, topPos + (i < 11 ? 190 + i/3*24 : 286), 110, 20)
                    .tooltip(Tooltip.create(Component.translatable("badge.mcyukkuri.help." + i))).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos+240, topPos+262, 110, 20).build());
    }
    @Override protected void containerTick() {
        super.containerTick(); boolean idle = menu.value(13) == 0, own = menu.value(19) == 1 && menu.value(27) == 1;
        for (var b : buttons) b.active = own && idle;
        for (int i = 0; i < 3; i++) buttons[i].active &= menu.value(16) == 0 && menu.value(7+i) < 100;
        buttons[3].active &= menu.value(3) == 1 && menu.value(4) < 1 && menu.value(16) == 0;
        buttons[4].active &= menu.value(3) == 1 && menu.value(4) < 2 && menu.value(16) == 0;
        buttons[5].active &= menu.value(4) > 0 && menu.value(5) == 1 && menu.value(20) == 1;
        buttons[6].active &= menu.value(3) == 1 && menu.value(4) > 0 && (menu.value(5) == 0 || menu.value(20) == 0);
        buttons[7].active = !own && menu.subjectEntityId() != 0 && idle;
        buttons[8].active = menu.value(1) > 1;
        buttons[9].active = own && !idle;
        buttons[10].active &= menu.value(4) > 0 && menu.value(5) == 0 && menu.value(20) == 1;
        for (int i = 11; i < 14; i++) { buttons[i].visible = menu.value(29) == 1; buttons[i].active &= menu.value(30) == 1; }
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        g.fill(leftPos, topPos, leftPos+imageWidth, topPos+imageHeight, 0xff334740);
        g.fill(leftPos+3, topPos+3, leftPos+imageWidth-3, topPos+imageHeight-3, 0xfff4ead5);
    }
    private void line(GuiGraphics g, int y, String key, Object... args) { g.drawString(font, Component.translatable("badge.mcyukkuri."+key, args), 10, y, 0xff34453e, false); }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(menu.subjectEntityId());
        line(g, 10, "subject", entity == null ? Component.translatable("badge.mcyukkuri.no_subject") : entity.getDisplayName(), menu.value(2), menu.value(1));
        line(g, 26, "certificate", Component.translatable("human.mcyukkuri.medal."+menu.value(4)), Component.translatable("badge.mcyukkuri.source."+menu.value(6)),
                Component.translatable("badge.mcyukkuri.wear."+(menu.value(20)==0?2:menu.value(5))));
        line(g, 43, "skills", menu.value(7), menu.value(8), menu.value(9));
        line(g, 59, "routines", menu.value(10), menu.value(11), menu.value(12), menu.value(21));
        line(g, 76, "requirements");
        line(g, 92, "thresholds");
        line(g, 108, "session", Component.translatable("badge.mcyukkuri.session."+menu.value(13)), menu.value(13)==4?Component.translatable("badge.mcyukkuri.stage."+menu.value(14)):Component.literal(""), menu.value(15)/20, menu.value(16));
        line(g, 124, "result", Component.translatable("badge.mcyukkuri.result."+menu.value(17)), menu.value(18));
        line(g, 140, "scores", menu.value(22), menu.value(23), menu.value(24), menu.value(25), menu.value(26));
        line(g, 157, menu.value(3)==1 ? "examiner_hint" : "home_hint");
        if (menu.value(29) == 1 && menu.value(13) != 4)
            g.drawString(font, Component.translatable("predator.mcyukkuri.control_state", Component.translatable("predator.mcyukkuri.order."+menu.value(31)),
                    Component.translatable("predator.mcyukkuri.obedient."+menu.value(30))), 10, 173, 0xff34453e, false);
        else line(g, 173, menu.value(13)==4 ? "question."+menu.value(28)+"."+menu.value(14) : "condition_hint");
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g,x,y,partial); renderTooltip(g,x,y); }
}
