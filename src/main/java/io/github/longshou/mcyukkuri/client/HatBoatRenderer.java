package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.client.aquatic.BoatPose;
import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.registry.ModItems;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

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
     *  hidden over the opening. The braid clips end with the worn hat in this same pose. The hull, its larder, the stowed stick
     *  and the water mask all stand in the boat's one frame for this render frame (client/aquatic BoatPose), which every rider
     *  is drawn in too. */
    @Override public void render(HatBoatEntity source,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        HatBoatView boat=source;
        if(boat.state()==HatBoatEntity.State.RESCUED)return;  // back on its owner's head (the braid clip carries it there)
        var frame=BoatPose.of(boat,partial);
        pose.pushPose();
        frame.applyHull(pose,boat.getPosition(partial));
        float scale=frame.scale;boolean ground=boat.isCradle();
        pose.translate(0,frame.top()*frame.unit,0);
        pose.pushPose();pose.scale(scale,scale*frame.squash,scale);
        // The hull is the owner's own hat: its wear shows as the same square-cell damage as when worn.
        int damage=boat.hullDamage();
        if(damage>0)PropModels.renderHat(HULL,damage,-1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        else PropModels.render(HULL,1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        // Wet cloth from just under the floating waterline up toward the brim (not on a dry land cradle).
        if(!ground&&PropModels.has(WETLINE))PropModels.render(WETLINE,1,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
        // The synchronized provisions remain visible when the loaded hat beaches or is lifted, too.
        HatCargoRenderer.render(frame,pose,buffers,light);
        var stowed=PropModels.anchor(STICK,"stow_hull");
        if(boat.stick()==HatBoatView.Stick.HULL_STOW&&stowed!=null&&PropModels.has(STICK)){
            // The same 3D stick the captain's braid holds, lying on the braid-side brim (paddle_stick anchors.stow_hull).
            pose.pushPose();pose.scale(scale,scale,scale);stowed.apply(pose);
            var grip=PropModels.socket(STICK);pose.translate(-grip.x,-grip.y,-grip.z);
            PropModels.render(STICK,8,pose,buffers,light,OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        // Enqueue the actual render transform; water-only depth follows real entity/item colors later this frame.
        if(!boat.selfRescueHeld())HatWaterMaskPass.enqueue(frame,pose);
        pose.popPose();
        super.render(source,yaw,partial,pose,buffers,light);
    }

    /**
     * The real stick in the captain's braid tuft (the server synced it there): placed by the clip's own props.paddle keys (the
     * same keys the previews read); a clip without them holds it at the braid tip.
     */
    static void renderBraidStick(YukkuriModel model,String clip,float seconds,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(!PropModels.has(STICK))return;
        var socket=model.clipHasProp(clip,PADDLE_SLOT)?model.propPose(clip,PADDLE_SLOT,seconds):model.boneFrame("hand_right_tip");
        if(socket==null)return;
        pose.pushPose();pose.mulPose(socket);
        var grip=PropModels.socket(STICK);pose.translate(-grip.x,-grip.y,-grip.z);
        PropModels.render(STICK,8,pose,buffers,light,overlay);
        pose.popPose();
    }
    static final String STICK="paddle_stick",PADDLE_SLOT="paddle";
}
