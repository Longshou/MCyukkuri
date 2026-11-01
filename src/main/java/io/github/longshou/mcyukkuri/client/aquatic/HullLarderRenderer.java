package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.aquatic.HullLarder;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** One flat floor texture, cropped at a tier's boundary; UVs stay at 16 pixels per block. */
public final class HullLarderRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("mcyukkuri", "textures/props/aquatic_basin_hat_stores.png");
    private static final int SEGMENTS = 32;
    private HullLarderRenderer() {}

    public static void render(PlacedHatView hat, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        int count = hat.contents().getInt("FoodCount");
        if (count == 0 || !hat.profile().seated()) return;
        double f = HullLarder.Counts.radius(count), scale = hat.hullScale() / HullBounds.SCALE;
        double radius = HullBounds.FLOOR_RADIUS * scale, centreZ = -(1 - f) * radius;
        float y = (float) (HullBounds.FLOOR * scale + .002);
        var vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        var matrix = pose.last();
        for (int i = 0; i < SEGMENTS; i++) {
            // Degenerate quads form the disc; the same texture repeats without changing its texel size.
            for (int corner = 0; corner < 4; corner++) {
                int index = (i + (corner == 2 ? 1 : 0)) % SEGMENTS;
                double r = corner == 0 || corner == 3 ? 0 : f * radius, a = index * 2 * Math.PI / SEGMENTS;
                float x = (float) (Math.cos(a) * r), z = (float) (centreZ + Math.sin(a) * r);
                vertices.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(x + .5f, z + .5f)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix, 0, 1, 0);
            }
        }
    }
}
