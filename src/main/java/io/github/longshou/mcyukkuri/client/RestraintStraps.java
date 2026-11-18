package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.interaction.RestraintTableBlock;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;

/** The two closed leather bands follow the visible body while their ends stay attached to the table. */
final class RestraintStraps {
    static final ResourceManagerReloadListener RELOAD = resources -> {
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("restraint_table/strap_fits.json")).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            var leather = data.getAsJsonObject("strap");
            var anchors = data.getAsJsonObject("anchors_px");
            var edges = anchors.getAsJsonArray("edges");
            Map<String, List<Band>> models = new HashMap<>();
            for (var model : data.getAsJsonObject("models").entrySet()) {
                List<Band> bands = new ArrayList<>();
                for (var element : model.getValue().getAsJsonArray()) {
                    var band = element.getAsJsonObject();
                    List<Vector2f> outline = new ArrayList<>();
                    for (var point : band.getAsJsonArray("hull")) outline.add(point(point.getAsJsonArray()));
                    bands.add(new Band(band.get("y").getAsFloat(), band.get("width").getAsFloat(), outline));
                }
                models.put(model.getKey(), bands);
            }
            var rows = anchors.getAsJsonArray("z");
            fits = new Fits(leather.get("width_px").getAsFloat(), leather.get("min_width_px").getAsFloat(),
                    leather.get("thickness_px").getAsFloat(), leather.get("clearance_px").getAsFloat(),
                    point(anchors.getAsJsonArray("west")), point(anchors.getAsJsonArray("east")),
                    point(edges.get(0).getAsJsonArray()), point(edges.get(1).getAsJsonArray()),
                    rows.get(0).getAsFloat() - 8, rows.get(1).getAsFloat() - 8, models);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    };

    private record Band(float height, float width, List<Vector2f> outline) {}
    private record Fits(float width, float minWidth, float thickness, float clearance, Vector2f west, Vector2f east,
                        Vector2f westEdge, Vector2f eastEdge, float northRow, float southRow, Map<String, List<Band>> models) {}
    private record Section(Vector3f north, Vector3f south, Vector3f outerNorth, Vector3f outerSouth) {}
    private static Fits fits;

    static void render(YukkuriView entity, float partialTick, PoseStack.Pose origin, Matrix4f bodyPose,
                       MultiBufferSource buffers, int light) {
        var offset = RestraintTableBlock.surface(entity.contactAnchor()).subtract(entity.getPosition(partialTick)).toVector3f();
        var toTable = new Matrix4f().translation(-offset.x, -offset.y, -offset.z)
                .mul(new Matrix4f(origin.pose()).invert().mul(bodyPose)).scale(1f / 16);
        var bands = fits.models.get(entity.assetId() + (entity.hasVisibleAccessory() ? "" : "_bare"));
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(MCYukkuri.id("block/restraint_table_strap"));
        var out = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        float scale = entity.getScale() * entity.growthScale();
        int north = toTable.m12() > 0 ? 0 : 1;
        ribbon(bands.get(north), fits.northRow, scale, offset, toTable, origin, out, sprite, light);
        ribbon(bands.get(1 - north), fits.southRow, scale, offset, toTable, origin, out, sprite, light);
    }

    private static void ribbon(Band band, float row, float scale, Vector3f offset, Matrix4f toTable,
                               PoseStack.Pose origin, VertexConsumer out, TextureAtlasSprite sprite, int light) {
        var center = new Vector2f();
        List<Vector2f> points = new ArrayList<>();
        for (var p : band.outline) {
            var posed = toTable.transformPosition(new Vector3f(p.x, band.height, p.y)).mul(16);
            var point = new Vector2f(posed.x, posed.y);
            points.add(point);
            center.add(point);
        }
        center.div(points.size());
        for (var p : points) p.add(new Vector2f(p).sub(center).normalize(fits.clearance));
        var hardware = List.of(fits.west, fits.east, fits.westEdge, fits.eastEdge);
        points.addAll(hardware);
        var hull = hull(points);
        // Walk the counterclockwise hull from the buckle, over the body, to the fixed end.
        int east = hull.indexOf(fits.east);
        int count = Math.floorMod(hull.indexOf(fits.west) - east, hull.size()) + 1;
        var chain = new ArrayList<Vector2f>(count);
        for (int k = 0; k < count; k++) chain.add(hull.get((east + k) % hull.size()));

        float bodyRow = toTable.transformPosition(new Vector3f(0, band.height, 0)).z * 16;
        float bodyWidth = Math.clamp(band.width * scale, fits.minWidth, fits.width);
        float length = 0;
        for (int i = 1; i < count; i++) length += chain.get(i - 1).distance(chain.get(i));
        Section previous = null;
        float traveled = 0;
        for (int i = 0; i < count; i++) {
            var p = chain.get(i);
            var tangent = new Vector2f(chain.get(Math.min(i + 1, count - 1))).sub(chain.get(Math.max(i - 1, 0)));
            var thickness = new Vector3f(tangent.y, -tangent.x, 0).normalize(fits.thickness / 16);
            boolean fixed = hardware.contains(p);
            float z = fixed ? row : bodyRow;
            float halfWidth = (fixed ? fits.width : bodyWidth) / 2;
            var near = new Vector3f(p.x, p.y, z - halfWidth).div(16).add(offset);
            var far = new Vector3f(p.x, p.y, z + halfWidth).div(16).add(offset);
            var next = new Section(near, far, new Vector3f(near).add(thickness), new Vector3f(far).add(thickness));
            if (i > 0) {
                float u0 = sprite.getU(traveled / length);
                traveled += chain.get(i - 1).distance(p);
                float u1 = sprite.getU(traveled / length);
                quad(origin, out, light, previous.outerNorth, previous.outerSouth, next.outerSouth, next.outerNorth,
                        u0, u1, sprite.getV(0), sprite.getV(2f / 16));
                quad(origin, out, light, previous.south, previous.north, next.north, next.south,
                        u0, u1, sprite.getV(1f / 16), sprite.getV(2f / 16));
                quad(origin, out, light, previous.north, previous.outerNorth, next.outerNorth, next.north,
                        u0, u1, sprite.getV(1f / 16), sprite.getV(1.25f / 16));
                quad(origin, out, light, previous.outerSouth, previous.south, next.south, next.outerSouth,
                        u0, u1, sprite.getV(1f / 16), sprite.getV(1.25f / 16));
            }
            previous = next;
        }
    }

    private static List<Vector2f> hull(List<Vector2f> points) {
        points.sort(Comparator.<Vector2f>comparingDouble(p -> p.x).thenComparingDouble(p -> p.y));
        List<Vector2f> hull = new ArrayList<>();
        for (var point : points) addTurn(hull, point, 0);
        int lower = hull.size();
        for (int i = points.size() - 2; i >= 0; i--) addTurn(hull, points.get(i), lower - 1);
        hull.removeLast();
        return hull;
    }

    private static void addTurn(List<Vector2f> hull, Vector2f p, int start) {
        while (hull.size() >= start + 2) {
            var a = hull.get(hull.size() - 2);
            var b = hull.getLast();
            if ((b.x - a.x) * (p.y - a.y) > (b.y - a.y) * (p.x - a.x)) break;
            hull.removeLast();
        }
        hull.add(p);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer out, int light, Vector3f a, Vector3f b, Vector3f c,
                             Vector3f d, float u0, float u1, float v0, float v1) {
        var normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).normalize();
        vertex(pose, out, light, a, normal, u0, v0);
        vertex(pose, out, light, b, normal, u0, v1);
        vertex(pose, out, light, c, normal, u1, v1);
        vertex(pose, out, light, d, normal, u1, v0);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer out, int light, Vector3f p, Vector3f normal, float u, float v) {
        out.addVertex(pose.pose(), p.x, p.y, p.z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
    }

    private static Vector2f point(JsonArray xy) { return new Vector2f(xy.get(0).getAsFloat(), xy.get(1).getAsFloat()); }

    private RestraintStraps() {}
}
