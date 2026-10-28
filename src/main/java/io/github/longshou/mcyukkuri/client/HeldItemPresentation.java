package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.ParentCarry;

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

    static void bedding(PoseStack pose) {
        // The source bundle is authored lying on the ground. Put its middle between
        // the lips instead of placing its ground origin above/in front of the mouth.
        PropModels.center("bedding_bundle",pose,false);
    }

    private HeldItemPresentation() {}
}
