package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.headwear.HatDamageGeometry;
import io.github.longshou.mcyukkuri.pose.Rig;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import javax.imageio.ImageIO;
import org.joml.Vector3f;

/** Packaged prop triangles and materials, loaded once per player entry. */
final class ReviewProps {
    private static final int[] TRIANGLES = {0, 1, 2, 0, 2, 3};

    static JsonObject load(Path assets, JsonObject request) throws IOException {
        String id = request.get("mesh").getAsString();
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid runtime prop id");
        var model = OfflineModel.read(assets.resolve("yukkuri_props/" + id + ".json"));
        var vertices = Rig.floats(model.getAsJsonArray("vertices"));
        var meshes = new JsonArray();
        int damage = request.has("damage") ? request.get("damage").getAsInt() : 0;
        var pattern = damage > 0 ? HatDamageGeometry.read(ReviewActor.read(assets, "hat_damage")) : null;
        var bounds = HatDamageGeometry.bounds(vertices, new int[]{0, vertices.length / 8});
        var images = new HashMap<String, BufferedImage>();
        var caps = new JsonArray();
        var colors = new JsonArray();
        for (var raw : model.getAsJsonArray("parts")) {
            var part = raw.getAsJsonObject();
            int start = part.get("start").getAsInt(), count = part.get("count").getAsInt();
            String texture = part.has("texture") ? part.get("texture").getAsString().replace(':', '/')
                    : "mcyukkuri/textures/props/" + id + ".png";
            var mesh = new JsonObject();
            mesh.addProperty("texture", texture);
            if (part.has("tint")) mesh.add("tint", part.get("tint"));
            if (part.has("translucent")) mesh.add("translucent", part.get("translucent"));
            if (damage > 0) {
                if (!images.containsKey(texture))
                    images.put(texture, ImageIO.read(assets.getParent().resolve(texture).toFile()));
                var faces = HatDamageGeometry.layout(vertices, start, count, part.get("name").getAsString(), bounds, damage, pattern);
                mesh.add("vertices", damaged(vertices, faces, images.get(texture), caps, colors));
            } else {
                mesh.add("vertices", OfflineModel.array(Arrays.copyOfRange(vertices, start * 8, (start + count) * 8)));
            }
            meshes.add(mesh);
        }
        if (!caps.isEmpty()) {
            var mesh = new JsonObject();
            mesh.addProperty("texture", "mcyukkuri/textures/props/hat_damage_inner.png");
            mesh.add("vertices", caps);
            mesh.add("colors", colors);
            meshes.add(mesh);
        }
        var result = new JsonObject();
        result.add("meshes", meshes);
        if (request.has("form")) {
            var vessel = ReviewActor.read(assets, "hat_vessel");
            var fit = new JsonObject();
            fit.add("scale", vessel.getAsJsonObject("scale").get(request.get("form").getAsString()));
            fit.add("anchor", vessel.get("anchor_worn"));
            result.add("fit", fit);
            var points = new LinkedHashSet<Vector3f>();
            for (int i = 0; i < vertices.length; i += 8)
                points.add(new Vector3f(vertices[i], vertices[i + 1], vertices[i + 2]));
            var outline = new JsonArray();
            for (var point : points) {
                outline.add(point.x); outline.add(point.y); outline.add(point.z);
            }
            result.add("outline", outline);
        }
        return result;
    }

    private static JsonArray damaged(float[] vertices, HatDamageGeometry.Layout[] faces, BufferedImage texture,
                                     JsonArray caps, JsonArray colors) {
        var cloth = new JsonArray();
        var points = new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        var corners = new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        var normal = new Vector3f();
        var scratch = new Vector3f();
        var u = new float[4];
        var v = new float[4];
        for (var face : faces) {
            if (face.original()) {
                int end = (face.originalStart() + face.originalCount()) * 8;
                for (int i = face.originalStart() * 8; i < end; i++) cloth.add(vertices[i]);
                continue;
            }
            var indices = face.corners();
            for (int k = 0; k < 4; k++) {
                int at = indices[k] * 8;
                corners[k].set(vertices[at], vertices[at + 1], vertices[at + 2]);
                u[k] = vertices[at + 3];
                v[k] = vertices[at + 4];
            }
            int n = face.n(), m = face.m();
            for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
                byte code = face.code(i, j);
                if (!HatDamageGeometry.cell(corners, n, m, i, j, code, face.flip(), points, normal, scratch)) continue;
                if (code == HatDamageGeometry.DRAW) {
                    for (int k : TRIANGLES) {
                        float s = (i + (k == 1 || k == 2 ? 1f : 0f)) / n;
                        float t = (j + (k >= 2 ? 1f : 0f)) / m;
                        vertex(cloth, points[k], uv(u, s, t), uv(v, s, t), normal);
                    }
                } else {
                    float s = (i + .5f) / n, t = (j + .5f) / m;
                    int x = (int) (Math.clamp(uv(u, s, t), 0f, .9999f) * texture.getWidth());
                    int y = (int) (Math.clamp(uv(v, s, t), 0f, .9999f) * texture.getHeight());
                    int rgb = texture.getRGB(x, y);
                    if ((rgb >>> 24) < 20) rgb = 0xc8c8c8;
                    int color = HatDamageGeometry.capRim(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255)
                            [code == HatDamageGeometry.HIDDEN ? 0 : 1];
                    for (int k : TRIANGLES) {
                        vertex(caps, points[k], .5f, .5f, normal);
                        colors.add(color);
                    }
                }
            }
        }
        return cloth;
    }

    private static float uv(float[] values, float s, float t) {
        return HatDamageGeometry.bilerp(values[0], values[1], values[2], values[3], s, t);
    }

    private static void vertex(JsonArray out, Vector3f p, float u, float v, Vector3f normal) {
        out.add(p.x); out.add(p.y); out.add(p.z); out.add(u); out.add(v);
        out.add(normal.x); out.add(normal.y); out.add(normal.z);
    }

    private ReviewProps() {}
}
