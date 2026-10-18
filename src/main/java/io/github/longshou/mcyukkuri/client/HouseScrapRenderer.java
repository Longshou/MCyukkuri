package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.house.HouseEggGeometry;
import io.github.longshou.mcyukkuri.life.Facilities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** A food scrap is a ground-level crumb at the same size as a house lock grip. */
public final class HouseScrapRenderer extends ItemEntityRenderer {
    static final float HELD_SCALE=.18f;
    static String smallProp(ItemStack item){
        if(item.is(ModItems.NEST_LINT.get()))return "nest_lint";
        if(!item.is(ModItems.HOUSE_EGG.get()))return null;
        var data=item.getOrDefault(DataComponents.CUSTOM_DATA,CustomData.EMPTY).copyTag();
        return data.getInt("Progress")>88?"house_egg_cracked":"house_egg";
    }
    static float smallScale(String prop){return prop.equals("nest_lint")?.65f:HouseEggGeometry.MODEL_SCALE;}
    public HouseScrapRenderer(EntityRendererProvider.Context context){super(context);}
    @Override public void render(ItemEntity entity,float yaw,float partialTick,PoseStack pose,MultiBufferSource buffers,int light){
        if(Facilities.toy(entity.getItem())){
            String name=BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).getPath();
            pose.pushPose();
            PropModels.center(name,pose,true);
            PropModels.render(name,Integer.MAX_VALUE,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();return;
        }
        String small=smallProp(entity.getItem());
        if(small!=null){
            pose.pushPose();float scale=smallScale(small);pose.scale(scale,scale,scale);
            PropModels.center(small,pose,true);
            PropModels.render(small,8,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();return;
        }
        if(!entity.getItem().is(ModItems.FOOD_SCRAP.get())){super.render(entity,yaw,partialTick,pose,buffers,light);return;}
        pose.pushPose();pose.translate(0,.45/16,0);
        float scale=HELD_SCALE*.75f;pose.scale(scale,scale,scale);
        PropModels.center("food_scrap",pose,false);
        PropModels.render("food_scrap",8,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
