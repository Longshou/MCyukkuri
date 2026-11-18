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
    private static final class Section {
        final Vector3f north = new Vector3f(), south = new Vector3f(), outerNorth = new Vector3f(), outerSouth = new Vector3f();
    }
    private static final class Scratch {
        final Matrix4f toTable = new Matrix4f(), invOrigin = new Matrix4f();
        final Vector3f offset = new Vector3f(), posed = new Vector3f(), thickness = new Vector3f(), edge = new Vector3f(), normal = new Vector3f();
        final Vector2f center = new Vector2f(), delta = new Vector2f(), tangent = new Vector2f();
        final List<Vector2f> pool = new ArrayList<>(), points = new ArrayList<>(), hull = new ArrayList<>(), chain = new ArrayList<>();
        Section prev = new Section(), curr = new Section();

        Vector2f pooled(int index) {
            while (pool.size() <= index) pool.add(new Vector2f());
            return pool.get(index);
        }
    }
    private static final Comparator<Vector2f> POINT_ORDER = Comparator.<Vector2f>comparingDouble(p -> p.x).thenComparingDouble(p -> p.y);
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static Fits fits;

    static void render(YukkuriView entity, float partialTick, PoseStack.Pose origin, Matrix4f bodyPose,
                       MultiBufferSource buffers, int light) {
        var s = SCRATCH.get();
        var anchor = RestraintTableBlock.surface(entity.contactAnchor());
        var pos = entity.getPosition(partialTick);
        s.offset.set((float) (anchor.x - pos.x), (float) (anchor.y - pos.y), (float) (anchor.z - pos.z));
        s.invOrigin.set(origin.pose()).invert().mul(bodyPose);
        s.toTable.translation(-s.offset.x, -s.offset.y, -s.offset.z).mul(s.invOrigin).scale(1f / 16);
        var bands = fits.models.get(entity.assetId() + (entity.hasVisibleAccessory() ? "" : "_bare"));
        var sprite = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(MCYukkuri.id("block/restraint_table_strap"));
        var out = buffers.getBuffer(RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS));
        float scale = entity.getScale() * entity.growthScale();
        int north = s.toTable.m12() > 0 ? 0 : 1;
        ribbon(s, bands.get(north), fits.northRow, scale, out, sprite, light, origin);
        ribbon(s, bands.get(1 - north), fits.southRow, scale, out, sprite, light, origin);
    }

    private static void ribbon(Scratch s, Band band, float row, float scale, VertexConsumer out,
                               TextureAtlasSprite sprite, int light, PoseStack.Pose origin) {
        s.center.set(0, 0);
        s.points.clear();
        int outlineCount = band.outline.size();
        for (int i = 0; i < outlineCount; i++) {
            var p = band.outline.get(i);
            s.posed.set(p.x, band.height, p.y);
            s.toTable.transformPosition(s.posed).mul(16);
            var point = s.pooled(i).set(s.posed.x, s.posed.y);
            s.points.add(point);
            s.center.add(point);
        }
        s.center.div(outlineCount);
        for (int i = 0; i < outlineCount; i++) {
            var p = s.points.get(i);
            p.add(s.delta.set(p).sub(s.center).normalize(fits.clearance));
        }
        s.points.add(fits.west);
        s.points.add(fits.east);
        s.points.add(fits.westEdge);
        s.points.add(fits.eastEdge);
        hull(s.points, s.hull);
        // Walk the counterclockwise hull from the buckle, over the body, to the fixed end.
        int east = s.hull.indexOf(fits.east);
        int count = Math.floorMod(s.hull.indexOf(fits.west) - east, s.hull.size()) + 1;
        s.chain.clear();
        for (int k = 0; k < count; k++) s.chain.add(s.hull.get((east + k) % s.hull.size()));

        float bodyRow = s.toTable.transformPosition(s.posed.set(0, band.height, 0)).z * 16;
        float bodyWidth = Math.clamp(band.width * scale, fits.minWidth, fits.width);
        float length = 0;
        for (int i = 1; i < count; i++) length += s.chain.get(i - 1).distance(s.chain.get(i));
        float traveled = 0;
        for (int i = 0; i < count; i++) {
            var p = s.chain.get(i);
            s.tangent.set(s.chain.get(Math.min(i + 1, count - 1))).sub(s.chain.get(Math.max(i - 1, 0)));
            s.thickness.set(s.tangent.y, -s.tangent.x, 0).normalize(fits.thickness / 16);
            boolean fixed = p.equals(fits.west) || p.equals(fits.east) || p.equals(fits.westEdge) || p.equals(fits.eastEdge);
            float z = fixed ? row : bodyRow;
            float halfWidth = (fixed ? fits.width : bodyWidth) / 2;
            var next = s.curr;
            next.north.set(p.x, p.y, z - halfWidth).div(16).add(s.offset);
            next.south.set(p.x, p.y, z + halfWidth).div(16).add(s.offset);
            next.outerNorth.set(next.north).add(s.thickness);
            next.outerSouth.set(next.south).add(s.thickness);
            if (i > 0) {
                var previous = s.prev;
                float u0 = sprite.getU(traveled / length);
                traveled += s.chain.get(i - 1).distance(p);
                float u1 = sprite.getU(traveled / length);
                quad(s, origin, out, light, previous.outerNorth, previous.outerSouth, next.outerSouth, next.outerNorth,
                        u0, u1, sprite.getV(0), sprite.getV(2f / 16));
                quad(s, origin, out, light, previous.south, previous.north, next.north, next.south,
                        u0, u1, sprite.getV(1f / 16), sprite.getV(2f / 16));
                quad(s, origin, out, light, previous.north, previous.outerNorth, next.outerNorth, next.north,
                        u0, u1, sprite.getV(1f / 16), sprite.getV(1.25f / 16));
                quad(s, origin, out, light, previous.outerSouth, previous.south, next.south, next.outerSouth,
                        u0, u1, sprite.getV(1f / 16), sprite.getV(1.25f / 16));
            }
            s.curr = s.prev;
            s.prev = next;
        }
    }

    private static void hull(List<Vector2f> points, List<Vector2f> hull) {
        points.sort(POINT_ORDER);
        hull.clear();
        for (int i = 0; i < points.size(); i++) addTurn(hull, points.get(i), 0);
        int lower = hull.size();
        for (int i = points.size() - 2; i >= 0; i--) addTurn(hull, points.get(i), lower - 1);
        hull.removeLast();
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

    private static void quad(Scratch s, PoseStack.Pose pose, VertexConsumer out, int light, Vector3f a, Vector3f b,
                             Vector3f c, Vector3f d, float u0, float u1, float v0, float v1) {
        s.normal.set(b).sub(a).cross(s.edge.set(c).sub(a)).normalize();
        vertex(pose, out, light, a, s.normal, u0, v0);
        vertex(pose, out, light, b, s.normal, u0, v1);
        vertex(pose, out, light, c, s.normal, u1, v1);
        vertex(pose, out, light, d, s.normal, u1, v0);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer out, int light, Vector3f p, Vector3f normal, float u, float v) {
        out.addVertex(pose.pose(), p.x, p.y, p.z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, normal.x, normal.y, normal.z);
    }

    private static Vector2f point(JsonArray xy) { return new Vector2f(xy.get(0).getAsFloat(), xy.get(1).getAsFloat()); }

    private RestraintStraps() {}
}
