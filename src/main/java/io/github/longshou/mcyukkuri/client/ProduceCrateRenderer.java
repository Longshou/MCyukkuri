package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.human.DisplayTrayBlock;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlock;
import io.github.longshou.mcyukkuri.human.ProduceCrateBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * Draws every item a produce crate holds, one sprite per item: row 0 stands at the back and each later row a step lower in front
 * of it, leaning back, so the goods face the crate's low side like a market display. A display tray (the same block entity) shows
 * its rows as piles lying flat.
 */
public final class ProduceCrateRenderer implements BlockEntityRenderer<ProduceCrateBlockEntity> {
    private static final float SCALE = .4f, PX = 1 / 16f;

    public ProduceCrateRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(ProduceCrateBlockEntity crate, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        var renderer = Minecraft.getInstance().getItemRenderer();
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-crate.getBlockState().getValue(ProduceCrateBlock.FACING).toYRot()));   // local +z: the low side
        if (crate.getBlockState().getBlock() instanceof DisplayTrayBlock) {
            tray(crate, renderer, pose, buffers, light, overlay);
            pose.popPose();
            return;
        }
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

    /**
     * A display tray: each occupied row is one pile lying flat on the tray floor. One pile fills the tray, two lie side by side,
     * more take the four quarters; every further item of a pile lies on the one below, turned a little. Block-shaped items stand.
     */
    private static void tray(ProduceCrateBlockEntity tray, ItemRenderer renderer, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        int piles = 0;
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) if (!tray.getItem(row).isEmpty()) piles++;
        float size = piles == 1 ? .62f : piles == 2 ? .42f : .36f;
        float floor = (DisplayTrayBlock.FLOOR + (tray.getBlockState().getValue(DisplayTrayBlock.RAISED) ? DisplayTrayBlock.SLAB : 0)) * PX;
        int pile = 0;
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) {
            var stack = tray.getItem(row);
            if (stack.isEmpty()) continue;
            float x = piles == 1 ? 0 : (pile % 2 == 0 ? -3.4f : 3.4f) * PX, z = piles <= 2 ? 0 : (pile < 2 ? -3.4f : 3.4f) * PX;
            boolean solid = renderer.getModel(stack, tray.getLevel(), null, 0).isGui3d();
            int count = Math.min(stack.getCount(), ProduceCrateBlockEntity.PER_ROW);
            for (int i = 0; i < count; i++) {
                pose.pushPose();
                float turn = (row * 67 + i * 29) % 50 - 25;
                if (solid) {
                    // A block model is half a block wide at scale 1 in the fixed display; the pile stands in a short row.
                    pose.translate(x + (i - (count - 1) / 2f) * 1.2f * PX, floor + size * .25f, z);
                    pose.mulPose(Axis.YP.rotationDegrees(turn));
                } else {
                    pose.translate(x, floor + (i + .5f) * size * PX, z);        // a sprite is one pixel thick at scale 1
                    pose.mulPose(Axis.YP.rotationDegrees(180 + turn));
                    pose.mulPose(Axis.XP.rotationDegrees(90));
                }
                pose.scale(size, size, size);
                renderer.renderStatic(stack, ItemDisplayContext.FIXED, light, overlay, pose, buffers, tray.getLevel(), 0);
                pose.popPose();
            }
            pile++;
        }
    }
}
