package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.aquatic.HullLarder;
import io.github.longshou.mcyukkuri.client.PropModels;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * The food in a floating hat's hollow: each synced portion drawn as a small 3D prop standing on the floor where it
 * really is (its spot in the hat's frame), as wide as its floor circle and with its own height, turned a little each so a heap
 * reads as separate pieces: a crumb for food scraps, a pouch for the grain pouches, a small forage heap for any other food.
 * Never on the brim, since the server only puts portions in the hollow. It reads the hat's synced contents and nothing of the server.
 */
public final class HullLarderRenderer {
    /** The forage heap's stage drawn for plain food (its smallest heap). */
    private static final int HEAP = 1;
    /** The parsed contents per hat, kept while its synced tag stays the same object. */
    private static final Map<Integer, Parsed> PARSED = new HashMap<>();
    private record Parsed(CompoundTag tag, List<HullLarder.Shown> portions) {}

    private HullLarderRenderer() {}

    public static void render(PlacedHatView hat, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        var tag = hat.contents();
        if (tag.isEmpty()) return;
        var parsed = PARSED.get(hat.getId());
        if (parsed == null || parsed.tag() != tag) {
            if (PARSED.size() > 64) PARSED.clear();
            parsed = new Parsed(tag, HullLarder.shown(tag, hat.level().registryAccess()));
            PARSED.put(hat.getId(), parsed);
        }
        float unit = hat.hullScale() / 16;
        for (int i = 0; i < parsed.portions().size(); i++) {
            var portion = parsed.portions().get(i);
            String prop = prop(portion.item());
            int units = prop.equals("forage_pile") ? HEAP : 8;
            var box = PropModels.bounds(prop, units);
            float scale = (float) (2 * HullLarder.RADIUS / Math.max(box.getXsize(), box.getZsize()));
            // no taller than the height its footprint takes on the server (a tall pouch is drawn squat)
            float lift = (float) Math.min(scale, HullLarder.HEIGHT / Math.max(1E-6, box.getYsize()));
            pose.pushPose();
            pose.translate(portion.at().x * unit, portion.at().y * unit, portion.at().z * unit);
            pose.mulPose(Axis.YP.rotationDegrees(i * 137.5f));
            pose.scale(scale, lift, scale);
            PropModels.center(prop, pose, true);
            PropModels.render(prop, units, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    /** The prop one stored food is drawn as, by its kind. */
    private static String prop(ItemStack item) {
        String path = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
        String own = path.equals("food_scrap") || path.startsWith("oil_grain_") || path.equals("spicy_grain") ? path : "forage_pile";
        return PropModels.has(own) ? own : "forage_pile";
    }
}
