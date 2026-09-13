package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriVariant;
import io.github.longshou.mcyukkuri.human.HumanMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class HumanScreen extends AbstractContainerScreen<HumanMenu> {
    private final Button[] actions = new Button[22];
    public HumanScreen(HumanMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 270; }
    @Override protected void init() {
        super.init();
        for (int i = 0; i < 4; i++) {
            addAction(i, 10, 53 + i * 28, 155, Component.translatable("human.mcyukkuri.buy_supply", menu.supply(i).getHoverName(), menu.supply(i).getCount(), HumanMenu.supplyPrice(i)));
            addAction(4 + i, 175, 53 + i * 28, 155, Component.translatable("human.mcyukkuri.empty"));
        }
        for (int i = 8; i < 12; i++) addAction(i, 10 + (i % 2) * 165, 170 + ((i - 8) / 2) * 25, 155, Component.translatable("human.mcyukkuri.action." + i));
        addAction(13,10,220,100,Component.literal(""));
        addAction(14,115,220,105,Component.translatable("human.mcyukkuri.action.14"));
        addAction(15,225,220,105,Component.translatable("human.mcyukkuri.action.15"));
        addAction(16, 175, 170, 155, Component.translatable("badge.mcyukkuri.shop"));
        addAction(17,175,53,155,Component.literal(""));addAction(18,175,81,155,Component.literal(""));
        addAction(19,175,109,155,Component.translatable("human.mcyukkuri.action.19"));addAction(20,175,137,155,Component.translatable("human.mcyukkuri.action.20"));
        addAction(12, 10, 246, 155, Component.translatable("human.mcyukkuri.action.12"));
        addAction(21, 277, 31, 53, Component.translatable("human.mcyukkuri.action.21"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 175, topPos + 246, 155, 20).build());
    }
    private void addAction(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, 20).tooltip(Tooltip.create(Component.translatable("human.mcyukkuri.help." + id))).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        actions[21].visible = menu.target()!=null && menu.target().job()==io.github.longshou.mcyukkuri.human.HumanNpc.Job.SHOPKEEPER;
        actions[21].active = menu.value(53) > 1;
        for (int i = 0; i < 4; i++) {
            actions[i].setMessage(Component.translatable("human.mcyukkuri.buy_supply",menu.supply(i).getHoverName(),menu.supply(i).getCount(),HumanMenu.supplyPrice(menu.page()*4+i)));
            actions[i].active = menu.value(0) > 0 && menu.value(4) != 0;
            actions[4+i].visible=menu.value(33)<2;
            int k = 6 + i * 6; int species = menu.value(k);
            actions[4 + i].active = species > 0 && menu.value(4) != 0;
            if (species > 0 && species <= YukkuriVariant.values().length) {
                String variant = YukkuriVariant.values()[species - 1].assetId();
                actions[4 + i].setMessage(Component.translatable("human.mcyukkuri.buy_animal", Component.translatable("human.mcyukkuri.medal."+menu.value(34+i)).append(Component.translatable("entity.mcyukkuri." + variant)), menu.value(k + 1)));
                actions[4 + i].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.animal_state", menu.value(k + 2), menu.value(k + 3),
                        Component.translatable("human.mcyukkuri.stage." + menu.value(k + 4)), menu.value(k + 5))));
            } else actions[4 + i].setMessage(Component.translatable("human.mcyukkuri.empty"));
        }
        for(int id=17;id<=20;id++)actions[id].visible=menu.value(33)>=2;
        actions[17].active=actions[18].active=menu.value(33)==3&&menu.value(4)!=0;
        actions[17].setMessage(Component.translatable("human.mcyukkuri.purpose_mode",Component.translatable("human.mcyukkuri.purpose."+menu.value(39))));
        actions[18].setMessage(Component.translatable("human.mcyukkuri.sale_filter",Component.translatable("human.mcyukkuri.purpose."+menu.value(40))));
        actions[19].active=menu.value(47)!=0&&menu.value(4)!=0;actions[20].active=menu.value(41)==3&&menu.value(4)!=0;
        actions[17].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.pipeline_state",Component.translatable("human.mcyukkuri.line_status."+menu.value(41)),menu.value(44),menu.value(45),menu.value(46),menu.value(42),menu.value(43))));
        actions[13].setMessage(Component.translatable("human.mcyukkuri.page",menu.page()+1));
        actions[14].active=menu.value(31)>0&&menu.value(4)!=0;
        actions[14].setMessage(menu.value(33)==0?Component.translatable("human.mcyukkuri.action.14"):Component.translatable("human.mcyukkuri.buy_product",menu.value(38)));
        actions[14].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.production",menu.value(31),menu.value(32)).append(" / ").append(Component.translatable("human.mcyukkuri.purpose."+menu.value(48)))));
        actions[15].active=menu.value(33)>0&&menu.value(4)!=0;
        actions[15].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.ingredient."+menu.value(33))));
        actions[8].active = menu.value(2) != 0 && menu.value(4) != 0;
        actions[9].active = actions[10].active = menu.value(3) == 1 && menu.value(4) != 0;
        actions[16].visible = menu.target()!=null && menu.target().job()==io.github.longshou.mcyukkuri.human.HumanNpc.Job.SHOPKEEPER;
        actions[9].visible = !actions[16].visible;
        actions[11].active = menu.value(1) <= 248 && menu.value(4) != 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff30484a);
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xfff1e7d2);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        g.drawString(font, title, 10, 9, 0xff253c3c, false);
        g.drawString(font, Component.translatable("human.mcyukkuri.status", menu.value(0), menu.value(1)), 10, 23, 0xff54665a, false);
        g.drawString(font, Component.translatable("human.mcyukkuri.supplies"), 10, 40, 0xff253c3c, false);
        g.drawString(font, menu.value(33)>=2?Component.translatable("human.mcyukkuri.stage_progress",Component.translatable("human.mcyukkuri.pipeline_stage."+menu.value(49)),menu.value(50)):Component.translatable("human.mcyukkuri.animals_page",menu.value(52)+1,Math.max(1,menu.value(53))), 175, 40, 0xff253c3c, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); }
}
