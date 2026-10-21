package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.subspecies.HatVessel;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.body.FillingResource;
import io.github.longshou.mcyukkuri.house.HouseEggGeometry;
import io.github.longshou.mcyukkuri.item.AccessoryItem;
import io.github.longshou.mcyukkuri.item.ShelterItem;
import io.github.longshou.mcyukkuri.life.InfantFood;
import io.github.longshou.mcyukkuri.play.PlayItem;
import io.github.longshou.mcyukkuri.registry.ModItems;
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
        if(stack.is(ModItems.HOUSE_EGG.get()))name=HouseScrapRenderer.smallProp(stack);
        if(InfantFood.isMash(stack))name="food_mash_serving";
        if(FillingResource.isCore(stack))name="core_filling";
        if(stack.is(ModItems.REMAINS.get())){
            var data=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            pose.pushPose();pose.translate(.5,.2,.5);
            // Inventory icons fit the slot; the carried body retains the dead individual's size.
            float scale=context==ItemDisplayContext.GUI?(RemainsRenderer.asset(data).startsWith("house_")?2.5f:.8f):RemainsRenderer.renderScale(data);
            pose.scale(scale,scale*.65f,scale);RemainsRenderer.draw(stack,data,pose,buffers,light,overlay);pose.popPose();return;
        }
        if(stack.is(ModItems.ACCESSORY_SHARD.get())){renderShard(stack,context,pose,buffers,light,overlay);return;}
        if(stack.getItem() instanceof AccessoryItem){
            String selected="accessory_"+stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getString("SourceModel");
            // The aquatic item keeps its type and source scale, but uses the same hat mesh.
            if(stack.is(ModItems.AQUATIC_MARISA_HAT.get()))name="accessory_marisa";
            else if(PropModels.has(selected))name=selected;
        }
        if(!PropModels.has(name))return;
        pose.pushPose();pose.translate(.5,.5,.5);
        boolean gui=context==ItemDisplayContext.GUI;
        float scale=gui?12/Math.max(1,PropModels.extent(name)):1;
        if(!gui&&stack.is(ModItems.HOUSE_EGG.get()))scale=HouseEggGeometry.MODEL_SCALE;
        if(!gui&&(stack.getItem() instanceof ShelterItem||stack.getItem() instanceof PlayItem))scale=12/Math.max(1,PropModels.extent(name));
        if(!gui&&(stack.getItem() instanceof AccessoryItem||name.equals("yukkuri_waste"))){
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            float size=tag.contains("SourceScale")?tag.getFloat("SourceScale"):
                    stack.is(ModItems.AQUATIC_MARISA_HAT.get())?HatVessel.AQUATIC_SCALE:1;
            if(Float.isFinite(size))scale*=Math.clamp(size,.05f,4f);
        }
        pose.scale(scale,scale,scale);PropModels.center(name,pose,false);
        if(stack.getItem() instanceof AccessoryItem&&stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag().getBoolean("Cut"))
            PropModels.renderCut(name,pose,buffers,light,overlay);
        else if(stack.getItem() instanceof AccessoryItem&&hatDamage(stack)>0)
            PropModels.renderHat(name,hatDamage(stack),-1,pose,buffers,light,overlay);
        else PropModels.render(name,name.equals("leaked_filling")?Math.min(12,stack.getCount()):8,pose,buffers,light,overlay);
        if(stack.getItem() instanceof AccessoryItem){
            var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
            YukkuriModel.renderDetachedBadge(name,tag.getInt("BadgeTier"),tag.getBoolean("Cut"),pose,buffers,light,overlay);
        }
        pose.popPose();
    }
    private static int hatDamage(ItemStack stack){
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        return tag.getInt("HatPieces")>0?3:Math.clamp(tag.getInt("HatDamage"),0,2);
    }
    /** One shared cloth patch for every piece, tinted by the source accessory's colour family. */
    private static void renderShard(ItemStack stack,ItemDisplayContext context,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        var tag=stack.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        String name=HatDamageMask.shard();
        if(!PropModels.has(name))return;
        int[] tint=HatDamageMask.tint(tag.getString("SourceModel"));
        pose.pushPose();pose.translate(.5,.5,.5);
        float scale;
        if(context==ItemDisplayContext.GUI)scale=12/Math.max(1,PropModels.extent(name));
        else{float size=tag.contains("SourceScale")?tag.getFloat("SourceScale"):1;scale=Float.isFinite(size)?Math.clamp(size,.05f,4f):1;}
        pose.scale(scale,scale,scale);
        PropModels.center(name,pose,false);
        PropModels.renderTinted(name,pose,buffers,light,overlay,tint[0],tint[1],tint[2]);
        pose.popPose();
    }
}
