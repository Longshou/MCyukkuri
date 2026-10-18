package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/** The 1.12 act and heat marks, shown as camera-facing quads exactly like the source game. */
public final class RapemOverlayRenderer {
    private static final ResourceLocation HEAT_MARK = MCYukkuri.id("textures/effects/hatujyou_rot.png");

    private RapemOverlayRenderer() {}

    public static void render(YukkuriEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var state = entity.agitationState();
        String phase = state.getString("Phase");
        if (!phase.equals("HEAT") && !phase.equals("ALICE_WATCH") && !phase.equals("ALICE_APPROACH")) return;
        if (entity.isInvisible()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        // The rotating 発情 mark above a heated body (1.12 hatujyou/rot.bmp).
        pose.pushPose();
        double top = entity.profile().bodyOverlayTop();
        pose.translate(0, Math.max(entity.getBbHeight(), top * entity.growthScale() * entity.getScale()) + .3, 0);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.mulPose(Axis.ZP.rotationDegrees((entity.level().getGameTime() + partialTick) * 3f));
        quad(pose, buffers.getBuffer(RenderType.entityTranslucentCull(HEAT_MARK)), light, .45f, 0, 1, .45f);
        pose.popPose();
    }

    private static void quad(PoseStack pose, VertexConsumer buffer, int light, float width, float u0, float u1, float height) {
        var matrix = pose.last().pose();
        var last = pose.last();
        vertex(last, matrix, buffer, light, -width, -height, u0, 1, 255);
        vertex(last, matrix, buffer, light, width, -height, u1, 1, 255);
        vertex(last, matrix, buffer, light, width, height, u1, 0, 255);
        vertex(last, matrix, buffer, light, -width, height, u0, 0, 255);
    }

    private static void vertex(PoseStack.Pose last, org.joml.Matrix4f matrix, VertexConsumer buffer, int light,
                               float x, float y, float u, float v, int alpha) {
        buffer.addVertex(matrix, x, y, 0f).setColor(255, 255, 255, alpha).setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(last, 0f, 0f, 1f);
    }
}
