package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlock;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * Draws every item a produce crate holds, one sprite per item: row 0 stands at the back and each later row a step lower in front
 * of it, leaning back, so the goods face the crate's low side like a market display.
 */
public final class ProduceCrateRenderer implements BlockEntityRenderer<ProduceCrateBlockEntity> {
    private static final float SCALE = .4f, PX = 1 / 16f;

    public ProduceCrateRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(ProduceCrateBlockEntity crate, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var renderer = Minecraft.getInstance().getItemRenderer();
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-crate.getBlockState().getValue(ProduceCrateBlock.FACING).toYRot()));   // local +z: the low side
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) {
            var stack = crate.getItem(row);
            int count = Math.min(stack.getCount(), ProduceCrateBlockEntity.PER_ROW);
            for (int i = 0; i < count; i++) {
                pose.pushPose();
                // Rows 3.2 px apart from the back (z -4.8) to the front (z +4.8), each 1.1 px lower; items 1.9 px apart across (they overlap).
                pose.translate(((i - 1.5f) * 1.9f + (row % 2 == 0 ? -.4f : .4f)) * PX,
                        (2 + (ProduceCrateBlockEntity.ROWS - 1 - row) * 1.1f) * PX + SCALE / 2, (row - 1.5f) * 3.2f * PX);
                pose.mulPose(Axis.XP.rotationDegrees(-18));
                pose.mulPose(Axis.ZP.rotationDegrees((i * 37 + row * 53) % 21 - 10));
                pose.scale(SCALE, SCALE, SCALE);
                renderer.renderStatic(stack, ItemDisplayContext.FIXED, light, overlay, pose, buffers, crate.getLevel(), 0);
                pose.popPose();
            }
        }
        pose.popPose();
    }
}
