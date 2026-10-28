package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.joml.Quaternionf;

public final class HatBoatRenderer extends EntityRenderer<HatBoatEntity> {
    private static final String HULL="marisa_hat_turned";
    public HatBoatRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=.45f; }
    public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(ModEntities.HAT_BOAT.get(),HatBoatRenderer::new); }
    public static void items(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        },ModItems.MARISA_SHELL.get());
    }
    @Override public ResourceLocation getTextureLocation(HatBoatEntity hat) { return MCYukkuri.id("textures/entity/marisa/neutral.png"); }
    /** Marisa's own shared hat turned upside down (larger for aquatic Marisa), its soft crown pressed along the hat's axis:
     *  on the ground until the brim stands on the squashed crown. The braid clips end with the worn hat in this same pose. */
    @Override public void render(HatBoatEntity source,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        HatBoatView hat=source;
        if(hat.state()==HatBoatEntity.State.RESCUED)return;  // back on its owner's head (the braid clip carries it there)
        float scale=hat.hullScale(), unit=scale/16;
        var at=hat.getPosition(partial);
        pose.pushPose();
        pose.translate(at.x-source.getX(),at.y-source.getY(),at.z-source.getZ());
        pose.mulPose(new Quaternionf().rotationY((180-Mth.rotLerp(partial,hat.previousYaw(),hat.getYRot()))*Mth.DEG_TO_RAD));
        pose.translate(0,HatVessel.top()*unit,0);
        pose.scale(scale,scale*(float)HatVessel.squash(),scale);
        // The hull is the owner's own hat: its wear shows as the same square-cell damage as when worn.
        int damage=hat.hullDamage();
        if(damage>0)PropModels.renderHat(HULL,damage,-1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        else PropModels.render(HULL,1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
        super.render(source,yaw,partial,pose,buffers,light);
    }
}
