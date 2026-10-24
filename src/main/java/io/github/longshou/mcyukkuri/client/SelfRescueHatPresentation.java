package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.subspecies.SelfRescueHatGeometry;

/** Displays the server's single shaken-off hat pose; it never invents a transfer path and the braid takes no part. */
final class SelfRescueHatPresentation {
    static void configure(YukkuriView entity,YukkuriModel model,float partial,float bodyYaw){
        model.setSelfHatPose(SelfRescueHatGeometry.wornMatrix(entity,partial,bodyYaw));
    }
    private SelfRescueHatPresentation(){}
}
