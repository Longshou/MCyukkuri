package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.item.ItemEntity;

/** A food scrap is a ground-level crumb at the same size as a house lock grip. */
public final class HouseScrapRenderer extends ItemEntityRenderer {
    static final float HELD_SCALE=.18f;
    public HouseScrapRenderer(EntityRendererProvider.Context context){super(context);}
    @Override public void render(ItemEntity entity,float yaw,float partialTick,PoseStack pose,MultiBufferSource buffers,int light){
        if(io.github.longshou.mcyukkuri.life.Facilities.toy(entity.getItem())){
            String name=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()).getPath();
            pose.pushPose();
            PropModels.center(name,pose,true);
            PropModels.render(name,Integer.MAX_VALUE,pose,buffers,light,OverlayTexture.NO_OVERLAY);
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
