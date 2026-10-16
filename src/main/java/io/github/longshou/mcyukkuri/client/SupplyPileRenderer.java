package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.house.SupplyPileBlockEntity;
import io.github.longshou.mcyukkuri.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

/** A single durable stock cell, shown as at most eight actual-sized scraps. */
public final class SupplyPileRenderer implements BlockEntityRenderer<SupplyPileBlockEntity> {
    // The first piece stays at the server pickup point; additions spread around it.
    private static final double[][] CLUSTERS={
            {0,0},{-.06,.035},{.055,-.04},{.025,.07},
            {-.075,-.045},{.085,.025},{-.03,-.09},{.08,-.085}
    };
    public SupplyPileRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(SupplyPileBlockEntity pile,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        ItemStack contents=pile.contents();int count=pile.count();
        if(contents.isEmpty()||count<=0)return;
        String prop=contents.is(ModItems.FOOD_SCRAP.get())?"food_scrap":contents.is(ModItems.NEST_LINT.get())?"nest_lint"
                :contents.is(ModItems.WASTE.get())?"yukkuri_waste":null;
        if(prop==null)return;
        float scale=switch(prop){case "food_scrap"->HouseScrapRenderer.HELD_SCALE*.75f;case "nest_lint"->.65f;default->.12f;};
        var pickup=pile.pickupPosition().subtract(pile.getBlockPos().getX(),pile.getBlockPos().getY(),pile.getBlockPos().getZ());
        for(int i=0;i<Math.min(CLUSTERS.length,count);i++) {
            pose.pushPose();pose.translate(pickup.x+CLUSTERS[i][0],pickup.y,pickup.z+CLUSTERS[i][1]);
            pose.mulPose(Axis.YP.rotationDegrees(i*137.5f));pose.scale(scale,scale,scale);
            PropModels.center(prop,pose,false);PropModels.render(prop,8,pose,buffers,light,overlay);pose.popPose();
        }
        label(pile,contents,count,pose,buffers,light);
    }

    private static void label(SupplyPileBlockEntity pile,ItemStack contents,int count,PoseStack pose,MultiBufferSource buffers,int light) {
        var mc=Minecraft.getInstance();
        if(mc.options.hideGui||mc.player==null||!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK
                ||!hit.getBlockPos().equals(pile.getBlockPos())
                ||mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(pile.pickupPosition())>16)return;
        var text=contents.getHoverName().copy().append(" × "+count).getVisualOrderText();
        pose.pushPose();pose.translate(.5,.25,.5);pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(.014f,-.014f,.014f);
        // Depth-tested and only under the crosshair; the world remains free of permanent labels.
        mc.font.drawInBatch(text,-mc.font.width(text)/2f,0,0xFFFFFFFF,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0x90000000,light);
        pose.popPose();
    }
}
