package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.pose.BraidClearance;
import io.github.longshou.mcyukkuri.pose.ClipBlend;
import io.github.longshou.mcyukkuri.pose.Compose;
import io.github.longshou.mcyukkuri.pose.ContactBounds;
import io.github.longshou.mcyukkuri.pose.WallYield;
import io.github.longshou.mcyukkuri.pose.check.Geometry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Carry frames 110 and 146, run 20261120-061847-eb82b9. Both endpoints use the frame under test's placement. */
class PoseBridgePenetrationTest {
    private static OfflineModel model;
    private static ClipBlend fit;
    private static BraidClearance clearance;
    private static WallYield wall;
    private static final List<WallYield.Face> nest = new ArrayList<>();
    private record Draw(String outgoing, float last, String incoming, float now, Matrix4f basis, boolean againstNest) {}

    @BeforeAll static void load() throws Exception {
        var resource = PoseBridgePenetrationTest.class.getResource("/assets/mcyukkuri/yukkuri/marisa.json");
        model = new OfflineModel(Path.of(resource.toURI()).getParent().getParent(), "marisa");
        var parts = model.data.getAsJsonArray("parts");
        fit = new ClipBlend(model.bones, model.bindings, parts);
        clearance = BraidClearance.of(parts);
        var names = new String[parts.size()];
        int[] starts = new int[names.length], counts = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            var p = parts.get(i).getAsJsonObject();
            names[i] = p.get("name").getAsString();
            starts[i] = p.get("start").getAsInt();
            counts[i] = p.get("count").getAsInt();
        }
        wall = WallYield.of("marisa", model.bones, model.bindings, model.vertices, names, starts, counts);
        // Optimized Minecraft nest collision faces in the mother's frame, as used by WallClearance.
        var faces = PoseBridgePenetrationTest.class.getResource("/pose-bridge/carry-110-nest-faces.json");
        try (var reader = Files.newBufferedReader(Path.of(faces.toURI()))) {
            for (var raw : JsonParser.parseReader(reader).getAsJsonArray()) {
                var a = raw.getAsJsonArray();
                nest.add(new WallYield.Face(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(),
                        a.get(3).getAsFloat(), a.get(4).getAsFloat()));
            }
        }
    }

    private static Geometry.Mesh mesh(Draw draw, String group, float weight, boolean external) {
        model.sample(draw.outgoing, draw.last, null);
        if (weight >= 0) {
            var from = new float[model.pose.length * 9];
            for (int i = 0; i < model.pose.length; i++) System.arraycopy(model.pose[i], 0, from, i * 9, 9);
            model.sample(draw.incoming, draw.now, null);
            if (weight < 1) {
                // A wall takes its fitted frame immediately. A peer can claim it after the bounds test.
                fit.apply(model.pose, from, weight, external && draw.againstNest);
                if (external && !draw.againstNest) fit.takeContactFrame(model.pose, from);
                compose();
                if (!external && !clearance.clear((i, dst) -> dst.set(model.skin(i)), draw.basis)) {
                    fit.takeContactFrame(model.pose, from);
                    compose();
                }
            }
        }
        var triangles = new ArrayList<Geometry.Triangle>();
        Matrix4f toWall = null;
        if (draw.againstNest && group.equals("braid")) {
            toWall = new Matrix4f(draw.basis).setTranslation(0, 0, 0).scale(1/16f);
            wall.solve((i, dst) -> dst.set(model.skin(i)), model.world, toWall, nest, kind -> false);
        }
        for (var raw : model.data.getAsJsonArray("parts")) {
            var part = raw.getAsJsonObject();
            if (!OfflineModel.group(part.get("name").getAsString()).equals(group)) continue;
            int start = part.get("start").getAsInt(), end = start + part.get("count").getAsInt();
            for (int i = start; i < end; i += 3)
                triangles.add(new Geometry.Triangle(point(draw, i, toWall), point(draw, i + 1, toWall), point(draw, i + 2, toWall)));
        }
        return new Geometry.Mesh(triangles, group.equals("body"));
    }

    private static void compose() {
        model.compose();
        Compose.features(model.bones, model.world, model.featureTransforms, new Matrix4f());
    }

    private static Vector3d point(Draw draw, int vertex, Matrix4f toWall) {
        Vector3f p = model.skin(vertex);
        if (toWall == null) return new Vector3d(draw.basis.transformPosition(p));
        wall.apply(vertex, p);
        toWall.transformPosition(p);
        float press = WallYield.press(nest, p.x, p.y, p.z);
        return new Vector3d(p.mul(press, 1, press).mul(16).add(draw.basis.m30(), draw.basis.m31(), draw.basis.m32()));
    }

    private static void check(Draw a, String first, Draw b, String second, List<Geometry.Region> allowed, boolean external) {
        double outgoing = depth(a, first, b, second, allowed, -1, external);
        double incoming = depth(a, first, b, second, allowed, 1, external);
        for (float w : new float[]{0, .125f, .25f, .5f, .75f, .83618397f, .875f}) {
            double drawn = depth(a, first, b, second, allowed, w, external);
            assertTrue(drawn <= Math.max(outgoing, incoming) + 1e-8,
                    () -> first + " x " + second + " at " + w + " yielded " + drawn + " vs " + outgoing + " / " + incoming);
        }
    }

    private static double depth(Draw a, String first, Draw b, String second, List<Geometry.Region> allowed,
                                float weight, boolean external) {
        return Geometry.penetration(mesh(a, first, weight, external), mesh(b, second, weight, external), allowed).depth();
    }

    @Test void braidAndBody() {
        var basis = new Matrix4f().translation(-7344, 1026.5f, 4558.10546875f).scale(-1.2f, 1.2f, -1.2f);
        var draw = new Draw("idle", 0, "waddle", .25099832f, basis, true);
        var root = new Geometry.Region(new Vector3d(-7351.892578125, 1036.062744140625, 4561.861328125), 2.2486887);
        assertEquals(1.851248, depth(draw, "braid", draw, "body", List.of(root), -1, true), 1e-5);
        assertEquals(1.808798, depth(draw, "braid", draw, "body", List.of(root), 1, true), 1e-5);
        check(draw, "braid", draw, "body", List.of(root), true);
        var clear = new Draw(draw.outgoing, draw.last, draw.incoming, draw.now, basis, false);
        check(clear, "braid", clear, "body", List.of(root), false);
    }

    private static ContactBounds bounds(Draw draw, float weight, boolean external) {
        var result = new ContactBounds();
        result.clear();
        var local = new Draw(draw.outgoing, draw.last, draw.incoming, draw.now, new Matrix4f(), false);
        String[] groups = {"body", "hair", "hat"};
        var p = new Vector3f();
        for (int i = 0; i < groups.length; i++) {
            for (var t : mesh(local, groups[i], weight, external).triangles) {
                result.add(i, t.a().get(p));
                result.add(i, t.b().get(p));
                result.add(i, t.c().get(p));
            }
        }
        result.place(draw.basis);
        return result;
    }

    @Test void siblingsKeepTheirFittedHatAndHair() {
        var a = new Draw("run", .011998773f, "waddle", 0, basis(.00177438324f, -.17999126017f, -7405.953125f, 4568.10546875f), false);
        var b = new Draw("run", .111998916f, "waddle", .027998542f, basis(-.04751610011f, -.17361515760f, -7406.1953125f, 4565.31640625f), false);
        // Whichever sibling draws second must detect the contact with the first one's fitted pose.
        assertFalse(bounds(a, .5f, false).separate(bounds(b, .5f, true)));
        assertFalse(bounds(b, .5f, false).separate(bounds(a, .5f, true)));
        assertEquals(.33321586, depth(a, "hat", b, "hair", List.of(), 1, true), 1e-5);
        check(a, "hat", b, "hair", List.of(), true);
        check(b, "hat", a, "hair", List.of(), true);
        check(a, "hat", b, "body", List.of(), true);
        check(b, "hat", a, "body", List.of(), true);
    }

    @Test void clearLocomotionKeepsMovingTheVisibleBody() {
        var draw = new Draw("run", .011998773f, "waddle", 0, new Matrix4f(), false);
        var neighbor = new Draw("run", .111998916f, "waddle", .027998542f, new Matrix4f().translation(30, 0, 0), false);
        assertTrue(bounds(draw, .5f, false).separate(bounds(neighbor, .5f, true)));
        var body = mesh(draw, "body", 1, false);
        var mixed = mesh(draw, "body", .5f, false);
        assertTrue(body.triangles.getFirst().a().distance(mixed.triangles.getFirst().a()) > .01);
    }

    private static Matrix4f basis(float xx, float xz, float x, float z) {
        return new Matrix4f(xx, 0, xz, 0, 0, .180000007f, 0, 0, -xz, 0, xx, 0, x, 1024, z, 1);
    }

    @Test void replayEveryClipChangeThatBlendedInTheTwoAdditionalCaptures() throws Exception {
        // Replay the clip layer alone. The nest and reciprocal partner tests above add fitted contacts.
        var resource = PoseBridgePenetrationTest.class.getResource("/pose-bridge/locomotion.json");
        try (var reader = Files.newBufferedReader(Path.of(resource.toURI()))) {
            for (var raw : JsonParser.parseReader(reader).getAsJsonArray()) {
                var row = raw.getAsJsonObject();
                var matrix = new float[16];
                for (int i = 0; i < matrix.length; i++) matrix[i] = row.getAsJsonArray("basis").get(i).getAsFloat();
                var draw = new Draw(row.get("outgoing").getAsString(), row.get("last").getAsFloat(),
                        row.get("incoming").getAsString(), row.get("now").getAsFloat(), new Matrix4f().set(matrix), false);
                var c = row.getAsJsonArray("center");
                var root = new Geometry.Region(new Vector3d(c.get(0).getAsDouble(), c.get(1).getAsDouble(), c.get(2).getAsDouble()),
                        row.get("radius").getAsDouble());
                check(draw, "braid", draw, "body", List.of(root), false);
            }
        }
    }
}
