package io.github.longshou.mcyukkuri.pose.cli;

import static io.github.longshou.mcyukkuri.body.WoundInk.A;
import static io.github.longshou.mcyukkuri.body.WoundInk.B;
import static io.github.longshou.mcyukkuri.body.WoundInk.C;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.body.BodyWound;
import io.github.longshou.mcyukkuri.body.SurfaceMark;
import io.github.longshou.mcyukkuri.body.SurfaceMark.NormalKey;
import io.github.longshou.mcyukkuri.body.WoundInk;
import io.github.longshou.mcyukkuri.body.WoundInk.Bary;
import io.github.longshou.mcyukkuri.body.WoundInk.Piece;
import io.github.longshou.mcyukkuri.body.WoundKind;
import io.github.longshou.mcyukkuri.body.WoundProfiles;
import io.github.longshou.mcyukkuri.body.WoundShape;
import io.github.longshou.mcyukkuri.body.WoundSurface;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.pose.Skin;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Poses the selected runtime overlays alongside the live review actor. */
final class ReviewMarks {
    static final class Wet {
        private final WoundSurface mesh;
        private final List<SurfaceMark> marks = new ArrayList<>();
        private final Vector3f[] rest, posed, normals;
        private final int[] vertices, triangles, slots;
        private final Vector3f temp = new Vector3f(), point = new Vector3f(), normal = new Vector3f(), edge = new Vector3f();

        Wet(String id) {
            mesh = WoundSurface.get(id);
            SurfaceMark.soleTexture(mesh, SurfaceMark.LEAK_WET_MATERIAL, marks);
            var groups = new HashMap<NormalKey, Integer>();
            for (var mark : marks) {
                int first = mark.a().triangle();
                var face = mesh.normal(first);
                for (int j = 0; j < 3; j++)
                    groups.computeIfAbsent(NormalKey.of(mesh.rest(first + j), face), ignored -> groups.size());
            }
            normals = new Vector3f[groups.size()];
            Arrays.setAll(normals, ignored -> new Vector3f());
            slots = new int[mesh.weights.length];
            rest = new Vector3f[slots.length];
            posed = new Vector3f[slots.length];
            var usedTriangles = new BitSet(slots.length / 3);
            var usedVertices = new BitSet(slots.length);
            var faceCard = new Vector3f(0, 0, -1);
            for (int first = 0; first < slots.length; first += 3) {
                var face = first < mesh.skinCount ? mesh.normal(first) : faceCard;
                for (int j = 0; j < 3; j++) {
                    int vertex = first + j;
                    slots[vertex] = groups.getOrDefault(NormalKey.of(mesh.rest(vertex), face), -1);
                    if (slots[vertex] >= 0) usedTriangles.set(first / 3);
                }
                if (usedTriangles.get(first / 3)) usedVertices.set(first, first + 3);
            }
            vertices = usedVertices.stream().toArray();
            triangles = usedTriangles.stream().map(index -> index * 3).toArray();
            for (int vertex : vertices) {
                rest[vertex] = mesh.rest(vertex);
                posed[vertex] = new Vector3f();
            }
        }

        float[] draw(Matrix4f[] world, float scale) {
            for (int vertex : vertices) Skin.weighted(mesh.weights[vertex], rest[vertex], world, posed[vertex], temp);
            SurfaceMark.smoothNormals(posed, triangles, slots, normals, normal, edge);
            var result = new float[marks.size() * 24];
            int at = 0;
            for (var mark : marks) {
                at = vertex(result, at, mark.a(), mark, 0, scale);
                at = vertex(result, at, mark.b(), mark, 2, scale);
                at = vertex(result, at, mark.c(), mark, 4, scale);
            }
            return result;
        }

        private int vertex(float[] result, int at, WoundSurface.Hit hit, SurfaceMark mark, int uv, float scale) {
            SurfaceMark.pointNormal(normals, slots, hit, normal);
            SurfaceMark.point(posed, hit, mark.offset(), normal, point);
            return RenderPose.emitVert(result, at, point.mul(scale), mark.uv()[uv], mark.uv()[uv + 1], normal);
        }
    }

    private record Fragment(int first, Bary[] corners, WoundInk.Plane plane) {}

    static final class Scratch {
        final JsonObject spec;
        private final List<WoundInk.Plane> wounds = new ArrayList<>();
        private final WoundSurface mesh;
        private final JsonObject model;
        private final List<String> hair;
        final String texture;
        private final List<Fragment> fragments = new ArrayList<>();
        private String lastExpression;
        private final Vector3f[] posed = {new Vector3f(), new Vector3f(), new Vector3f()};
        private final float[] uv = new float[6];
        private final Vector3f normal = new Vector3f(), edge = new Vector3f(), temp = new Vector3f();

