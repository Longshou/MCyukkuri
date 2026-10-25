package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.subspecies.HatBoatView;
import io.github.longshou.mcyukkuri.subspecies.crew.DeckLayout;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * The existing forage pile, scaled by quantity in the crown larder only (nothing is stowed on the brim). Its measured
 * zone is the deck layout's larder tier. Brim top is y=0 here.
 */
final class HatCargoRenderer {
    private static final String PROP="hat_provisions";

    static void render(HatBoatView boat,float partial,PoseStack pose,MultiBufferSource buffers,int light) {
        int tier=boat.visibleCargoTier();
        if(tier==0||!PropModels.has(PROP))return;
        float scale=boat.hullScale();
        pose.pushPose();
        // The authored larder zone sits a little off the old heap mesh: move the mesh onto the zone.
        var zone=DeckLayout.of(boat.visibleMode()).tier(tier);
        if(zone!=null)pose.translate(zone.visualOffset()[0]*scale/16,0,zone.visualOffset()[1]*scale/16);
        pose.scale(scale,scale*boat.hullSquash(partial),scale);
        // Parts are exclusive per tier: unit n draws only when bit n-1 of the mask is set.
        int mask=1<<(tier-1);
        PropModels.renderLayers(PROP,mask,pose,buffers,light,OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
    private HatCargoRenderer() {}
}
