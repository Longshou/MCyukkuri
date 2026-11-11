package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.math.Axis;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriVariant.Accessory;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyLife;
import io.github.longshou.mcyukkuri.headwear.HatScale;
import io.github.longshou.mcyukkuri.interaction.CareLineage;
import io.github.longshou.mcyukkuri.interaction.CareMenu;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

final class CareInspection {
    private static final int PAD = 7, GAP = 5;
    private static final float INITIAL_YAW = 18, INITIAL_PITCH = 12;
    private final CareMenu menu;
    private float yaw, pitch, zoom;
    private int scroll, maxScroll;
    private boolean dragging;

    CareInspection(CareMenu menu) {
        this.menu = menu;
        reset();
    }

    static Component text(String id, Object... args) {
        return Component.translatable(CareLineage.KEY + id, args);
    }

    static int previewWidth(int width) { return (width - GAP) / 2; }

    void reset() {
        yaw = INITIAL_YAW;
        pitch = INITIAL_PITCH;
        zoom = 1;
    }

    void changeTab() {
        scroll = 0;
        dragging = false;
    }

    void startDrag() { dragging = true; }

    boolean endDrag() {
        boolean handled = dragging;
        dragging = false;
        return handled;
    }

    boolean drag(double dx, double dy) {
        if (!dragging) return false;
        yaw = Mth.wrapDegrees(yaw + (float) dx * 2);
        pitch = Mth.wrapDegrees(pitch + (float) dy * 2);
        return true;
    }

    void zoom(double wheel) {
        zoom = Mth.clamp((float) (zoom * Math.pow(1.12, wheel)), .5f, 2.5f);
    }

    void scroll(double wheel) {
        scroll = Mth.clamp(scroll - (int) Math.copySign(24, wheel), 0, maxScroll);
    }

    void render(GuiGraphics g, Font font, int x, int y, int w, int h, boolean lineage) {
        if (lineage) {
            drawReadout(g, font, x, y, w, h, relationships());
            return;
        }
        int viewW = previewWidth(w);
        GuiKit.card(g, x, y, viewW, h, false);
        var hint = font.split(text("orbit"), viewW - 2 * PAD);
        int hintY = y + h - PAD - hint.size() * font.lineHeight;
        var entity = menu.target();
        if (entity != null) drawModel(g, entity, x + 3, y + 3, viewW - 6, hintY - y - 6, yaw, pitch, zoom);
        for (var line : hint) {
            g.drawString(font, line, x + PAD, hintY, GuiKit.SOFT, false);
            hintY += font.lineHeight;
        }
        drawReadout(g, font, x + viewW + GAP, y, w - viewW - GAP, h, identity());
    }

    private static void drawModel(GuiGraphics g, YukkuriEntity entity, int x, int y, int w, int h,
                                  float yaw, float pitch, float zoom) {
        var mc = Minecraft.getInstance();
        var renderer = (YukkuriRenderer) mc.getEntityRenderDispatcher().getRenderer(entity);
        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        float bodyYaw = Mth.rotLerp(partial, entity.previousBodyYaw(), entity.bodyYaw());
        float bodyHeight = entity.getBbHeight();
        // Collision size describes the body. Leave room for Marisa's crown and the wider aquatic brim.
        float hatRoom = entity.hasVisibleAccessory() && entity.getVariant().accessory() == Accessory.HAT
                ? 1.25f * HatScale.factor(entity.subspeciesFlags()) : 1;
        float visualHeight = bodyHeight * Math.max(entity.broodCount() > 0 ? 2.8f : 1.8f, 1.8f * hatRoom);
        float scale = zoom * Math.min(h / visualHeight, w / (entity.getBbWidth() * 1.6f * hatRoom));
        g.enableScissor(x, y, x + w, y + h);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(x + w / 2f, y + h * .5f, 50);
        pose.scale(scale, scale, -scale);
        pose.mulPose(Axis.ZP.rotationDegrees(180));
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw + bodyYaw - 180));
        pose.translate(0, -visualHeight * .38f, 0);
        Lighting.setupForEntityInInventory();
        try {
            renderer.renderPreview(entity, partial, pose, g.bufferSource(), LightTexture.FULL_BRIGHT);
            g.flush();
        } finally {
            Lighting.setupFor3DItems();
            pose.popPose();
            g.disableScissor();
        }
    }

    private record Line(Component text, boolean heading) {}

    private List<Line> identity() {
        YukkuriView entity = menu.target();
        if (entity == null) return List.of(new Line(text("unavailable"), false));
        var snapshot = menu.lineage();
        var status = StatusStrip.of(entity);
        var lines = new ArrayList<Line>();
        if (entity.hasCustomName()) lines.add(new Line(status.name(), true));
        lines.add(new Line(text("species", entity.getTypeName()), !entity.hasCustomName()));
        var stage = entity.lifeStage() == FamilyLife.Stage.JUVENILE.ordinal() && snapshot.stage().equals("subadult")
                ? Component.translatable("stage.mcyukkuri.subadult") : status.stage();
        lines.add(new Line(text("stage", stage), false));
        lines.add(new Line(text("identity", Component.translatable(status.identityKey())), false));
        if (!snapshot.roleKey().isEmpty()) lines.add(new Line(text("role", Component.translatable(snapshot.roleKey())), false));
        lines.add(new Line(status.status(), false));
        if (entity.broodCount() > Integer.bitCount(entity.broodBornMask())) lines.add(new Line(text("stem"), false));
        return lines;
    }

    private List<Line> relationships() {
        var snapshot = menu.lineage();
        var lines = new ArrayList<Line>();
        if (!snapshot.groupKey().isEmpty()) {
            lines.add(new Line(text("group"), true));
            lines.add(new Line(Component.translatable(snapshot.groupKey()), false));
        }
        for (var relation : CareLineage.Relation.values()) {
            boolean heading = false;
            for (var entry : snapshot.entries()) {
                if (entry.relation() != relation) continue;
                if (!heading) {
                    lines.add(new Line(text("relation." + CareLineage.id(relation)), true));
                    heading = true;
                }
                lines.add(new Line(entry.label(), false));
            }
        }
        if (lines.isEmpty()) lines.add(new Line(text("no_relations"), false));
        return lines;
    }

    private void drawReadout(GuiGraphics g, Font font, int x, int y, int w, int h, List<Line> lines) {
        GuiKit.statusCard(g, x, y, w, h);
        int inner = h - 2 * PAD;
        int offset = 0;
        g.enableScissor(x + PAD, y + PAD, x + w - PAD, y + h - PAD);
        for (var line : lines) {
            if (line.heading() && offset > 0) offset += 5;
            for (var part : font.split(line.text(), w - 2 * PAD - 5)) {
                int top = offset - scroll;
                if (top >= -font.lineHeight && top < inner) {
                    g.drawString(font, part, x + PAD, y + PAD + top, line.heading() ? GuiKit.GOOD : GuiKit.INK, false);
                }
                offset += font.lineHeight + 2;
            }
        }
        g.disableScissor();
        maxScroll = Math.max(0, offset - inner);
        scroll = Math.min(scroll, maxScroll);
        if (maxScroll > 0) {
            int thumb = Math.max(9, inner * inner / offset);
            int top = y + PAD + (inner - thumb) * scroll / maxScroll;
            g.fill(x + w - 7, top, x + w - 4, top + thumb, GuiKit.SOFT);
        }
    }
}
