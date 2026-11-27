package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LeashRingTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/mcyukkuri/yukkuri");

    /** A model's rest vertices (stride 8) and its body and hair ranges, as the game and the preview pass them. */
    private record Rest(float[] vertices, int[] body, int[] hair) {
        LeashRing fit() { return LeashRing.fit(vertices, 8, body, hair); }
        LeashRing.Skin identity() {
            return (v, out) -> out.set(vertices[v * 8], vertices[v * 8 + 1], vertices[v * 8 + 2]);
        }
    }

    /** Every body model resource: a new species cannot be added without a rope check. */
    private static TreeMap<String, Rest> models() throws IOException {
        var all = new TreeMap<String, Rest>();
        try (Stream<Path> files = Files.list(MODELS)) {
            for (var path : (Iterable<Path>) files.sorted()::iterator) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".json") || name.equals("badge_mounts.json")) continue;
                var model = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                var data = model.getAsJsonArray("vertices");
                float[] vertices = new float[data.size()];
                for (int i = 0; i < vertices.length; i++) vertices[i] = data.get(i).getAsFloat();
                var body = new ArrayList<Integer>();
                var hair = new ArrayList<Integer>();
                for (var raw : model.getAsJsonArray("parts")) {
                    JsonObject part = raw.getAsJsonObject();
                    String partName = part.get("name").getAsString();
                    var into = LeashRing.bodySurface(partName) ? body
                            : LeashRing.hair(partName, part.has("anatomy") && !part.get("anatomy").getAsString().isEmpty()) ? hair : null;
                    if (into != null) { into.add(part.get("start").getAsInt()); into.add(part.get("count").getAsInt()); }
                }
                all.put(name.substring(0, name.length() - 5), new Rest(vertices, ints(body), ints(hair)));
            }
        }
        return all;
    }

    @Test
    void everyBodyModelGetsACleanRopeWithAKnotAtTheBack() throws IOException {
        for (var entry : models().entrySet()) {
            var rest = entry.getValue();
            var ring = rest.fit();
            var posed = rest.identity();
            ring.build(posed, 1f, false);
            String id = entry.getKey();
            assertTrue(ring.length() > 0 && ring.length() % LeashRing.TRIANGLE == 0, "mesh size for " + id);
            float[] mesh = ring.vertices();
            float minY = Float.MAX_VALUE;
            for (int r = 0; r < rest.body.length; r += 2)
                for (int v = rest.body[r]; v < rest.body[r] + rest.body[r + 1]; v++) minY = Math.min(minY, rest.vertices[v * 8 + 1]);
            float height = ring.bodyHeight();
            float frontMax = -Float.MAX_VALUE;
            for (int i = 0; i < ring.length(); i += 8) {
                for (int c = 0; c < 8; c++) assertTrue(Float.isFinite(mesh[i + c]), "non-finite vertex for " + id);
                assertTrue(mesh[i + 1] > minY - .02f * height, "rope below the floor for " + id);
                if (mesh[i + 2] < 0) frontMax = Math.max(frontMax, mesh[i + 1]);
            }
            assertTrue(frontMax < minY + .30f * height, "front rope reaches the face for " + id);
            // The knot is the last box: its centre sits behind the body at the lead's height.
            float ky = 0, kz = 0;
            int knotStart = ring.length() - 12 * LeashRing.TRIANGLE, count = 12 * 3;
            for (int i = knotStart; i < ring.length(); i += 8) { ky += mesh[i + 1]; kz += mesh[i + 2]; }
            ky /= count; kz /= count;
            assertTrue(kz > 0, "knot not at the back for " + id);
            assertEquals(minY + LeashRing.BACK_AT * height, ky, .05f * height, "knot height for " + id);
            // The tube is one closed ring: neighbouring bins stay close.
            for (int k = 0; k < LeashRing.BINS; k++) {
                int n = (k + 1) % LeashRing.BINS;
                int a = k * LeashRing.SIDES * 6 * 8, b = n * LeashRing.SIDES * 6 * 8;
                float d = (float) Math.sqrt(sq(mesh[a] - mesh[b]) + sq(mesh[a + 1] - mesh[b + 1]) + sq(mesh[a + 2] - mesh[b + 2]));
                assertTrue(d < 4f, "ring jumps between bins " + k + " and " + n + " for " + id + ": " + d);
            }
        }
    }

    @Test
    void ropeRidesTheSameSkinPointsThroughSquashAndTilt() throws IOException {
        var rest = models().get("reimu");
        var ring = rest.fit();
        ring.build(rest.identity(), 1f, false);
        float[] at = ring.vertices().clone();
        // Squashed, widened and tipped over, with a vertex-dependent bulge that no single rotation or scale could mimic.
        var move = new Matrix4f().translate(3, 1, -2).rotateZ(.35f).scale(1.15f, .8f, 1.1f);
        LeashRing.Skin bent = (v, out) -> {
            rest.identity().position(v, out);
            out.y += .4f * (float) Math.sin(out.x * .7f);
            move.transformPosition(out);
        };
        ring.build(bent, 1f, false);
        float[] bentMesh = ring.vertices();
        // The first corner of each bin's first side: moved by the same skin weights, so it stays within the rope's own radius
        // of where the moved rest corner lies once the bulge, which lifts rest points by up to .4, is taken out.
        var rope = new Vector3f();
        for (int k = 0; k < LeashRing.BINS; k++) {
            int i = k * LeashRing.SIDES * 6 * 8;
            rope.set(bentMesh[i], bentMesh[i + 1], bentMesh[i + 2]);
            var expect = new Vector3f(at[i], at[i + 1], at[i + 2]);
            expect.y += .4f * (float) Math.sin(expect.x * .7f);
            move.transformPosition(expect);
            assertTrue(rope.distance(expect) < 1.1f, "rope bin " + k + " left its skin point by " + rope.distance(expect));
        }
    }

    @Test
    void hairLowersTheRopeUnderItAndFarHairLeavesItAlone() {
        // A sphere of radius 5 with its centre on the axis, as triangles.
        var tris = new ArrayList<Float>();
        int rows = 12, cols = 24;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                float[][] q = {sphere(r, c, rows, cols), sphere(r + 1, c, rows, cols), sphere(r + 1, c + 1, rows, cols), sphere(r, c + 1, rows, cols)};
                for (int i : new int[] {0, 1, 2, 0, 2, 3}) for (float x : q[i]) tris.add(x);
            }
        float[] body = array(tris);
        float height = 10;
        // Hair strips (vertical triangles, a bin wide) over bin 10, flush on the skin, and over bin 20, three units off it.
        var hair = new ArrayList<Float>();
        strip(hair, 10, skinRadius(10) + .03f, height);
        strip(hair, 20, skinRadius(20) + 3f, height);
        float[] rest = new float[body.length + hair.size()];
        System.arraycopy(body, 0, rest, 0, body.length);
        for (int i = 0; i < hair.size(); i++) rest[body.length + i] = hair.get(i);
        var ring = LeashRing.fit(rest, 3, new int[] {0, body.length / 3}, new int[] {body.length / 3, hair.size() / 3});
        assertTrue(ring.lift(10) < -.9f * LeashRing.RADIUS, "flush hair sinks the rope: " + ring.lift(10));
        assertTrue(ring.lift(9) < -.5f * LeashRing.RADIUS && ring.lift(11) < -.5f * LeashRing.RADIUS, "neighbours sink as well");
        assertEquals(LeashRing.LIFT, ring.lift(20), 1e-4f, "far hair leaves the rope on the skin");
        assertEquals(LeashRing.LIFT, ring.lift(30), 1e-4f, "bare skin keeps the rope proud");
        assertTrue(Float.isInfinite(ring.clearance(30)));
    }

    @Test
    void ropeStaysOutOfEveryHairSurfaceAndStillShowsOnHairyModels() throws IOException {
        for (var id : List.of("reimu", "marisa", "patchouli", "reimu_bean", "marisa_bean")) {
            var ring = models().get(id).fit();
            int shown = 0;
            for (int k = 0; k < LeashRing.BINS; k++) {
                assertTrue(ring.lift(k) + LeashRing.RADIUS <= ring.clearance(k) + 1e-4f, "rope pokes through hair at bin " + k + " of " + id);
                if (ring.lift(k) > 0) shown++;
            }
            assertTrue(shown >= LeashRing.BINS / 2, id + " hides the rope at all but " + shown + " bins");
        }
    }

    private static void strip(List<Float> out, int bin, float radius, float height) {
        double theta = 2 * Math.PI * bin / LeashRing.BINS, half = Math.PI / LeashRing.BINS;
        float[] a = {(float) (radius * Math.sin(theta - half)), 0, (float) (radius * Math.cos(theta - half))};
        float[] b = {(float) (radius * Math.sin(theta + half)), 0, (float) (radius * Math.cos(theta + half))};
        for (float x : new float[] {a[0], 0, a[2], b[0], 0, b[2], b[0], height, b[2]}) out.add(x);
        for (float x : new float[] {a[0], 0, a[2], b[0], height, b[2], a[0], height, a[2]}) out.add(x);
    }

    /** Radius of the test sphere (centre height 5) at the girth height of a bin. */
    private static float skinRadius(int bin) {
        double back = Math.cos(2 * Math.PI * bin / LeashRing.BINS);
        double y = (LeashRing.FRONT_AT + (LeashRing.BACK_AT - LeashRing.FRONT_AT) * (1 + back) / 2) * 10;
        return (float) Math.sqrt(25 - (5 - y) * (5 - y));
    }

    private static float[] sphere(int row, int col, int rows, int cols) {
        double phi = Math.PI * row / rows, theta = 2 * Math.PI * col / cols;
        return new float[] {(float) (5 * Math.sin(phi) * Math.sin(theta)), (float) (5 - 5 * Math.cos(phi)), (float) (5 * Math.sin(phi) * Math.cos(theta))};
    }

    private static float sq(float x) { return x * x; }
    private static int[] ints(List<Integer> list) { return list.stream().mapToInt(Integer::intValue).toArray(); }
    private static float[] array(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
