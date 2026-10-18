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
        String name=BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if(stack.is(io.github.longshou.mcyukkuri.registry.ModItems.HOUSE_EGG.get()))name=HouseScrapRenderer.smallProp(stack);
        if(io.github.longshou.mcyukkuri.life.InfantFood.isMash(stack))name="food_mash_serving";
        if(io.github.longshou.mcyukkuri.body.FillingResource.isCore(stack))name="core_filling";
        if(stack.is(io.github.longshou.mcyukkuri.registry.ModItems.REMAINS.get())){
            var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            pose.pushPose();pose.translate(.5,.2,.5);
            // Inventory icons fit the slot; the carried body retains the dead individual's size.
            float scale=context==ItemDisplayContext.GUI?(RemainsRenderer.asset(data).startsWith("house_")?2.5f:.8f):RemainsRenderer.renderScale(data);
            pose.scale(scale,scale*.65f,scale);RemainsRenderer.draw(stack,data,pose,buffers,light,overlay);pose.popPose();return;
        }
        if(stack.is(io.github.longshou.mcyukkuri.registry.ModItems.ACCESSORY_SHARD.get())){renderShard(stack,context,pose,buffers,light,overlay);return;}
        if(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem){
            String selected="accessory_"+stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("SourceModel");
            if(PropModels.has(selected))name=selected;
        }
        if(!PropModels.has(name))return;
        pose.pushPose();pose.translate(.5,.5,.5);
        boolean gui=context==ItemDisplayContext.GUI;
        float scale=gui?12/Math.max(1,PropModels.extent(name)):1;
        if(!gui&&stack.is(io.github.longshou.mcyukkuri.registry.ModItems.HOUSE_EGG.get()))scale=io.github.longshou.mcyukkuri.house.HouseEggGeometry.MODEL_SCALE;
        if(!gui&&(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.ShelterItem||stack.getItem() instanceof io.github.longshou.mcyukkuri.play.PlayItem))scale=12/Math.max(1,PropModels.extent(name));
        if(!gui&&(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem||name.equals("yukkuri_waste"))){
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            float size=tag.contains("SourceScale")?tag.getFloat("SourceScale"):1;
            if(Float.isFinite(size))scale*=Math.clamp(size,.05f,4f);
        }
        pose.scale(scale,scale,scale);PropModels.center(name,pose,false);
        if(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem&&stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getBoolean("Cut"))
            PropModels.renderCut(name,pose,buffers,light,overlay);
        else if(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem&&hatDamage(stack)>0)
            PropModels.renderHat(name,hatDamage(stack),-1,pose,buffers,light,overlay);
        else PropModels.render(name,name.equals("leaked_filling")?Math.min(12,stack.getCount()):8,pose,buffers,light,overlay);
        if(stack.getItem() instanceof io.github.longshou.mcyukkuri.item.AccessoryItem){
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            YukkuriModel.renderDetachedBadge(name,tag.getInt("BadgeTier"),tag.getBoolean("Cut"),pose,buffers,light,overlay);
        }
        pose.popPose();
    }
    private static int hatDamage(ItemStack stack){return Math.clamp(stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getInt("HatDamage"),0,2);}
    /** A shard is drawn as that piece of its owner's own accessory, centred on itself (HatDamageMask pieces). */
    private static void renderShard(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        String name="accessory_"+tag.getString("SourceModel");
        if(!PropModels.has(name))name="accessory_"+tag.getString("Variant");
        if(!PropModels.has(name))name="accessory_reimu";
        if(!PropModels.has(name))return;
        int piece=Math.max(0,tag.getInt("Piece"));
        // A piece region that holds no triangle of this accessory borrows the next piece, so a shard is never invisible.
        float[] b=null;
        for(int i=0;i<3&&b==null;i++){b=PropModels.hatBounds(name,0,piece+i);if(b!=null)piece+=i;}
        if(b==null)return;
        pose.pushPose();pose.translate(.5,.5,.5);
        float extent=Math.max(b[3]-b[0],Math.max(b[4]-b[1],b[5]-b[2]));
        float scale;
        if(context==ItemDisplayContext.GUI)scale=12/Math.max(1,extent);
        else{float size=tag.contains("SourceScale")?tag.getFloat("SourceScale"):1;scale=Float.isFinite(size)?Math.clamp(size,.05f,4f):1;}
        pose.scale(scale,scale,scale);
        pose.translate(-(b[0]+b[3])/32,-(b[1]+b[4])/32,-(b[2]+b[5])/32);
        PropModels.renderHat(name,0,piece,pose,buffers,light,overlay);
        pose.popPose();
    }
}
