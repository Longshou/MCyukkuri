package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

/** Rear-care motion and a flat facial tongue expression, visible only during verified live care. */
final class ToiletPresentation {
    private static float cycle = 10, contactStart = 2, contactEnd = 7, width = .045f, length = .055f, faceOffset = .003f;
    private static float[][] profile = {{0, .8f}, {.25f, 1}, {.8f, 1}, {1, .55f}};
    /** The dropping's prop and the share of its full size it first shows at the opening. */
    private static final String WASTE = "yukkuri_waste";
    private static final float EMERGING = .35f;
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
            if (!(cycle > 0 && contactStart >= 0 && contactEnd > contactStart && contactEnd <= cycle && width > 0 && length > 0 && faceOffset >= 0 &&
                    faceOffset <= .005f)) throw new IllegalArgumentException("Invalid tongue dimensions");
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
            return data.getAsJsonObject("models");
        } catch (IOException error) { throw new UncheckedIOException("Cannot load toilet presentation", error); }
    }

    /** Contact at the child's rear: the first toilet and every leaf-swaddle care phase share its clip and lean. */
    static boolean rearCare(String behavior) {
        return switch (behavior) {
            case Behaviors.FIRST_TOILET_ASSIST, Behaviors.LEAF_SWADDLE_WRAP, Behaviors.SWADDLE_UNWRAP, Behaviors.SWADDLE_TOILET_ASSIST,
                    Behaviors.SWADDLE_CLEAN, Behaviors.SWADDLE_REWRAP -> true;
            default -> false;
        };
    }

    /** Rear care that licks (stimulating a release, cleaning); opening and wrapping the leaves are done with the closed mouth. */
    static boolean rearLick(String behavior) {
        return behavior.equals(Behaviors.FIRST_TOILET_ASSIST) || behavior.equals(Behaviors.SWADDLE_TOILET_ASSIST) || behavior.equals(Behaviors.SWADDLE_CLEAN);
    }

    static boolean authored(String behavior) {
        return switch (behavior) {
            case Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.BOAT_TOILET, Behaviors.TOILET_ACCIDENT, Behaviors.FIRST_TOILET_ASSIST,
                    Behaviors.FIRST_TOILET_WAIT, Behaviors.FIRST_TOILET_STIMULATED -> true;
            default -> false;
        };
    }

    static void renderTongue(YukkuriView parent, String behavior, float ticks, float partialTick,
                             YukkuriModel model, PoseStack pose, MultiBufferSource buffers, int light, int overlay) {
        boolean comfort = behavior.equals(Behaviors.COMFORT_LICK);
        YukkuriView child;
        if (!(comfort || rearLick(behavior)) || parent.careContactProgress() <= 0
                || (child = parent.yukkuriView(parent.carePartnerId())) == null
                || !(comfort ? FamilyFeedingContact.visibleMouthsMeet(parent, child) : FamilyFeedingContact.visibleRearContact(parent, child))) return;
        var face = LivingPresentation.variants.get(parent.assetId());
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
     * The stool leaves the model's own anus (油菊) as the straining clip pushes: ToiletContact.stool, the same stool the server lets go
     * as the real item at {@code DROP_TICKS} (ToiletContact.release), grows out along the rear-downward normal between
     * {@code EMERGE_FROM_TICKS} and that tick. It is drawn as the very dropping the loose item and the ground pile show afterwards
     * (HouseScrapRenderer, SupplyPileRenderer), standing on {@link ToiletContact.Stool#bottom} at the ground size, so the hand-over at
     * the drop tick changes neither shape, size nor place. Drawn in the model frame under the body's own clip pose.
     */
    static void renderStool(YukkuriView actor, String behavior, float ticks, PoseStack pose, MultiBufferSource buffers, int light) {
        if (!(behavior.equals(Behaviors.TOILET) || behavior.equals(Behaviors.GROUND_TOILET) || behavior.equals(Behaviors.BOAT_TOILET) || behavior.equals(Behaviors.TOILET_ACCIDENT))) return;
        if (ticks < ToiletContact.EMERGE_FROM_TICKS || ticks >= ToiletContact.DROP_TICKS) return;
        var stool = ToiletContact.stool(actor, behavior, ticks);
        if (stool == null) return;
        var at = stool.bottom();
        // Never nothing: a small first knob at the opening that swells to the full dropping.
        float bodyScale=actor.growthScale()*actor.getScale();
        float scale = WastePresentation.scale(actor.toiletVolume()) / bodyScale
                * (float) (EMERGING + (1 - EMERGING) * stool.grown());
        pose.pushPose();
        pose.translate(at.x, at.y, at.z);
        pose.scale(scale, scale, scale);
        PropModels.center(WASTE, pose, true);
        PropModels.render(WASTE, 8, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
