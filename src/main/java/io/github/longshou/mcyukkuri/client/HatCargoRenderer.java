package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * The existing forage pile, scaled by quantity in the cavity and on the temporary side stow. Its measured
 * bounds are exported as hat_cargo_layout.json for server seat and route planning. Brim top is y=0 here.
 */
final class HatCargoRenderer {
    private static final String PROP="hat_provisions";

    static void render(HatBoatEntity boat,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        int tier=boat.cargo().tier(),aside=boat.cargo().asideTier();
        if((tier==0&&aside==0)||!PropModels.has(PROP))return;
        float scale=boat.hullScale();
        pose.pushPose();
        pose.scale(scale,scale*boat.hullSquash(partial),scale);
        // Parts are exclusive per tier: unit n draws only when bit n-1 of the mask is set.
        int mask=(tier>0?1<<(tier-1):0)|(aside>0?1<<(aside+2):0);
        PropModels.renderLayers(PROP,mask,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
    private HatCargoRenderer() {}
}
