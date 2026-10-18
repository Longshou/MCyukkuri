package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.human.HumanMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class HumanScreen extends AbstractContainerScreen<HumanMenu> {
    private final Button[] actions = new Button[HumanMenu.ButtonId.COUNT];
    public HumanScreen(HumanMenu menu, Inventory inv, Component title) { super(menu, inv, title); imageWidth = 340; imageHeight = 270; }
    @Override protected void init() {
        super.init();
        for (int i = 0; i < 4; i++) {
            addAction(i, 10, 53 + i * 28, 155, Component.translatable("human.mcyukkuri.buy_supply", menu.supply(i).getHoverName(), menu.supply(i).getCount(), HumanMenu.supplyPrice(i)));
        }
        for (int i = HumanMenu.ButtonId.DELIVER_INPUT; i < HumanMenu.ButtonId.GUIDE; i++) addAction(i, 10 + (i % 2) * 165, 170 + ((i - HumanMenu.ButtonId.DELIVER_INPUT) / 2) * 25, 155, Component.translatable("human.mcyukkuri.action." + i));
        addAction(HumanMenu.ButtonId.NEXT_PAGE,10,220,100,Component.literal(""));
        addAction(HumanMenu.ButtonId.BUY_PRODUCT,115,220,105,Component.translatable("human.mcyukkuri.action.14"));
        addAction(HumanMenu.ButtonId.REPLENISH,225,220,105,Component.translatable("human.mcyukkuri.action.15"));
        addAction(HumanMenu.ButtonId.CYCLE_PURPOSE,175,53,155,Component.literal(""));addAction(HumanMenu.ButtonId.CYCLE_SALES,175,81,155,Component.literal(""));
        addAction(HumanMenu.ButtonId.DELIVER_PARENT,175,109,155,Component.translatable("human.mcyukkuri.action.19"));addAction(HumanMenu.ButtonId.REPAIR,175,137,155,Component.translatable("human.mcyukkuri.action.20"));
        addAction(HumanMenu.ButtonId.GUIDE, 10, 246, 155, Component.translatable("human.mcyukkuri.action.12"));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(leftPos + 175, topPos + 246, 155, 20).build());
    }
    private void addAction(int id, int x, int y, int w, Component title) {
        actions[id] = addRenderableWidget(Button.builder(title, b -> { if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); })
                .bounds(leftPos + x, topPos + y, w, 20).tooltip(Tooltip.create(Component.translatable("human.mcyukkuri.help." + id))).build());
    }
    @Override protected void containerTick() {
        super.containerTick();
        for (int i = 0; i < 4; i++) {
            actions[i].setMessage(Component.translatable("human.mcyukkuri.buy_supply",menu.supply(i).getHoverName(),menu.supply(i).getCount(),HumanMenu.supplyPrice(menu.page()*4+i)));
            actions[i].active = menu.value(HumanMenu.Data.TRADES_LEFT) > 0 && menu.value(HumanMenu.Data.DAYTIME) != 0;
        }
        for(int id=HumanMenu.ButtonId.CYCLE_PURPOSE;id<=HumanMenu.ButtonId.REPAIR;id++)actions[id].visible=menu.value(HumanMenu.Data.PRODUCTION_KIND)>=2;
        actions[HumanMenu.ButtonId.CYCLE_PURPOSE].active=actions[HumanMenu.ButtonId.CYCLE_SALES].active=menu.value(HumanMenu.Data.PRODUCTION_KIND)==3&&menu.value(HumanMenu.Data.DAYTIME)!=0;
        actions[HumanMenu.ButtonId.CYCLE_PURPOSE].setMessage(Component.translatable("human.mcyukkuri.purpose_mode",Component.translatable("human.mcyukkuri.purpose."+menu.value(HumanMenu.Data.PURPOSE_MODE))));
        actions[HumanMenu.ButtonId.CYCLE_SALES].setMessage(Component.translatable("human.mcyukkuri.sale_filter",Component.translatable("human.mcyukkuri.purpose."+menu.value(HumanMenu.Data.SALE_FILTER))));
        actions[HumanMenu.ButtonId.DELIVER_PARENT].active=menu.value(HumanMenu.Data.PARENT_READY)!=0&&menu.value(HumanMenu.Data.DAYTIME)!=0;actions[HumanMenu.ButtonId.REPAIR].active=menu.value(HumanMenu.Data.LINE_STATUS)==3&&menu.value(HumanMenu.Data.DAYTIME)!=0;
        actions[HumanMenu.ButtonId.CYCLE_PURPOSE].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.pipeline_state",Component.translatable("human.mcyukkuri.line_status."+menu.value(HumanMenu.Data.LINE_STATUS)),menu.value(HumanMenu.Data.BREEDERS),menu.value(HumanMenu.Data.NURSERY),menu.value(HumanMenu.Data.INPUT),menu.value(HumanMenu.Data.INSPECTED),menu.value(HumanMenu.Data.HELD))));
        actions[HumanMenu.ButtonId.NEXT_PAGE].setMessage(Component.translatable("human.mcyukkuri.page",menu.page()+1));
        actions[HumanMenu.ButtonId.BUY_PRODUCT].active=menu.value(HumanMenu.Data.OUTPUT)>0&&menu.value(HumanMenu.Data.DAYTIME)!=0;
        actions[HumanMenu.ButtonId.BUY_PRODUCT].setMessage(menu.value(HumanMenu.Data.PRODUCTION_KIND)==0?Component.translatable("human.mcyukkuri.action.14"):Component.translatable("human.mcyukkuri.buy_product",menu.value(HumanMenu.Data.PRODUCT_PRICE)));
        actions[HumanMenu.ButtonId.BUY_PRODUCT].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.production",menu.value(HumanMenu.Data.OUTPUT),menu.value(HumanMenu.Data.MATERIAL)).append(" / ").append(Component.translatable("human.mcyukkuri.purpose."+menu.value(HumanMenu.Data.PRODUCT_PURPOSE)))));
        actions[HumanMenu.ButtonId.REPLENISH].active=menu.value(HumanMenu.Data.PRODUCTION_KIND)>0&&menu.value(HumanMenu.Data.DAYTIME)!=0;
        actions[HumanMenu.ButtonId.REPLENISH].setTooltip(Tooltip.create(Component.translatable("human.mcyukkuri.ingredient."+menu.value(HumanMenu.Data.PRODUCTION_KIND))));
        actions[HumanMenu.ButtonId.DELIVER_INPUT].active = menu.value(HumanMenu.Data.INPUT_READY) != 0 && menu.value(HumanMenu.Data.DAYTIME) != 0;
        actions[HumanMenu.ButtonId.ACCEPT_QUEST].active = actions[HumanMenu.ButtonId.RETURN_LOST].active = menu.value(HumanMenu.Data.QUEST_STATE) == 1 && menu.value(HumanMenu.Data.DAYTIME) != 0;
        actions[HumanMenu.ButtonId.DONATE].active = menu.value(HumanMenu.Data.CARE_FOOD) <= 248 && menu.value(HumanMenu.Data.DAYTIME) != 0;
    }
    @Override protected void renderBg(GuiGraphics g, float partial, int x, int y) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xff30484a);
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth - 3, topPos + imageHeight - 3, 0xfff1e7d2);
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        g.drawString(font, title, 10, 9, 0xff253c3c, false);
        g.drawString(font, Component.translatable("human.mcyukkuri.status", menu.value(HumanMenu.Data.TRADES_LEFT), menu.value(HumanMenu.Data.CARE_FOOD)), 10, 23, 0xff54665a, false);
        g.drawString(font, Component.translatable("human.mcyukkuri.supplies"), 10, 40, 0xff253c3c, false);
        if (menu.value(HumanMenu.Data.PRODUCTION_KIND)>=2) g.drawString(font, Component.translatable("human.mcyukkuri.stage_progress",Component.translatable("human.mcyukkuri.pipeline_stage."+menu.value(HumanMenu.Data.STAGE)),menu.value(HumanMenu.Data.WORK)), 175, 40, 0xff253c3c, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float partial) { super.render(g, x, y, partial); renderTooltip(g, x, y); }
}
