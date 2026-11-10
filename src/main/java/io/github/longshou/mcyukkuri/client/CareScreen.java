package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.interaction.CareAction;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class CareScreen extends AbstractContainerScreen<CareMenu> {
    private static final int COLUMN=101, BUTTON_TOP=52;
    private long actions;
    public CareScreen(CareMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=312;imageHeight=228;
    }
    @Override protected void init(){super.init();buttons();}
    @Override protected void containerTick(){
        super.containerTick();
        if(actions!=menu.actions())buttons();
    }
    private void buttons(){
        actions=menu.actions();
        clearWidgets();
        int[] rows=new int[CareAction.GROUP_COUNT];
        for(var action:CareAction.values()){
            if((actions&(1L<<action.id()))==0)continue;
            addRenderableWidget(Button.builder(action.title(),button->minecraft.gameMode.handleInventoryButtonClick(menu.containerId,action.id()))
                    .bounds(leftPos+6+action.group*COLUMN,topPos+BUTTON_TOP+rows[action.group]++*17,COLUMN-3,16)
                    .tooltip(Tooltip.create(action.help())).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),button->onClose())
                .bounds(leftPos+107,topPos+205,98,18).build());
    }
    @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){
        graphics.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff263c3b);
        graphics.fill(leftPos+3,topPos+3,leftPos+imageWidth-3,topPos+imageHeight-3,0xffe7dfcb);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        if(menu.target()==null){
            graphics.drawString(font,title,9,7,0xff243835,false);
        }else{
            var lines=StatusStrip.lines(menu.target());
            graphics.drawString(font,font.plainSubstrByWidth(lines.getFirst().getString(),imageWidth-18),9,7,0xff243835,false);
            graphics.drawWordWrap(font,lines.get(1),9,18,imageWidth-18,0xff5b645c);
        }
        if(actions==0){
            graphics.drawCenteredString(font,Component.translatable("operation.mcyukkuri.no_actions"),imageWidth/2,66,0xff5b645c);
        }else{
            for(int group=0;group<CareAction.GROUP_COUNT;group++){
                graphics.drawString(font,Component.translatable("operation.mcyukkuri.group."+group),9+group*COLUMN,41,0xff243835,false);
            }
        }
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        super.render(graphics,mouseX,mouseY,partial);renderTooltip(graphics,mouseX,mouseY);
    }
}
