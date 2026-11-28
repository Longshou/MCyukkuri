package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.pose.IceShell;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Draws the translucent bevelled ice block built by {@link IceShell} around a cold-stiff body. Client only. */
final class IceShellRenderer {
    static final ResourceLocation TEXTURE = MCYukkuri.id("textures/misc/cold_stiff_ice.png");

    private IceShellRenderer() {}

    static void render(IceShell shell, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (shell == null || shell.length() == 0) return;
        float[] verts = shell.vertices();
        // Cull back faces so only the outer frosted block faces blend cleanly over the opaque frozen body inside.
        var consumer = buffers.getBuffer(RenderType.entityTranslucentCull(TEXTURE));
        var last = pose.last();
        var matrix = last.pose();
        for (int i = 0; i < shell.length(); i += IceShell.TRIANGLE) {
            for (int c = 0; c < 4; c++) {
                int k = i + (c == 3 ? 2 : c) * 8;
                consumer.addVertex(matrix, verts[k], verts[k + 1], verts[k + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(verts[k + 3], verts[k + 4])
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(last, verts[k + 5], verts[k + 6], verts[k + 7]);
            }
        }
    }
}
