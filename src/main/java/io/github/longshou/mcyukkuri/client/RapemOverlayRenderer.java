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
    private static final ResourceLocation ACT_STRIP = MCYukkuri.id("textures/effects/rapem_act_strip.png");
    private static final ResourceLocation HEAT_MARK = MCYukkuri.id("textures/effects/hatujyou_rot.png");
    private static final int ACT_FRAMES = 14, RUB_TICKS = 40;

    private RapemOverlayRenderer() {}

    public static void render(YukkuriEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var state = entity.agitationState();
        String phase = state.getString("Phase");
        boolean act = phase.equals("ALICE_ACT");
        boolean heat = phase.equals("HEAT") || phase.equals("ALICE_WATCH") || phase.equals("ALICE_APPROACH");
        if (!act && !heat) return;
        if (entity.isInvisible()) return;
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (act) {
            var victim = state.getInt("TargetId") >= 0 && mc.level != null && mc.level.getEntity(state.getInt("TargetId")) instanceof YukkuriEntity other
                    && other.isAlive() ? other : null;
            if (victim == null) return;
            float ticks = Math.max(0, entity.level().getGameTime() - state.getLong("Start") + partialTick);
            int frame = (int) (ticks % RUB_TICKS / (float) RUB_TICKS * 7);
            // Pick the mirrored sheet half by which side of the pair faces the camera.
            var camera = mc.gameRenderer.getMainCamera().getPosition();
            var axis = victim.position().subtract(entity.position());
            var view = camera.subtract(entity.position());
            boolean rightSide = axis.x * view.z - axis.z * view.x < 0;
            int index = (rightSide ? 7 : 0) + frame;
            var middle = entity.getPosition(partialTick).add(victim.getPosition(partialTick)).scale(.5);
            pose.pushPose();
            pose.translate(middle.x - entity.getX(), middle.y - entity.getY() + (entity.getBbHeight() + victim.getBbHeight()) * .25, middle.z - entity.getZ());
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            quad(pose, buffers.getBuffer(RenderType.entityTranslucentCull(ACT_STRIP)), light,
                    1.3f, index / (float) ACT_FRAMES, (index + 1) / (float) ACT_FRAMES, .9f);
            pose.popPose();
        } else {
            // The rotating 発情 mark above a heated body (1.12 hatujyou/rot.bmp).
            pose.pushPose();
            double top = entity.getVariant().isHouse() ? .35 : .87;
            pose.translate(0, Math.max(entity.getBbHeight(), top * entity.growthScale() * entity.getScale()) + .3, 0);
            pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
            pose.mulPose(Axis.ZP.rotationDegrees((entity.level().getGameTime() + partialTick) * 3f));
            quad(pose, buffers.getBuffer(RenderType.entityTranslucentCull(HEAT_MARK)), light, .45f, 0, 1, .45f);
            pose.popPose();
        }
    }

    private static void quad(PoseStack pose, VertexConsumer buffer, int light, float width, float u0, float u1, float height) {
        var matrix = pose.last().pose();
        var normal = pose.last().normal();
        vertex(matrix, normal, buffer, light, -width, -height, u0, 1, 255);
        vertex(matrix, normal, buffer, light, width, -height, u1, 1, 255);
        vertex(matrix, normal, buffer, light, width, height, u1, 0, 255);
        vertex(matrix, normal, buffer, light, -width, height, u0, 0, 255);
    }

    private static void vertex(org.joml.Matrix4f matrix, org.joml.Matrix3f normal, VertexConsumer buffer, int light,
                               float x, float y, float u, float v, int alpha) {
        buffer.vertex(matrix, x, y, 0).color(255, 255, 255, alpha).uv(u, v)
                .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .uv2(light).normal(normal, 0, 0, 1).endVertex();
    }
}
