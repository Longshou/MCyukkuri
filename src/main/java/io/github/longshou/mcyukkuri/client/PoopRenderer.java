package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.life.PoopBlockEntity;
import io.github.longshou.mcyukkuri.life.Poops;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Placeholder art for the four tiers of a droppings heap: the care-prop 便堆 swirl ({@code toilet_waste}) scaled by tier.
 * Replace with per-tier block models (docs/TODO.md 美术): blockstates/yukkuri_poop.json already has one entry per tier.
 */
public final class PoopRenderer implements BlockEntityRenderer<PoopBlockEntity> {
    private static final String MESH = "toilet_waste";
    /** The mesh is 5.9 wide and 4.2 high (sixteenths): tier 1 is a dab, tier 4 fills most of the cell up to the 8-high outline. */
    private static final float[] SCALE = {.5f, 1f, 1.4f, 1.9f};
    public PoopRenderer(BlockEntityRendererProvider.Context context) {}
    @Override public int getViewDistance() { return 48; }

    @Override public void render(PoopBlockEntity poop, float partial, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        int count = poop.count();
        if (count <= 0 || !PropModels.has(MESH)) return;
        float scale = SCALE[Poops.tier(count) - 1];
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees((poop.getBlockPos().hashCode() & 7) * 45f));
        pose.scale(scale, scale, scale);
        PropModels.render(MESH, 0, pose, buffers, light, overlay);
        pose.popPose();
    }
}
