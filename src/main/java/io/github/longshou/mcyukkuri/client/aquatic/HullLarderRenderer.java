package io.github.longshou.mcyukkuri.client.aquatic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.aquatic.HullLarder;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** One flat floor texture, cropped at a tier's boundary; UVs stay at 16 pixels per block. */
public final class HullLarderRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("mcyukkuri", "textures/props/aquatic_basin_hat_stores.png");
    private record Floor(double height, double bow, double[] x, double[] z) {}
    private static Map<String, Floor> floors;
    public static final SimpleJsonResourceReloadListener RELOAD = new SimpleJsonResourceReloadListener(new Gson(), "yukkuri_presentation/hull_profiles") {
        @Override protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            var parsed = new HashMap<String, Floor>();
            for (var value : data.values()) {
                var profile = value.getAsJsonObject();
                double unit = profile.get("scale").getAsDouble() / 16;
                var floor = profile.getAsJsonObject("floor");
                var radii = floor.getAsJsonArray("radius");
                var angles = profile.getAsJsonArray("azimuths");
                double[] x = new double[radii.size()], z = new double[radii.size()];
                double bow = 0;
                for (int i = 0; i < radii.size(); i++) {
                    double angle = Math.toRadians(angles.get(i).getAsDouble()), radius = radii.get(i).getAsDouble() / unit;
                    x[i] = Math.cos(angle) * radius;
                    z[i] = Math.sin(angle) * radius;
                    bow = Math.max(bow, -z[i]);
                }
                double height = (floor.get("y").getAsDouble() - profile.getAsJsonObject("keel").get("y").getAsDouble()) / unit;
                parsed.put(profile.get("form").getAsString(), new Floor(height, bow, x, z));
            }
            floors = Map.copyOf(parsed);
        }
    };
    private HullLarderRenderer() {}

    public static void render(PlacedHatView hat, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        int count = hat.contents().getInt("FoodCount");
        if (count == 0) return;
        double f = HullLarder.Counts.radius(count), unit = hat.hullScale() / 16;
        var floor = floors.get(hat.formId());
        // Current profiles are centred at local (0, 0); -Z is the bow. WP4 replaces this measured oval boundary.
        double centreZ = -(1 - f) * floor.bow() * unit;
        float y = (float) (floor.height() * unit + .002);
        var vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        var matrix = pose.last();
        for (int i = 0; i < floor.x().length; i++) {
            // Degenerate quads form the disc; the same texture repeats without changing its texel size.
            for (int corner = 0; corner < 4; corner++) {
                int index = (i + (corner == 2 ? 1 : 0)) % floor.x().length;
                double scale = corner == 0 || corner == 3 ? 0 : f * unit;
                float x = (float) (floor.x()[index] * scale), z = (float) (centreZ + floor.z()[index] * scale);
                vertices.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(x + .5f, z + .5f)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(matrix, 0, 1, 0);
            }
        }
    }
}
