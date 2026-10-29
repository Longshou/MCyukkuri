package io.github.longshou.mcyukkuri.client.aquatic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.aquatic.HullLarder;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemDisplayContext;

/**
 * The food in a floating hat's hollow (refactor WP4b): each synced portion drawn as its own item, lying on the floor where it
 * really is (its spot in the hat's frame), turned a little each so a heap reads as separate pieces. Never on the brim, since
 * the server only puts portions in the hollow. It reads the hat's synced contents and nothing of the server.
 */
public final class HullLarderRenderer {
    /** An item drawn this much wider than its floor circle; this far above the floor (blocks). */
    private static final float SIZE = 1.3f, LIFT = .01f;
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
        var items = Minecraft.getInstance().getItemRenderer();
        float unit = hat.hullScale() / 16, size = (float) (2 * HullLarder.RADIUS) * SIZE;
        for (int i = 0; i < parsed.portions().size(); i++) {
            var portion = parsed.portions().get(i);
            pose.pushPose();
            pose.translate(portion.at().x * unit, portion.at().y * unit + LIFT, portion.at().z * unit);
            pose.mulPose(Axis.YP.rotationDegrees(i * 137.5f));
            pose.mulPose(Axis.XP.rotationDegrees(90));
            pose.scale(size, size, size);
            items.renderStatic(portion.item(), ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, pose, buffers, hat.level(), hat.getId() * 31 + i);
            pose.popPose();
        }
    }
}
