package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.pose.LeashLoop;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** Draws the procedural vanilla-lead rope loop and back knot around a leashed yukkuri's posed body. Client only. */
final class LeashLoopRenderer {
    private static final ResourceLocation TEXTURE = MCYukkuri.id("textures/misc/leash_rope.png");

    private LeashLoopRenderer() {}

    static void render(YukkuriModel model, int wear, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        float[] body = model.posedWear(wear);
        int bodyLen = model.wearBodyLength();
        if (bodyLen < 9) return;
        float[] hair = model.posedLeashHair();
        int hairLen = model.leashHairLength();
        float extra = (wear & (2 | 4)) != 0 && (wear & 8) == 0 ? .028f : 0f;
        var mesh = LeashLoop.build(body, bodyLen, hair, hairLen, extra);
        if (mesh == null) return;
        var verts = mesh.vertices();
        var consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURE));
        var last = pose.last();
        var matrix = last.pose();
        for (int i = 0; i < verts.length; i += 24) {
            // Emit each triangle as a degenerate quad (v0, v1, v2, v2) for Minecraft's QUADS vertex format.
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
