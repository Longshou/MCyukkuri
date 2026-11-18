package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

final class ReservationMark {
    private static final Component OWN = Component.translatable("shop.mcyukkuri.mark.own");
    private static final Component OTHER = Component.translatable("shop.mcyukkuri.mark.other");

    static void render(YukkuriView animal, PoseStack pose, MultiBufferSource buffers, int light) {
        var reserver = animal.reservedBy();
        var mc = Minecraft.getInstance();
        if (reserver.isEmpty() || mc.player == null || mc.options.hideGui || !animal.isAlive() || animal.isInvisible()
                || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(animal.position()) > 256) return;
        boolean own = reserver.get().equals(mc.player.getUUID());
        var text = own ? OWN : OTHER;
        int color = own ? 0xFFFFCF59 : 0xFFD0D0D0;
        float scale = animal.growthScale() * animal.getScale();
        pose.pushPose();
        pose.translate(0, MomentEmote.height(animal, scale) + .35 * scale, 0);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(.025f, -.025f, .025f);
        float x = -mc.font.width(text) / 2f;
        var matrix = pose.last().pose();
        mc.font.drawInBatch(text, x, 0, 0x40000000 | color & 0xFFFFFF, false, matrix, buffers, Font.DisplayMode.SEE_THROUGH, 0, light);
        mc.font.drawInBatch(text, x, 0, color, false, matrix, buffers, Font.DisplayMode.NORMAL, 0, light);
        pose.popPose();
    }

    private ReservationMark() {}
}
