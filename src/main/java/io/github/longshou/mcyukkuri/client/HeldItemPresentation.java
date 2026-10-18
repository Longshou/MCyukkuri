package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Presentation of a real synchronized stack; never creates an item or grants inventory. */
final class HeldItemPresentation {
    static boolean closedGrip(YukkuriEntity entity) {
        String behavior=entity.getBehaviorId();
        return !entity.getVariant().isHouse()&&!entity.getCarriedItem().isEmpty()
                &&entity.getMouthItem().isEmpty()&&!behavior.equals("family_chew")
                &&!behavior.equals("boat_feed_young");
    }

    /** The stick is shown while the mouth is free: not while eating, feeding, carrying by mouth or asleep. */
    static boolean kanroken(YukkuriEntity entity,String behavior) {
        return entity.visibleKanroken()&&entity.getMouthItem().isEmpty()&&entity.getCarriedItem().isEmpty()&&entity.aquaticServing().isEmpty()
                &&!behavior.startsWith("sleep")&&!behavior.equals("wake")&&!behavior.equals("family_chew")&&!behavior.equals("feed_child")
                &&!behavior.equals("boat_feed_young")&&PropModels.has("kanroken");
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
