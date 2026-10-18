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
    private int group;
    public CareScreen(CareMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);imageWidth=304;imageHeight=240;
        for(var action:CareAction.values())if(action.tool!=null&&inventory.player.getMainHandItem().is(action.tool.item())){group=action.group;break;}
    }
    @Override protected void init(){super.init();buttons();}
    private void buttons(){
        clearWidgets();
        for(int i=0;i<CareAction.GROUP_COUNT;i++){
            final int selected=i;
            var tab=Button.builder(Component.translatable("operation.mcyukkuri.group."+i),button->{group=selected;buttons();})
                    .bounds(leftPos+8+i*58,topPos+31,56,20).build();tab.active=group!=i;addRenderableWidget(tab);
        }
        int row=0;
        for(var action:CareAction.values())if(action.group==group&&action!=CareAction.RELEASE){
            var button=Button.builder(action.title(),clicked->{
                if(minecraft.gameMode!=null)minecraft.gameMode.handleInventoryButtonClick(menu.containerId,action.id());
            }).bounds(leftPos+9+(row%2)*146,topPos+58+(row/2)*25,140,22)
                    .tooltip(Tooltip.create(Component.translatable("operation.mcyukkuri.help."+action.key()))).build();
            if(action.tool!=null)button.active=minecraft.player.getMainHandItem().is(action.tool.item());
            addRenderableWidget(button);row++;
        }
        addRenderableWidget(Button.builder(CareAction.RELEASE.title(),button->{
            if(minecraft.gameMode!=null)minecraft.gameMode.handleInventoryButtonClick(menu.containerId,CareAction.RELEASE.id());
        }).bounds(leftPos+9,topPos+208,140,22).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"),button->onClose()).bounds(leftPos+155,topPos+208,140,22).build());
    }
    @Override protected void renderBg(GuiGraphics graphics,float partial,int mouseX,int mouseY){
        graphics.fill(leftPos,topPos,leftPos+imageWidth,topPos+imageHeight,0xff263c3b);
        graphics.fill(leftPos+3,topPos+3,leftPos+imageWidth-3,topPos+imageHeight-3,0xffe7dfcb);
    }
    @Override protected void renderLabels(GuiGraphics graphics,int mouseX,int mouseY){
        graphics.drawString(font,title,9,9,0xff243835,false);
        if(menu.target()!=null)graphics.drawString(font,Component.translatable("operation.mcyukkuri.health",Math.round(menu.target().getHealth())),9,20,0xff5b645c,false);
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        super.render(graphics,mouseX,mouseY,partial);renderTooltip(graphics,mouseX,mouseY);
    }
}
