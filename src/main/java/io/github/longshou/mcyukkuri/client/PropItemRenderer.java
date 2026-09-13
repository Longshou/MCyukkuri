package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class PropItemRenderer extends BlockEntityWithoutLevelRenderer {
    public PropItemRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    @Override public void renderByItem(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        String name=BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();if(!PropModels.has(name))return;
        pose.pushPose();pose.translate(.5,.5,.5);
        boolean gui=context==ItemDisplayContext.GUI;
        float scale=gui?12/Math.max(1,PropModels.extent(name)):1;
        if(!gui&&(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.ShelterItem||stack.getItem() instanceof io.github.longshou.mcyukkuri.play.PlayItem))scale=12/Math.max(1,PropModels.extent(name));
        if(!gui&&(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem||name.equals("yukkuri_waste"))){
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            float size=tag.contains("SourceScale")?tag.getFloat("SourceScale"):1;
            if(Float.isFinite(size))scale*=Math.clamp(size,.05f,2f);
        }
        pose.scale(scale,scale,scale);PropModels.center(name,pose,false);
        if(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem&&stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getBoolean("Cut"))
            PropModels.renderCut(name,pose,buffers,light,overlay);
        else PropModels.render(name,name.equals("leaked_filling")?Math.min(12,stack.getCount()):8,pose,buffers,light,overlay);
        if(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem){
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            YukkuriModel.renderDetachedBadge(name,tag.getInt("BadgeTier"),tag.getBoolean("Cut"),pose,buffers,light,overlay);
        }
        pose.popPose();
    }
}
