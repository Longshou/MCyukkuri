package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid=MCYukkuri.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class HatBoatRenderer extends EntityRenderer<HatBoatEntity> {
    public HatBoatRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=.45f; }
    @SubscribeEvent public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(ModEntities.HAT_BOAT.get(),HatBoatRenderer::new); }
    @SubscribeEvent public static void items(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent event) {
        event.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        },io.github.longshou.mcyukkuri.registry.ModItems.MARISA_SHELL.get());
    }
    @Override public ResourceLocation getTextureLocation(HatBoatEntity boat) { return MCYukkuri.id("textures/entity/marisa/neutral.png"); }
    /** Marisa's own hat, hollowed (marisa_hat_vessel): afloat opening up with its rim a freeboard over the water, or lying
     *  on its side on the ground (HatVessel tilt and lift; the same pose the braid clips end in). */
    @Override public void render(HatBoatEntity boat,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
        if(boat.state()==HatBoatEntity.State.CAPSIZED)pose.mulPose(Axis.ZP.rotationDegrees(150));
        float scale=boat.hullScale();
        if(boat.isCradle()){
            var mode=boat.mode();
            pose.translate(0,HatVessel.lift(mode)*scale/16,0);
            pose.mulPose(Axis.XP.rotationDegrees(-HatVessel.tilt(mode)));
            pose.mulPose(Axis.YP.rotationDegrees(mode==HatVessel.Mode.BOW?90:-90));
        }else pose.translate(0,HatVessel.FREEBOARD*scale/16,0);
        pose.pushPose();pose.scale(scale,scale,scale);
        PropModels.render("marisa_hat_vessel",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
        if(boat.isAfloat()||boat.isMoored()){
            // The well stays dry: hide the water over the opening at the water line.
            float[] o=HatVessel.opening();float k=scale/16,y=-HatVessel.FREEBOARD*k+.03f;
            var mask=buffers.getBuffer(net.minecraft.client.renderer.RenderType.waterMask());var matrix=pose.last().pose();
            for(int face=0;face<2;face++){  // both windings, seen from above or from a low angle
                float z0=face==0?o[2]*k:o[3]*k,z1=face==0?o[3]*k:o[2]*k;
                mask.addVertex(matrix,o[0]*k,y,z0);mask.addVertex(matrix,o[0]*k,y,z1);mask.addVertex(matrix,o[1]*k,y,z1);mask.addVertex(matrix,o[1]*k,y,z0);
            }
        }
        pose.popPose();
        super.render(boat,yaw,partial,pose,buffers,light);
    }
}
