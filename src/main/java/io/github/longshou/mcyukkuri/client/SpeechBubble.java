package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FormattedCharSequence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;

/** Depth-tested billboard with its own panel, wrapping and lifetime. */
public final class SpeechBubble {
    private static final int MAX_LAYOUTS=128,LINE_WIDTH=150;
    private static final Map<String,Layout> LAYOUTS=new LinkedHashMap<>(MAX_LAYOUTS,.75f,true){
        @Override
        protected boolean removeEldestEntry(Map.Entry<String,Layout> eldest){return size()>MAX_LAYOUTS;}
    };
    /** Language and font data can change with a client resource reload. */
    public static final ResourceManagerReloadListener RELOAD=resources->clearLayouts();

    public static void render(YukkuriView entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        var mc = Minecraft.getInstance();
        double left = entity.speechEnd() - entity.level().getGameTime() - partialTick;
        if (mc.options.hideGui || mc.player == null || !entity.isAlive() || entity.isInvisible()
                || entity.getDialogueKey().isEmpty() || left <= 0 || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(entity.position()) > 256) return;
        Layout layout=layout(mc.font,entity.getDialogueKey());
        int count=layout.count;
        if (count == 0) return;
        int width=layout.width;
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
        for (int i=0; i<count; i++) {
            FormattedCharSequence line=layout.lines.get(i);
            mc.font.drawInBatch(line, -layout.widths[i]/2f,
                top+4+i*(mc.font.lineHeight+2), textColor, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, light);
        }
        pose.popPose();
    }

    private static Layout layout(Font font,String dialogueKey){
        synchronized(LAYOUTS){
            Language language=Language.getInstance();
            Layout cached=LAYOUTS.get(dialogueKey);
            if(cached!=null&&cached.language==language&&cached.font==font)return cached;
            List<FormattedCharSequence> lines=font.split(Component.translatable(dialogueKey),LINE_WIDTH);
            int count=Math.min(4,lines.size()),width=24;
            int[] widths=new int[count];
            for(int i=0;i<count;i++){widths[i]=font.width(lines.get(i));width=Math.max(width,widths[i]);}
            Layout layout=new Layout(List.copyOf(lines.subList(0,count)),widths,count,width,language,font);
            LAYOUTS.put(dialogueKey,layout);
            return layout;
        }
    }

    private static void clearLayouts(){
        synchronized(LAYOUTS){LAYOUTS.clear();}
    }

    private record Layout(List<FormattedCharSequence> lines,int[] widths,int count,int width,Language language,Font font){}

    private static void quad(VertexConsumer out, Matrix4f matrix, float x0, float y0, float x1, float y1, float z, int color, int light) {
        out.addVertex(matrix, x0, y0, z).setColor(color).setLight(light);
        out.addVertex(matrix, x0, y1, z).setColor(color).setLight(light);
        out.addVertex(matrix, x1, y1, z).setColor(color).setLight(light);
        out.addVertex(matrix, x1, y0, z).setColor(color).setLight(light);
    }
    private SpeechBubble() {}
}
