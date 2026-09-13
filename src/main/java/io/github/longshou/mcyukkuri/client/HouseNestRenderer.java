package io.github.longshou.mcyukkuri.client;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.house.HouseNestBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
public final class HouseNestRenderer implements BlockEntityRenderer<HouseNestBlockEntity>{
    public HouseNestRenderer(BlockEntityRendererProvider.Context context){}
    @Override public void render(HouseNestBlockEntity nest,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        mesh("house_crevice",.5,0,.5,1,pose,buffers,light,overlay);
        if(nest.materials()>0)mesh("nest_lining",.5,.01,.5,1,pose,buffers,light,overlay);
        if(nest.food()>0)draw(new ItemStack(ModItems.FOOD_SCRAP.get()),.32,.08,.25,.2f,nest,pose,buffers,light,overlay);
        if(nest.dirt()>0)draw(new ItemStack(ModItems.WASTE.get()),.68,.06,.55,.25f,nest,pose,buffers,light,overlay);
        // The bowl-shaped lining is lower in its centre than at its outer rim.
        for(int i=0;i<nest.eggs();i++)mesh(nest.progress()>.85f?"house_egg_cracked":"house_egg",.43+(i%2)*.14,.036,.43+(i/2)*.14,1,pose,buffers,light,overlay);
    }
    private void mesh(String name,double x,double y,double z,float scale,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(x,y,z);pose.scale(scale,scale,scale);PropModels.render(name,8,pose,buffers,light,overlay);pose.popPose();
    }
    private void draw(ItemStack item,double x,double y,double z,float scale,HouseNestBlockEntity nest,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        pose.pushPose();pose.translate(x,y,z);pose.scale(scale,scale,scale);
        Minecraft.getInstance().getItemRenderer().renderStatic(item,ItemDisplayContext.FIXED,light,overlay,pose,buffers,nest.getLevel(),0);pose.popPose();
    }
}
