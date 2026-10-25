package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
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
    private static final String HULL="marisa_hat_turned",WETLINE="marisa_hat_wetline";
    public HatBoatRenderer(EntityRendererProvider.Context context) { super(context); shadowRadius=.45f; }
    public static void register(EntityRenderersEvent.RegisterRenderers event) { event.registerEntityRenderer(ModEntities.HAT_BOAT.get(),HatBoatRenderer::new); }
    public static void items(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions(){
            private PropItemRenderer renderer;
            @Override public BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new PropItemRenderer();return renderer;}
        },ModItems.MARISA_SHELL.get());
    }
    @Override public ResourceLocation getTextureLocation(HatBoatEntity boat) { return MCYukkuri.id("textures/entity/marisa/neutral.png"); }
    /** Marisa's own shared hat turned upside down (larger for aquatic Marisa), its soft crown pressed along the hat's axis: on the ground
     *  until the brim stands on the squashed crown, afloat a little with the brim a freeboard over the water and the water
     *  hidden over the opening. The braid clips end with the worn hat in this same pose. */
    /** Synced brim dip: the hull tips at most 5 degrees toward the near rim. Riders go through HatBoatMotion.rider, which has
     *  no dip term, so they stay level on their own support (visually counter-balanced); this never moves a seat or collision. */
    private static void applyDip(PoseStack pose,HatBoatView boat,float partial){
        float degrees=Mth.clamp(boat.dipDegrees(partial),0,5);
        if(degrees<.01f)return;
        // dipYaw is the lowered rim's direction (-sin, 0, cos) in the hat's own frame, which is exactly the frame the pose is in here.
        // A right-handed turn about the horizontal axis (cos, 0, sin) sends that direction downward (and the far rim up).
        float toward=boat.dipYaw()*Mth.DEG_TO_RAD;
        pose.mulPose(new Quaternionf().rotationAxis(degrees*Mth.DEG_TO_RAD,Mth.cos(toward),0,Mth.sin(toward)));
    }
    @Override public void render(HatBoatEntity source,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        HatBoatView boat=source;
        if(boat.state()==HatBoatEntity.State.RESCUED)return;  // back on its owner's head (the braid clip carries it there)
        pose.pushPose();
        var heldOffset=boat.motionPosition(partial).subtract(boat.getPosition(partial));
        pose.translate(heldOffset.x,heldOffset.y,heldOffset.z);
        pose.mulPose(Axis.YP.rotationDegrees(180-HatBoatMotion.yaw(boat,partial)));
        float scale=boat.hullScale(),k=scale/16;boolean ground=boat.isCradle();
        var mode=ground?HatVessel.Mode.NEST:boat.aquaticHull()?HatVessel.Mode.AQUATIC_FLOAT:HatVessel.Mode.FLOAT;
        if(boat.state()==HatBoatEntity.State.CAPSIZED||boat.selfRighting()){
            // Turning over about the waterline would sink the brim a freeboard under; a capsized felt hat floats crown up
            // with its brim just awash, so rise by nearly that freeboard as the roll goes over.
            float roll=boat.visibleMotionRoll(partial)*Mth.DEG_TO_RAD;
            pose.translate(0,HatVessel.top(mode)*k*.45f*(1-Mth.cos(roll)),0);
        }
        HatBoatMotion.local(pose,boat,partial);
        applyDip(pose,boat,partial);
        pose.translate(0,HatVessel.top(mode)*k,0);
        pose.pushPose();pose.scale(scale,scale*boat.hullSquash(partial),scale);
        // The hull is the owner's own hat: its wear shows as the same square-cell damage as when worn.
        int damage=boat.hullDamage();
        if(damage>0)PropModels.renderHat(HULL,damage,-1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        else PropModels.render(HULL,1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        // Wet cloth from just under the floating waterline up toward the brim (not on a dry land cradle).
        if(!ground&&PropModels.has(WETLINE))PropModels.render(WETLINE,1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
        // The synchronized provisions remain visible when the loaded hat beaches or is lifted, too.
        HatCargoRenderer.render(boat,partial,pose,buffers,light);
        boolean held=source.getPassengers().stream().anyMatch(passenger->passenger instanceof YukkuriEntity captain
                &&AquaticMotionPresentation.paddleInGrip(captain,boat,partial));
        var stowed=PropModels.anchor(AquaticMotionPresentation.STICK,"stow_hull");
        if(boat.visiblePaddle()&&!held&&stowed!=null){
            // The same 3D stick the captain's braid holds, lying on the braid-side brim (paddle_stick anchors.stow_hull).
            pose.pushPose();pose.scale(scale,scale,scale);stowed.apply(pose);
            var grip=PropModels.socket(AquaticMotionPresentation.STICK);pose.translate(-grip.x,-grip.y,-grip.z);
            PropModels.render(AquaticMotionPresentation.STICK,8,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        // Enqueue the actual render transform; water-only depth follows real entity/item colors later this frame.
        if(!boat.selfRescueHeld())HatWaterMaskPass.enqueue(boat,pose);
        pose.popPose();
        super.render(source,yaw,partial,pose,buffers,light);
    }
}
