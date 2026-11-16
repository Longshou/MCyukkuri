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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemDisplayContext;
import org.joml.Quaternionf;

/**
 * Draws every item a produce crate holds, one sprite per item: row 0 stands at the back and each later row a step lower in front
 * of it, leaning back, so the goods face the crate's low side like a market display. A display tray (the same block entity) shows
 * its rows as piles lying flat.
 */
public final class ProduceCrateRenderer implements BlockEntityRenderer<ProduceCrateBlockEntity> {
    private static final float SCALE = .4f, PX = 1 / 16f;
    private static final Quaternionf[] FACING = new Quaternionf[4];
    private static final Quaternionf[][] CRATE = turns(), TRAY_UPRIGHT = turns(), TRAY_FLAT = turns();

    private static Quaternionf[][] turns() { return new Quaternionf[ProduceCrateBlockEntity.ROWS][ProduceCrateBlockEntity.PER_ROW]; }

    static {
        for (var side : Direction.Plane.HORIZONTAL) FACING[side.get2DDataValue()] = Axis.YP.rotationDegrees(-side.toYRot());
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) {
            for (int i = 0; i < ProduceCrateBlockEntity.PER_ROW; i++) {
                int turn = (row * 67 + i * 29) % 50 - 25;
                CRATE[row][i] = Axis.XP.rotationDegrees(-18).mul(Axis.ZP.rotationDegrees((i * 37 + row * 53) % 21 - 10));
                TRAY_UPRIGHT[row][i] = Axis.YP.rotationDegrees(turn);
                TRAY_FLAT[row][i] = Axis.YP.rotationDegrees(180 + turn).mul(Axis.XP.rotationDegrees(90));
            }
        }
    }

    public ProduceCrateRenderer(BlockEntityRendererProvider.Context context) {}

    @Override public void render(ProduceCrateBlockEntity crate, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (crate.isEmpty()) return;
        var renderer = Minecraft.getInstance().getItemRenderer();
        var state = crate.getBlockState();
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(FACING[state.getValue(ProduceCrateBlock.FACING).get2DDataValue()]); // local +z: the low side
        var base = pose.last();
        pose.pushPose();
        if (state.getBlock() instanceof DisplayTrayBlock) tray(crate, renderer, pose, base, buffers, light, overlay);
        else crate(crate, renderer, pose, base, buffers, light, overlay);
        pose.popPose();
        pose.popPose();
    }

    // Item transforms are rotations and uniform scales, so both matrices can reuse the base's normal policy.
    private static void resetItemPose(PoseStack pose, PoseStack.Pose base) {
        pose.last().pose().set(base.pose());
        pose.last().normal().set(base.normal());
    }

    private static void crate(ProduceCrateBlockEntity crate, ItemRenderer renderer, PoseStack pose, PoseStack.Pose base,
                              MultiBufferSource buffers, int light, int overlay) {
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) {
            var stack = crate.getItem(row);
            if (stack.isEmpty()) continue;
            var model = renderer.getModel(stack, crate.getLevel(), null, 0);
            int count = Math.min(stack.getCount(), ProduceCrateBlockEntity.PER_ROW);
            for (int i = 0; i < count; i++) {
                resetItemPose(pose, base);
                // Rows 3.2 px apart from the back (z -4.8) to the front (z +4.8), each 1.1 px lower; items 1.9 px apart across (they overlap).
                pose.translate(((i - 1.5f) * 1.9f + (row % 2 == 0 ? -.4f : .4f)) * PX,
                        (2 + (ProduceCrateBlockEntity.ROWS - 1 - row) * 1.1f) * PX + SCALE / 2, (row - 1.5f) * 3.2f * PX);
                pose.mulPose(CRATE[row][i]);
                pose.scale(SCALE, SCALE, SCALE);
                renderer.render(stack, ItemDisplayContext.FIXED, false, pose, buffers, light, overlay, model);
            }
        }
    }

    /**
     * A display tray: each occupied row is one pile lying flat on the tray floor. One pile fills the tray, two lie side by side,
     * more take the four quarters; every further item of a pile lies on the one below, turned a little. Block-shaped items and
     * prop meshes stand.
     */
    private static void tray(ProduceCrateBlockEntity tray, ItemRenderer renderer, PoseStack pose, PoseStack.Pose base,
                             MultiBufferSource buffers, int light, int overlay) {
        int piles = 0;
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) if (!tray.getItem(row).isEmpty()) piles++;
        float size = piles == 1 ? .62f : piles == 2 ? .42f : .36f;
        float floor = (DisplayTrayBlock.FLOOR + (tray.getBlockState().getValue(DisplayTrayBlock.RAISED) ? DisplayTrayBlock.SLAB : 0)) * PX;
        int pile = 0;
        for (int row = 0; row < ProduceCrateBlockEntity.ROWS; row++) {
            var stack = tray.getItem(row);
            if (stack.isEmpty()) continue;
            float x = piles == 1 ? 0 : (pile % 2 == 0 ? -3.4f : 3.4f) * PX, z = piles <= 2 ? 0 : (pile < 2 ? -3.4f : 3.4f) * PX;
            var model = renderer.getModel(stack, tray.getLevel(), null, 0);
            String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            boolean prop = model.isCustomRenderer() && PropModels.has(name), solid = model.isGui3d();
            float fit = prop ? Math.min(1, .9f * size * 16 / Math.max(1, PropModels.extent(name))) : 0;
            float y = prop ? floor + fit * PropModels.height(name) / 2 : floor + size * .25f;
            int count = Math.min(stack.getCount(), ProduceCrateBlockEntity.PER_ROW);
            for (int i = 0; i < count; i++) {
                resetItemPose(pose, base);
                if (prop || solid) {
                    // Meshes and blocks stand in a short row, centred half their drawn height above the floor.
                    pose.translate(x + (i - (count - 1) / 2f) * 1.2f * PX, y, z);
                    pose.mulPose(TRAY_UPRIGHT[row][i]);
                } else {
                    pose.translate(x, floor + (i + .5f) * size * PX, z);        // a sprite is one pixel thick at scale 1
                    pose.mulPose(TRAY_FLAT[row][i]);
                }
                float scale = prop ? fit : size;
                pose.scale(scale, scale, scale);
                renderer.render(stack, prop ? ItemDisplayContext.NONE : ItemDisplayContext.FIXED, false, pose, buffers, light, overlay, model);
            }
            pile++;
        }
    }
}
