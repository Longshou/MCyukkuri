package io.github.longshou.mcyukkuri.client;

import io.github.longshou.mcyukkuri.entity.Behaviors;
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

/** Rear-care motion and a flat facial tongue expression, visible only during verified live care. */
final class ToiletPresentation {
    private static float cycle = 10, contactStart = 2, contactEnd = 7, width = .045f, length = .055f, faceOffset = .003f;
    private static float[][] profile = {{0, .8f}, {.25f, 1}, {.8f, 1}, {1, .55f}};
    /** Anus (rump mark) centre of each rig in rest-pose model pixels, exported with the straining clip (toilet_contact.json "outlet"). */
    private static java.util.Map<String, float[]> sites = java.util.Map.of();
    private static final int[] STOOL = {96, 62, 32};
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
            length = tongue.get("length").getAsFloat();
            faceOffset = tongue.get("face_offset").getAsFloat();
            if (!tongue.get("placement").getAsString().equals("face_plane")) throw new IllegalArgumentException("Tongue must remain on the face plane");
            if (!(cycle > 0 && contactStart >= 0 && contactEnd > contactStart && contactEnd <= cycle && width > 0 && length > 0 && faceOffset >= 0 && faceOffset <= .005f)) throw new IllegalArgumentException("Invalid tongue dimensions");
            var outline = tongue.getAsJsonArray("profile");
            float[][] loaded = new float[outline.size()][2];
            if (loaded.length < 2) throw new IllegalArgumentException("Missing facial tongue outline");
            for (int i = 0; i < loaded.length; i++) {
                var row = outline.get(i).getAsJsonArray();
                loaded[i][0] = row.get(0).getAsFloat(); loaded[i][1] = row.get(1).getAsFloat();
                if (!(loaded[i][0] >= 0 && loaded[i][0] <= 1 && loaded[i][1] > 0 && loaded[i][1] <= 1)
                        || i > 0 && loaded[i][0] <= loaded[i - 1][0]) throw new IllegalArgumentException("Invalid facial tongue outline");
            }
            if (loaded[0][0] != 0 || loaded[loaded.length - 1][0] != 1) throw new IllegalArgumentException("Incomplete facial tongue outline");
            profile = loaded;
            var models = data.getAsJsonObject("models");
            var found = new java.util.HashMap<String, float[]>();
            for (var entry : models.entrySet()) {
                var outlet = entry.getValue().getAsJsonObject().getAsJsonObject("outlet");
                if (outlet == null) continue;
                var site = outlet.getAsJsonArray("site_px");
                found.put(entry.getKey(), new float[]{site.get(0).getAsFloat(), site.get(1).getAsFloat(), site.get(2).getAsFloat()});
            }
            sites = java.util.Map.copyOf(found);
            return models;
        } catch (IOException error) { throw new UncheckedIOException("Cannot load toilet presentation", error); }
    }

    static boolean authored(String behavior) {
        return switch (behavior) {
            case Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.BOAT_TOILET, Behaviors.TOILET_ACCIDENT, Behaviors.FIRST_TOILET_ASSIST,
                    Behaviors.FIRST_TOILET_WAIT, Behaviors.FIRST_TOILET_STIMULATED -> true;
            default -> false;
        };
    }

    static void renderTongue(YukkuriEntity parent, String behavior, float ticks, float partialTick,
                             YukkuriModel model, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        boolean comfort = behavior.equals(Behaviors.COMFORT_LICK);
        if (!(comfort || behavior.equals(Behaviors.FIRST_TOILET_ASSIST)) || parent.careContactProgress() <= 0
                || !(parent.level().getEntity(parent.carePartnerId()) instanceof YukkuriEntity child)
                || !(comfort ? FamilyFeedingContact.mouthsMeet(parent, child) : FamilyFeedingContact.rearContact(parent, child))) return;
        var face = LivingPresentation.variants.get(parent.subspecies().assetId());
        if (face == null) return;
        // A facial expression: animate down the mouth's own XY plane, never toward the other entity.
        float stroke = Mth.clamp((ticks % cycle - contactStart) / (contactEnd - contactStart), 0, 1);
        float extension = .15f + .85f * (float)Math.pow(Math.sin(Math.PI * stroke), 2);
        float size = parent.getVariant().width() / .75f;
        float shownLength = length * size * extension;
        var root = model.rootPose();
        var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(MCYukkuri.id("textures/entity/care_tongue.png")));
        var normal = root.transformDirection(new Vector3f(0, 0, -1)).normalize();
        for (int row = 0; row + 1 < profile.length; row++) {
            int[] rows = {row, row + 1, row + 1, row};
            for (int i = 0; i < 4; i++) {
                var edge = profile[rows[i]];
                float x = face.x() + (i < 2 ? -1 : 1) * width * size * .5f * edge[1];
                float y = face.y() - shownLength * edge[0];
                // Tiny constant offset prevents z-fighting; every vertex stays in the same facial plane.
                var vertex = new Vector3f(x, y, face.z() - faceOffset).mul(16);
                root.transformPosition(vertex).div(16);
                buffer.addVertex(pose.last().pose(), vertex.x, vertex.y, vertex.z).setColor(255, 255, 255, 255)
                        .setUv(i < 2 ? 0 : 1, edge[0]).setOverlay(overlay).setLight(light)
                        .setNormal(pose.last(), normal.x, normal.y, normal.z);
            }
        }
    }

    /**
     * The stool leaves the model's own anus (油菊) as the straining clip pushes: it grows out of the rump along the rear-downward
     * normal between {@code EMERGE_FROM_TICKS} and {@code DROP_TICKS}, the tick at which the server lets the real item fall from the
     * same point (ToiletContact.anus). Drawn in the model's posed root frame, so squat, squash and yaw are all followed.
     */
    static void renderStool(YukkuriEntity actor, String behavior, float ticks, YukkuriModel model, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!(behavior.equals(Behaviors.TOILET) || behavior.equals(Behaviors.GROUND_TOILET) || behavior.equals(Behaviors.BOAT_TOILET) || behavior.equals(Behaviors.TOILET_ACCIDENT))) return;
        float from = io.github.longshou.mcyukkuri.life.ToiletContact.EMERGE_FROM_TICKS, to = io.github.longshou.mcyukkuri.life.ToiletContact.DROP_TICKS;
        if (ticks < from || ticks > to + 1) return;
        var site = sites.get(actor.subspecies().assetId());
        if (site == null) return;
        float q = Mth.clamp((ticks - from) / (to - from), 0, 1);
        q = q * q * (3 - 2 * q);
        float size = actor.getVariant().width() / .75f;
        var root = model.rootPose();
        // Starts just inside the skin so the sausage seems to come out of the opening; leaves along the rear-downward normal.
        var inside = new Vector3f(site[0], site[1] + .15f, site[2] - .25f);
        var direction = root.transformDirection(new Vector3f(0, -.26f, .965f)).normalize();
        var start = root.transformPosition(new Vector3f(inside)).div(16);
        float length = .165f * size * q, radius = .036f * size * (.55f + .45f * q);
        var end = new Vector3f(start).add(direction.x * length, direction.y * length, direction.z * length);
        var tip = new Vector3f(end).add(direction.x * radius * .8f, direction.y * radius * .8f, direction.z * radius * .8f);
        var buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/block/white_concrete.png")));
        FamilyRenderer.tube(pose, buffer, new net.minecraft.world.phys.Vec3(start.x, start.y, start.z), new net.minecraft.world.phys.Vec3(end.x, end.y, end.z),
                radius * .8, radius, light, STOOL);
        FamilyRenderer.tube(pose, buffer, new net.minecraft.world.phys.Vec3(end.x, end.y, end.z), new net.minecraft.world.phys.Vec3(tip.x, tip.y, tip.z),
                radius, radius * .35, light, STOOL);
    }
}
