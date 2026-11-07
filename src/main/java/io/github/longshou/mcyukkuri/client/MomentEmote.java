package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.entity.presentation.Moments;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** Name-tag style, body-scaled key-beat symbol; a roof leaves the faint pass visible. */
final class MomentEmote {
    private static final JsonObject ATLAS = read();
    private static JsonObject read() {
        try (var reader = new InputStreamReader(MomentEmote.class.getResourceAsStream(
                "/assets/mcyukkuri/textures/emote/emotes.json"), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    static void render(YukkuriView e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        var mc = Minecraft.getInstance();
        if (e.getMomentId().isEmpty() || e.isInvisible() || !e.isAlive()
                || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(e.position()) > 256) return;
        float elapsed = (e.level().getGameTime()-e.getMomentStartTick()+partial)/20f;
        // A looping body pose must not turn a short punctuation mark into a permanent status icon.
        if (elapsed >= Moments.duration(e.getMomentId())) return;
        var frame = Moments.frame(e.getMomentId(), elapsed);
        String emote = frame.optional("emote");
        if (emote.isEmpty() || frame.seconds() >= 2) return;
        JsonObject icon = null;
        for (var value : ATLAS.getAsJsonArray("emotes"))
            if (value.getAsJsonObject().get("id").getAsString().equals(emote)) icon = value.getAsJsonObject();
        var cell = icon.getAsJsonArray("cell");
        float u = cell.get(0).getAsFloat()/ATLAS.get("columns").getAsFloat();
        float v = cell.get(1).getAsFloat()/ATLAS.get("rows").getAsFloat();
        float du = 1f/ATLAS.get("columns").getAsFloat(), dv = 1f/ATLAS.get("rows").getAsFloat();
        float scale = e.growthScale()*e.getScale();
        double top = e.profile().houseBody() ? .35 : e.getVariant().assetId().endsWith("marisa") ? 1.25 : .87;
        pose.pushPose();
        pose.translate(0, Math.max(e.getBbHeight(), top*scale)+.12*scale, 0);
        pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        pose.scale(.4f*scale, .4f*scale, .4f*scale);
        var texture = MCYukkuri.id("textures/emote/atlas.png");
        quad(buffers.getBuffer(RenderType.textSeeThrough(texture)), pose.last().pose(), u,v,du,dv,64,light);
        quad(buffers.getBuffer(RenderType.text(texture)), pose.last().pose(), u,v,du,dv,255,light);
        pose.popPose();
    }
    private static void quad(VertexConsumer out, Matrix4f matrix, float u,float v,float du,float dv,int alpha,int light) {
        out.addVertex(matrix,-.5f,0,0).setColor(255,255,255,alpha).setUv(u,v+dv).setLight(light);
        out.addVertex(matrix,.5f,0,0).setColor(255,255,255,alpha).setUv(u+du,v+dv).setLight(light);
        out.addVertex(matrix,.5f,1,0).setColor(255,255,255,alpha).setUv(u+du,v).setLight(light);
        out.addVertex(matrix,-.5f,1,0).setColor(255,255,255,alpha).setUv(u,v).setLight(light);
    }
    private MomentEmote() {}
}
