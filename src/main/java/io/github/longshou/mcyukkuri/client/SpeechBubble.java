package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

/** Depth-tested billboard with its own panel, wrapping and lifetime. */
public final class SpeechBubble {
    public static void render(YukkuriEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var mc = Minecraft.getInstance();
        double left = entity.speechEnd() - entity.level().getGameTime() - partialTick;
        if (mc.options.hideGui || mc.player == null || !entity.isAlive() || entity.isInvisible()
                || entity.getDialogueKey().isEmpty() || left <= 0 || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(entity.position()) > 256) return;
        var lines = mc.font.split(Component.translatable(entity.getDialogueKey()), 150);
        int count = Math.min(4, lines.size());
        if (count == 0) return;
        int width = 24;
        for (int i = 0; i < count; i++) width = Math.max(width, mc.font.width(lines.get(i)));
        float alpha = (float)Math.min(1, left / 10.0);
        if (alpha < .08f) return;
        float bottom = -7, top = bottom - count * (mc.font.lineHeight + 2) - 6;
        pose.pushPose();
        double modelTop = entity.profile().houseBody() ? .35 : entity.getVariant().assetId().endsWith("marisa") ? 1.25 : .87;
        double height = Math.max(entity.getBbHeight(), modelTop * entity.growthScale() * entity.getScale());
        pose.translate(0, height + .25 + (entity.hasCustomName() ? .28 : 0), 0);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(.018f, -.018f, .018f);
        Matrix4f matrix = pose.last().pose();
        var panel = buffers.getBuffer(RenderType.textBackground());
        int border = ((int)(alpha * 235) << 24) | 0x514536;
        int fill = ((int)(alpha * 245) << 24) | 0xfff8df;
        quad(panel, matrix, -width/2f-6, top, width/2f+6, bottom, 0, border, light);
        quad(panel, matrix, -width/2f-5, top+1, width/2f+5, bottom-1, .01f, fill, light);
        // Tapered pointer uses stacked quads in the same depth-tested pipeline.
        for (int i=0; i<4; i++) quad(panel, matrix, -4+i, bottom+i, 4-i, bottom+i+1, 0, border, light);
        for (int i=0; i<3; i++) quad(panel, matrix, -3+i, bottom-1+i, 3-i, bottom+i, .01f, fill, light);
        pose.translate(0, 0, .03);
        int textColor = ((int)(alpha * 255) << 24) | 0x30271d;
        for (int i=0; i<count; i++) mc.font.drawInBatch(lines.get(i), -mc.font.width(lines.get(i))/2f,
                top+4+i*(mc.font.lineHeight+2), textColor, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
    }
    private static void quad(VertexConsumer out, Matrix4f matrix, float x0, float y0, float x1, float y1, float z, int color, int light) {
        out.addVertex(matrix, x0, y0, z).setColor(color).setLight(light);
        out.addVertex(matrix, x0, y1, z).setColor(color).setLight(light);
        out.addVertex(matrix, x1, y1, z).setColor(color).setLight(light);
        out.addVertex(matrix, x1, y0, z).setColor(color).setLight(light);
    }
    private SpeechBubble() {}
}
