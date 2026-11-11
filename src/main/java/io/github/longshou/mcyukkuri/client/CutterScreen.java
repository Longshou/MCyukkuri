package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The knife's three targets share one held item. */
final class CutterScreen extends AbstractContainerScreen<CareMenu> {
    private static final int MARGIN = 8, ROW = 20;
    private long actions;
    private int cardHeight;

    CutterScreen(CareMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);
        imageWidth=182;
    }

    @Override protected void init(){super.init();buttons();}

    @Override protected void containerTick(){
        super.containerTick();
        if(actions!=menu.actions())buttons();
    }

    private void buttons(){
        actions=menu.actions();
        clearWidgets();
        cardHeight = 22 + Math.max(1, Long.bitCount(actions)) * ROW;
        imageHeight = cardHeight + 3 * MARGIN + 16;
        topPos = (height - imageHeight) / 2;
        int y=topPos+MARGIN+20;
        for(var action:CareAction.values()){
            if((actions&(1L<<action.id()))==0)continue;
            addRenderableWidget(Button.builder(action.title(),button->minecraft.gameMode.handleInventoryButtonClick(menu.containerId,action.id()))
                    .bounds(leftPos+MARGIN+5,y,imageWidth-2*(MARGIN+5),17)
                    .tooltip(Tooltip.create(action.help())).build(GuiKit.ActionButton::new));
            y+=ROW;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),button->onClose())
                .bounds(leftPos+(imageWidth-82)/2,topPos+imageHeight-MARGIN-16,82,16).build(GuiKit.ActionButton::new));
    }

    @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){
        GuiKit.panel(graphics,leftPos,topPos,imageWidth,imageHeight);
        GuiKit.card(graphics,leftPos+MARGIN,topPos+MARGIN,imageWidth-2*MARGIN,cardHeight,false);
    }

    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        int x = MARGIN + 5;
        graphics.drawString(font,GuiKit.ellipsize(font,title,imageWidth-2*x),x,MARGIN+6,GuiKit.INK,false);
        if(actions==0)graphics.drawWordWrap(font,Component.translatable("operation.mcyukkuri.no_actions"),x,MARGIN+20,imageWidth-2*x,GuiKit.SOFT);
    }
}
