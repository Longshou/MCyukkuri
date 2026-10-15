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
    /** Marisa's own hat turned over, soft: on the ground a nest (marisa_hat_nest), afloat a deep pleated bowl
     *  (marisa_hat_vessel) whose rim is a freeboard over the water, the water hidden over its opening. */
    @Override public void render(HatBoatEntity boat,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        pose.pushPose();pose.mulPose(Axis.YP.rotationDegrees(180-yaw));
        if(boat.state()==HatBoatEntity.State.CAPSIZED)pose.mulPose(Axis.ZP.rotationDegrees(150));
        float scale=boat.hullScale(),k=scale/16;
        if(boat.isCradle()){
            pose.pushPose();pose.scale(scale,scale,scale);
            PropModels.render("marisa_hat_nest",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }else{
            pose.translate(0,HatVessel.FREEBOARD*k,0);
            pose.pushPose();pose.scale(scale,scale,scale);
            PropModels.render("marisa_hat_vessel",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
            if(boat.isAfloat()||boat.isMoored()){
                // The bowl stays dry: hide the water over its opening at the water line (a fan, both windings).
                var mask=buffers.getBuffer(net.minecraft.client.renderer.RenderType.waterMask());var matrix=pose.last().pose();
                float y=-HatVessel.FREEBOARD*k+.03f;var ring=HatVessel.mask();
                for(int i=0;i<ring.length;i++){
                    float[] a=ring[i],b=ring[(i+1)%ring.length];
                    for(int face=0;face<2;face++){
                        float[] p=face==0?a:b,q=face==0?b:a;
                        mask.addVertex(matrix,0,y,0);mask.addVertex(matrix,p[0]*k,y,p[1]*k);mask.addVertex(matrix,q[0]*k,y,q[1]*k);mask.addVertex(matrix,0,y,0);
                    }
                }
            }
        }
        pose.popPose();
        super.render(boat,yaw,partial,pose,buffers,light);
    }
}
