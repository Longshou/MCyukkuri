package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.item.CarrierRelease;
import io.github.longshou.mcyukkuri.network.CarrierPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Which occupant leaves the basket; the server rechecks the basket and the chosen row. */
final class CarrierScreen extends Screen {
    private static final int ROW = 22, COLUMN = 150, GAP = 8, PAD = 12;
    private final CarrierPayload.Menu menu;
    private int panelLeft, panelTop, panelWidth, panelHeight;

    CarrierScreen(CarrierPayload.Menu menu) {
        super(Component.translatable("care.mcyukkuri.carrier.menu.title"));
        this.menu = menu;
    }

    static void open(CarrierPayload.Menu menu) { Minecraft.getInstance().setScreen(new CarrierScreen(menu)); }

    @Override protected void init() {
        int columns = menu.rows().size() > 6 ? 2 : 1;
        int lines = (menu.rows().size() + columns - 1) / columns;
        panelWidth = columns * COLUMN + (columns - 1) * GAP + 2 * PAD;
        panelHeight = 48 + lines * ROW + 28;
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        for (int i = 0; i < menu.rows().size(); i++) {
            var row = menu.rows().get(i);
            Component label = Component.translatable("care.mcyukkuri.carrier.menu.row",
                    row.name() == null ? row.species() : row.name(), row.stage(), row.health());
            addRenderableWidget(Button.builder(label, button -> choose(row.token()))
                    .bounds(panelLeft + PAD + i / lines * (COLUMN + GAP), panelTop + 44 + i % lines * ROW, COLUMN, 20)
                    .build(GuiKit.ActionButton::new));
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(panelLeft + (panelWidth - 80) / 2, panelTop + panelHeight - 28, 80, 20)
                .build(GuiKit.ActionButton::new));
    }

    private void choose(java.util.UUID token) {
        PacketDistributor.sendToServer(new CarrierPayload.Release(menu.offhand(), token, menu.target(), menu.pos()));
        onClose();
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partial) {
        super.renderBackground(graphics, mouseX, mouseY, partial);
        GuiKit.panel(graphics, panelLeft, panelTop, panelWidth, panelHeight);
        graphics.drawString(font, title, panelLeft + PAD, panelTop + 10, GuiKit.INK, false);
        graphics.drawString(font, Component.translatable("care.mcyukkuri.carrier.menu.hint." + hint(menu.target())),
                panelLeft + PAD, panelTop + 26, GuiKit.SOFT, false);
    }

    private static String hint(CarrierRelease.Target target) {
        return switch (target) {
            case FRONT, GROUND -> "ground";
            case TABLE -> "table";
            case FREEZER -> "freezer";
        };
    }
}
