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
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Quaternionf;

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
    /** Marisa's own hat turned upside down (marisa_hat_turned), its soft crown pressed along the hat's axis: on the ground
     *  until the brim stands on the squashed crown, afloat a little with the brim a freeboard over the water and the water
     *  hidden over the opening. The braid clips end with the worn hat in this same pose. */
    /** Synced brim dip: the hull tips at most 5 degrees toward the near rim. Riders go through HatBoatMotion.rider, which has
     *  no dip term, so they stay level on their own support (visually counter-balanced); this never moves a seat or collision. */
    private static void applyDip(PoseStack pose,HatBoatEntity boat,float partial){
        float degrees=Mth.clamp(boat.dipDegrees(partial),0,5);
        if(degrees<.01f)return;
        // dipYaw is the lowered rim's direction (-sin, 0, cos) in the hat's own frame, which is exactly the frame the pose is in here.
        // A right-handed turn about the horizontal axis (cos, 0, sin) sends that direction downward (and the far rim up).
        float toward=boat.dipYaw()*Mth.DEG_TO_RAD;
        pose.mulPose(new Quaternionf().rotationAxis(degrees*Mth.DEG_TO_RAD,Mth.cos(toward),0,Mth.sin(toward)));
    }
    @Override public void render(HatBoatEntity boat,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        if(boat.state()==HatBoatEntity.State.RESCUED)return;  // back on its owner's head (the braid clip carries it there)
        pose.pushPose();
        var heldOffset=boat.motionPosition(partial).subtract(boat.getPosition(partial));
        pose.translate(heldOffset.x,heldOffset.y,heldOffset.z);
        pose.mulPose(Axis.YP.rotationDegrees(180-HatBoatMotion.yaw(boat,partial)));
        HatBoatMotion.local(pose,boat,partial);
        applyDip(pose,boat,partial);
        float scale=boat.hullScale(),k=scale/16;boolean ground=boat.isCradle();
        pose.translate(0,(ground?HatVessel.NEST_HEIGHT:HatVessel.FREEBOARD)*k,0);
        pose.pushPose();pose.scale(scale,scale*boat.hullSquash(partial),scale);
        PropModels.render("marisa_hat_turned",1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
        if(boat.isAfloat()||boat.isMoored()||boat.isCradle()||boat.capsizing())HatCargoRenderer.render(boat,pose,buffers,light);
        // Enqueue the actual render transform; water-only depth follows real entity/item colors later this frame.
        if(!boat.selfRescueHeld())HatWaterMaskPass.enqueue(boat,pose);
        pose.popPose();
        super.render(boat,yaw,partial,pose,buffers,light);
    }
}
