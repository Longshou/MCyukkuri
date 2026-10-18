package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * Stored provisions as one thin picture layer on the rear half of the two side brims (hat_provisions, hat units, brim top
 * at y = 0 in this pose). The layer only gets richer with the synchronized count (tier 1-3, see HatBoatCargo.tier) and never
 * shows what the food is; it is not a seat or collision shape, and the server does not read it.
 */
final class HatCargoRenderer {
    private static final String PROP="hat_provisions";

    static void render(HatBoatEntity boat,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        int tier=boat.cargo().tier();
        if(tier==0||!PropModels.has(PROP))return;
        float scale=boat.hullScale();
        pose.pushPose();
        pose.scale(scale,scale,scale);
        // Parts are exclusive per tier: unit n draws only when bit n-1 of the mask is set.
        PropModels.renderLayers(PROP,1<<(tier-1),pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
    private HatCargoRenderer() {}
}