        Scratch(String id, JsonObject spec) {
            this.spec = spec;
            mesh = WoundSurface.get(id);
            int grade = spec.get("grade").getAsInt();
            if (grade < 1 || grade > 3) throw new IllegalArgumentException("Expected scratch grade 1–3");
            var shape = WoundShape.ABRASION;
            var profiles = GeometryJson.read("yukkuri_presentation/hair_wounds.json").data();
            model = profiles.getAsJsonObject("models").getAsJsonObject(id);
            hair = model.getAsJsonArray("hair_parts").asList().stream().map(value -> value.getAsString()).toList();
            var profile = profiles.getAsJsonObject("profiles").getAsJsonObject(shape.id()).getAsJsonObject(Integer.toString(grade));
            var radius = profile.getAsJsonArray("radius");
            var fit = WoundProfiles.fit(id);
            for (var raw : spec.getAsJsonArray("sites")) {
                var site = raw.getAsJsonArray();
                float az = site.get(0).getAsFloat();
                var direction = new Vector3f((float)Math.sin(az), 0, -(float)Math.cos(az));
                // Launch outside the selected skin, in the authored injury frame.
                var origin = new Vector3f(fit.center()).add(0, site.get(1).getAsFloat() * fit.scale().y, 0).fma(32, direction);
                var hit = mesh.ray(origin, direction.negate(), null, List.of());
                var wound = new BodyWound(new UUID(0, wounds.size()), WoundKind.SCRATCH, hit.triangle(), hit.u(), hit.v(), grade, 1, 0, 0);
                wounds.add(new WoundInk.Plane(mesh, wound, fit.scale(), fit.offset(), radius.get(0).getAsFloat(), radius.get(1).getAsFloat(), null));
            }
            texture = WoundInk.TEXTURE_DIR + WoundInk.material(shape, grade) + ".png";
        }

        private void prepare(RenderPose actor, String expression) {
            lastExpression = expression;
            fragments.clear();
            String body = model.get("body_part").getAsString();
            for (var raw : actor.data.getAsJsonArray("parts")) {
                var part = raw.getAsJsonObject();
                String name = part.get("name").getAsString();
                if (!(name.equals(body) || hair.contains(name) || name.startsWith("face_") || mesh.replaced.contains(name))) continue;
                int start = part.get("start").getAsInt(), end = start + part.get("count").getAsInt();
                for (int first = start; first < end; first += 3) {
                    if (name.startsWith("face_") && actor.source[first * 8 + 7] >= -.15f) continue;
                    var a = A.point(actor.source, first);
                    var b = B.point(actor.source, first);
                    var c = C.point(actor.source, first);
                    for (var plane : wounds) {
                        if (!plane.bounds(a, b, c, 1.25f, false) || !plane.facing(a, b, c)) continue;
                        var polygon = List.of(new Piece(plane.x(a), plane.y(a), A),
                                new Piece(plane.x(b), plane.y(b), B), new Piece(plane.x(c), plane.y(c), C));
                        for (var corners : plane.ink(polygon)) fragments.add(new Fragment(first, corners, plane));
                    }
                }
            }
        }

        float[] draw(RenderPose actor, String expression, float scale) {
            if (!expression.equals(lastExpression)) prepare(actor, expression);
            var result = new float[fragments.size() * 24];
            int at = 0;
            for (var fragment : fragments) {
                for (int j = 0; j < 3; j++) {
                    var bary = fragment.corners[j];
                    var rest = bary.point(actor.source, fragment.first);
                    uv[j * 2] = fragment.plane.u(rest);
                    uv[j * 2 + 1] = fragment.plane.v(rest);
                    Skin.fragment(fragment.first, bary.a(), bary.b(), bary.c(), rest, posed[j], temp,
                            actor.bindings, actor.world, actor.features, actor.featureTransforms);
                }
                normal.set(posed[1]).sub(posed[0]).cross(edge.set(posed[2]).sub(posed[0]));
                if (normal.lengthSquared() < 1e-12) continue;
                normal.normalize();
                WoundInk.lift(posed[0], posed[1], posed[2], normal, true, uv[0], 0);
                for (int j = 0; j < 3; j++) at = RenderPose.emitVert(result, at, posed[j].mul(scale), uv[j * 2], uv[j * 2 + 1], normal);
            }
            return Arrays.copyOf(result, at);
        }
    }

    private ReviewMarks() {}
}
