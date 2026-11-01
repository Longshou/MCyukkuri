package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.aquatic.HullLarder;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/** One flat floor texture, cropped at a tier's boundary; UVs stay at 16 pixels per block. */
public final class HullLarderRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("mcyukkuri", "textures/props/aquatic_basin_hat_stores.png");
    private HullLarderRenderer() {}

    public static void render(PlacedHatView hat, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        int count = hat.contents().getInt("FoodCount");
        if (count == 0) return;
        double f = HullLarder.Counts.radius(count), unit = hat.hullScale() / 16;
        var profile = hat.profile();
        var radii = profile.data().getAsJsonObject("floor").getAsJsonArray("radius");
        var angles = profile.data().getAsJsonArray("azimuths");
        // Current profiles are centred at local (0, 0); -Z is the bow. WP4 replaces this measured oval boundary.
        double bow = 0;
        for (int i = 0; i < radii.size(); i++)
            bow = Math.max(bow, -Math.sin(Math.toRadians(angles.get(i).getAsDouble())) * radii.get(i).getAsDouble());
        double scale = unit / profile.measuredUnit(), centreZ = -(1 - f) * bow * scale;
        float y = (float) (profile.floor() * unit + .002);
        var vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        var matrix = pose.last();
        for (int i = 0; i < radii.size(); i++) {
            // Degenerate quads form the disc; the same texture repeats without changing its texel size.
            for (int corner = 0; corner < 4; corner++) {
                int index = (i + (corner == 2 ? 1 : 0)) % radii.size();
                double a = Math.toRadians(angles.get(index).getAsDouble());
                double r = corner == 0 || corner == 3 ? 0 : f * radii.get(index).getAsDouble() * scale;
                float x = (float) (Math.cos(a) * r), z = (float) (centreZ + Math.sin(a) * r);
                vertices.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(x + .5f, z + .5f)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix, 0, 1, 0);
            }
        }
    }
}
