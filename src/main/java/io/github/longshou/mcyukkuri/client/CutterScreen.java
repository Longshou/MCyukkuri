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
    private long actions;

    CutterScreen(CareMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);
        imageWidth=168;
        imageHeight=106;
    }

    @Override protected void init(){super.init();buttons();}

    @Override protected void containerTick(){
        super.containerTick();
        if(actions!=menu.actions())buttons();
    }

    private void buttons(){
        actions=menu.actions();
        clearWidgets();
        int y=topPos+20;
        for(var action:CareAction.values()){
            if((actions&(1L<<action.id()))==0)continue;
            addRenderableWidget(Button.builder(action.title(),button->minecraft.gameMode.handleInventoryButtonClick(menu.containerId,action.id()))
                    .bounds(leftPos+8,y,imageWidth-16,18).tooltip(Tooltip.create(action.help())).build());
            y+=20;
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),button->onClose())
                .bounds(leftPos+8,topPos+82,imageWidth-16,18).build());
    }

    @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){
        graphics.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff263c3b);
        graphics.fill(leftPos+3,topPos+3,leftPos+imageWidth-3,topPos+imageHeight-3,0xffe7dfcb);
    }

    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        graphics.drawString(font,title,8,7,0xff243835,false);
        if(actions==0)graphics.drawWordWrap(font,Component.translatable("operation.mcyukkuri.no_actions"),8,27,imageWidth-16,0xff5b645c);
    }
}
