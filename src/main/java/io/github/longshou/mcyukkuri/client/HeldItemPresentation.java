package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Presentation of a real synchronized stack; never creates an item or grants inventory. */
final class HeldItemPresentation {
    static boolean closedGrip(YukkuriView entity) {
        String behavior=entity.getBehaviorId();
        return !entity.profile().houseBody()&&!entity.getCarriedItem().isEmpty()
                &&entity.getMouthItem().isEmpty()&&entity.getMouthBodyFood().isEmpty()&&!behavior.equals(Behaviors.FAMILY_CHEW)
                &&!behavior.equals(Behaviors.BOAT_FEED_YOUNG);
    }

    /** The stick is shown while the mouth is free: not while eating, feeding, carrying by mouth or asleep. */
    static boolean kanroken(YukkuriView entity,String behavior) {
        return entity.visibleKanroken()&&!ParentCarry.mouthCarrying(entity)&&entity.getMouthItem().isEmpty()&&entity.getMouthBodyFood().isEmpty()&&entity.getCarriedItem().isEmpty()
                &&!behavior.startsWith(Behaviors.SLEEP)&&!behavior.equals(Behaviors.WAKE)&&!behavior.equals(Behaviors.FAMILY_CHEW)&&!behavior.equals(Behaviors.FEED_CHILD)
                &&!behavior.equals(Behaviors.BOAT_FEED_YOUNG)&&PropModels.has("kanroken");
    }

    static void mouth(PoseStack pose,YukkuriModel model) {
        pose.mulPose(model.mouthAttachmentPose());
    }

    /** The real held stack at the running clip's grip; a body without a braid grip carries it at the mouth. While her braid
     *  works in a clip without its own grip keys (YukkuriModel.heldItemStowed) the stack stays in her hat and is not drawn. */
    static void held(ItemStack stack,YukkuriView entity,YukkuriModel model,PoseStack pose,MultiBufferSource buffers,int light,int overlay,float mouthScale) {
        if(model.heldItemStowed())return;
        var grip=model.itemAttachmentPose();
        pose.pushPose();
        if(grip!=null){
            pose.mulPose(grip);
            // The item sprite runs diagonally: align it with the track's +Y shaft and grip near its top.
            pose.translate(0,-5/16f,0);pose.mulPose(Axis.ZP.rotationDegrees(45));
            float scale=(float)Math.sqrt(.5);pose.scale(scale,scale,scale);
        } else {mouth(pose,model);pose.scale(mouthScale,mouthScale,mouthScale);}
        Minecraft.getInstance().getItemRenderer().renderStatic(stack,ItemDisplayContext.NONE,
                light,overlay,pose,buffers,entity.level(),entity.getId());
        pose.popPose();
    }

    static void bedding(PoseStack pose) {
        // The source bundle is authored lying on the ground. Put its middle between
        // the lips instead of placing its ground origin above/in front of the mouth.
        PropModels.center("bedding_bundle",pose,false);
    }

    private HeldItemPresentation() {}
}
