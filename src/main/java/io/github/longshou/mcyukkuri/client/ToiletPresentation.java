package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Authored rear-care motion and a mouth-attached tongue, visible only during verified live care. */
final class ToiletPresentation {
    private static float cycle = 10, contactStart = 2, contactEnd = 7, width = .045f, thickness = .012f, reach = .22f;
    private ToiletPresentation() {}

    static JsonObject reload(ResourceManager resources) {
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/toilet_contact.json")).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            if (data.get("format").getAsInt() != 1) throw new IllegalArgumentException("Unsupported toilet motions");
            var tongue = data.getAsJsonObject("tongue");
            cycle = tongue.get("cycle_ticks").getAsFloat();
            contactStart = tongue.getAsJsonArray("contact_window_ticks").get(0).getAsFloat();
            contactEnd = tongue.getAsJsonArray("contact_window_ticks").get(1).getAsFloat();
            width = tongue.get("width").getAsFloat();
            thickness = tongue.get("thickness").getAsFloat();
            reach = tongue.get("max_reach").getAsFloat();
            if (!(cycle > 0 && contactStart >= 0 && contactEnd > contactStart && contactEnd <= cycle && width > 0 && thickness > 0 && reach > 0)) throw new IllegalArgumentException("Invalid tongue dimensions");
            return data.getAsJsonObject("models");
        } catch (IOException error) { throw new UncheckedIOException("Cannot load toilet presentation", error); }
    }

    static boolean authored(String behavior) {
        return switch (behavior) {
            case "toilet", "ground_toilet", "boat_toilet", "toilet_accident", "first_toilet_assist",
                    "first_toilet_wait", "first_toilet_stimulated" -> true;
            default -> false;
        };
    }

    static void renderTongue(YukkuriEntity parent, String behavior, float ticks, float partialTick,
                             YukkuriModel model, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        if (!behavior.equals("first_toilet_assist") || parent.careContactProgress() <= 0
                || !(parent.level().getEntity(parent.carePartnerId()) instanceof YukkuriEntity child)
                || !FamilyFeedingContact.rearContact(parent, child)) return;
        float scale = parent.getScale() * parent.growthScale();
        if (scale <= 0) return;
        float parentYaw = (180 - Mth.rotLerp(partialTick, parent.yBodyRotO, parent.yBodyRot)) * Mth.DEG_TO_RAD;
        float childYaw = (180 - Mth.rotLerp(partialTick, child.yBodyRotO, child.yBodyRot)) * Mth.DEG_TO_RAD;
        var delta = child.getPosition(partialTick).subtract(parent.getPosition(partialTick));
        var target = new Vector3f(0, .04f, child.getBbWidth() * .55f).rotateY(childYaw)
                .add((float)delta.x, (float)delta.y, (float)delta.z).rotateY(-parentYaw).div(scale);
        var mouth = model.mouthPoint().div(16);
        var toward = new Vector3f(target).sub(mouth);
        if (toward.lengthSquared() < 1e-6 || toward.lengthSquared() > reach * reach) return;
        // Four small licks across the 40-tick service phase; the tip contacts then retracts.
        float stroke = Mth.clamp((ticks % cycle - contactStart) / (contactEnd - contactStart), 0, 1);
        float extension = .15f + .85f * (float)Math.pow(Math.sin(Math.PI * stroke), 2);
        var end = new Vector3f(mouth).fma(extension, toward);
        var direction = new Vector3f(toward).normalize();
        var side = new Vector3f(direction).cross(0, 1, 0);
        if (side.lengthSquared() < 1e-6) side.set(1, 0, 0); else side.normalize();
        var up = new Vector3f(side).cross(direction).normalize();
        var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/care_tongue.png")));
        // A rounded, closed ribbon is a body attachment; it never becomes an entity or inventory object.
        Vector3f[][] rings = new Vector3f[7][8];
        for (int row = 0; row < rings.length; row++) {
            float t = row / 6f;
            float taper = row == 6 ? 0 : 1 - .25f * t;
            var centre = new Vector3f(mouth).lerp(end, t).fma(-.008f * (float)Math.sin(t * Math.PI), up);
            for (int corner = 0; corner < 8; corner++) {
                double angle = corner * Math.PI / 4;
                rings[row][corner] = new Vector3f(centre).fma((float)Math.cos(angle) * width * .5f * taper, side)
                        .fma((float)Math.sin(angle) * thickness * .5f * taper, up);
            }
        }
        for (int row = 0; row < 6; row++) for (int corner = 0; corner < 8; corner++) {
            int next = (corner + 1) % 8;
            Vector3f[] quad = {rings[row][corner], rings[row + 1][corner], rings[row + 1][next], rings[row][next]};
            var normal = new Vector3f(quad[1]).sub(quad[0]).cross(new Vector3f(quad[3]).sub(quad[0])).normalize();
            for (int i = 0; i < 4; i++) {
                var vertex = quad[i];
                buffer.addVertex(pose.last().pose(), vertex.x, vertex.y, vertex.z).setColor(255, 255, 255, 255)
                        .setUv(i == 0 || i == 3 ? 0 : 1, corner / 8f).setOverlay(overlay).setLight(light)
                        .setNormal(pose.last(), normal.x, normal.y, normal.z);
            }
        }
    }
}
