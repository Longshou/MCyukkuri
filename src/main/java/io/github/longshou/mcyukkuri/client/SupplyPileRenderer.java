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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Quaternionf;

/** Real stock is independent of the distance-limited decoration count. */
public final class SupplyPileRenderer implements BlockEntityRenderer<SupplyPileBlockEntity> {
    // The first piece stays at the server pickup point; additions spread around it.
    private static final double[][] CLUSTERS={
            {0,0},{-.06,.035},{.055,-.04},{.025,.07}
    };
    private static final Quaternionf[] ROTATIONS={Axis.YP.rotationDegrees(0),Axis.YP.rotationDegrees(137.5f),
            Axis.YP.rotationDegrees(275),Axis.YP.rotationDegrees(412.5f)};
    public SupplyPileRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public int getViewDistance(){return 12;}

    @Override public void render(SupplyPileBlockEntity pile,float partial,PoseStack pose,MultiBufferSource buffers,int light,int overlay) {
        int count=pile.count();if(count<=0)return;
        double distance=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceToSqr(pile.pickupPosition());
        if(distance>144)return;
        int clusters=Math.min(count,distance<=16?4:distance<=64?2:1);
        String prop=pile.is(ModItems.FOOD_SCRAP.get())?"food_scrap":pile.is(ModItems.NEST_LINT.get())?"nest_lint"
                :pile.is(ModItems.WASTE.get())?"yukkuri_waste":null;
        if(prop==null)return;
        float scale=switch(prop){case "food_scrap"->HouseScrapRenderer.HELD_SCALE*.75f;case "nest_lint"->.65f;default->.12f;};
        for(int i=0;i<clusters;i++) {
            pose.pushPose();pose.translate(.5+CLUSTERS[i][0],.025,.5+CLUSTERS[i][1]);
            pose.mulPose(ROTATIONS[i]);pose.scale(scale,scale,scale);
            PropModels.center(prop,pose,false);PropModels.render(prop,8,pose,buffers,light,overlay);pose.popPose();
        }
        label(pile,count,distance,pose,buffers,light);
    }

    private static void label(SupplyPileBlockEntity pile,int count,double distance,PoseStack pose,MultiBufferSource buffers,int light) {
        var mc=Minecraft.getInstance();
        if(mc.options.hideGui||mc.player==null||!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=HitResult.Type.BLOCK
                ||!hit.getBlockPos().equals(pile.getBlockPos())
                ||distance>16)return;
        var text=pile.displayName().copy().append(" × "+count).getVisualOrderText();
        pose.pushPose();pose.translate(.5,.25,.5);pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(.014f,-.014f,.014f);
        // Depth-tested and only under the crosshair; the world remains free of permanent labels.
        mc.font.drawInBatch(text,-mc.font.width(text)/2f,0,0xFFFFFFFF,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0x90000000,light);
        pose.popPose();
    }
}
