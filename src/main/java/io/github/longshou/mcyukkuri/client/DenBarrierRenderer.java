package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.settlement.DenBarrierBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Placeholder look of the den-mouth screen: the wild-shelter twig mesh (`nest_boundary`, all three twigs) stood up as a rough
 * frame on the four sides of the cell and crossed by three lying twigs at different heights. Refinement is tracked in docs/TODO.md.
 */
public final class DenBarrierRenderer implements BlockEntityRenderer<DenBarrierBlockEntity> {
    private static final int ALL_TWIGS = 7, LYING_TWIG = 4;
    private static final float[] CROSS_HEIGHT = {.28f, .55f, .82f}, CROSS_TURN = {18f, -27f, 63f};

    public DenBarrierRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(DenBarrierBlockEntity barrier, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        float jitter = (barrier.getBlockPos().hashCode() & 15) - 7.5f;
        for (int side = 0; side < 4; side++) {
            pose.pushPose();
            pose.translate(.5, 0, .5);
            pose.mulPose(Axis.YP.rotationDegrees(90 * side + jitter * .6f));
            pose.scale(1f, 1.7f, 1f);
            PropModels.renderLayers("nest_boundary", ALL_TWIGS, pose, buffers, light, overlay);
            pose.popPose();
        }
        for (int i = 0; i < CROSS_HEIGHT.length; i++) {
            pose.pushPose();
            pose.translate(.5, CROSS_HEIGHT[i], .5);
            pose.mulPose(Axis.YP.rotationDegrees(CROSS_TURN[i] + jitter));
            pose.translate(.04, 0, .4375);
            pose.scale(1.45f, 1f, 1f);
            PropModels.renderLayers("nest_boundary", LYING_TWIG, pose, buffers, light, overlay);
            pose.popPose();
        }
    }
}
