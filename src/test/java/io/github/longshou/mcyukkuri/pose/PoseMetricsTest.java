package io.github.longshou.mcyukkuri.pose;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import io.github.longshou.mcyukkuri.pose.cli.PoseCli;
import java.nio.file.Path;
import java.util.*;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** F evidence, not reconstructed historical captures. Catalog rows 29–36 each have a broken and a clean control. */
class PoseMetricsTest {
    static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    static JsonArray array(double... values) { var a = new JsonArray(); for (double v : values) a.add(v); return a; }
    static JsonArray matrix(Matrix4d m) { return array(m.get(new double[16])); }
    static JsonObject frame(double t) {
        var f = json("""
                {"k":"pose","run":"F","u":"actor","win":"fixture","pt":0,"clip":"idle","ct":0.5,
                 "contact":"UPRIGHT","parameters":{},"yawsrc":"body","yaw":0,"transition":false,
                 "sup":{"kind":"ground"},"clip_markers":{"contact":0.2,"stroke":0.8}}
                """);
        f.addProperty("t", t); f.addProperty("f", t); f.add("root", matrix(new Matrix4d()));
        f.add("bones", json("{}")); f.getAsJsonObject("bones").add("root", matrix(new Matrix4d()));
        f.add("server", json("{\"by\":0}")); f.getAsJsonObject("server").addProperty("t", t); return f;
    }
    static JsonObject result(String metric, JsonObject... frames) {
        var out = PoseChecks.evaluate(List.of(frames), List.of(metric)); return out.get(out.size() - 1).getAsJsonObject();
    }
    static void verdict(String wanted, String metric, JsonObject... fs) {
        var r = result(metric, fs); assertEquals(wanted, r.get("verdict").getAsString(), r.toString());
        assertTrue(r.has("unit") && r.has("band") && r.has("value"));
    }
    static JsonObject box(double x, double y, double z, double size) {
        double[][] p = {{x,y,z},{x+size,y,z},{x+size,y+size,z},{x,y+size,z},{x,y,z+size},{x+size,y,z+size},{x+size,y+size,z+size},{x,y+size,z+size}};
        int[][] faces = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},{3,7,6},{3,6,2},{0,4,7},{0,7,3},{1,2,6},{1,6,5}};
        var m = json("{\"closed\":true,\"triangles\":[]}");
        for (int[] face : faces) { var t = new JsonArray(); for (int v : face) t.add(array(p[v])); m.getAsJsonArray("triangles").add(t); } return m;
    }
    static JsonObject surface() { return json("{\"closed\":false,\"triangles\":[[[-10,0,-10],[10,0,-10],[0,0,10]]]} "); }
    static JsonObject pair(String a, JsonObject ma, String b, JsonObject mb) {
        var f = frame(0); var meshes = new JsonObject(); meshes.add(a, ma); meshes.add(b, mb); f.add("meshes", meshes);
        var pairs = new JsonArray(); var p = json("{\"allowed\":[]}"); p.addProperty("a", a); p.addProperty("b", b); pairs.add(p); f.add("pairs", pairs); return f;
    }
    @Test void row29BraidAndChildPenetration() {
        var bad = pair("braid", box(.5,.5,.5,1), "own_body", box(0,0,0,4)); verdict("fail", "pass", bad);
        var child = pair("child", box(.5,.5,.5,1), "carrier", box(0,0,0,4)); verdict("fail", "pass", child);
        var clean = pair("braid", box(5,0,0,1), "own_body", box(0,0,0,4)); verdict("pass", "pass", clean);
        var cleanChild = pair("child", box(0,4,0,1), "carrier", box(0,0,0,4)); verdict("pass", "pass", cleanChild);
        bad.getAsJsonArray("pairs").get(0).getAsJsonObject().getAsJsonArray("allowed").add(json("{\"center\":[1,1,1],\"radius\":1,\"why\":\"braid root ring\"}"));
        verdict("pass", "pass", bad);
    }
    @Test void row30BraidAndDeclaredBladeWet() {
        for (String tip : List.of("braid end", "stick blade")) {
            var f = frame(0); f.getAsJsonObject("parameters").add("stroke", json("{\"depth\":2}")); f.add("water", json("{\"point\":0.5}"));
            f.add("wet_tip", array(0,9,0)); verdict("fail", "wet", f);
            f.add("wet_tip", array(0,7,0)); verdict("pass", "wet", f);
            f.add("wet_tip", array(0,7.5,0)); verdict("suspect", "wet", f);
            f.remove("wet_tip"); verdict("error", "wet", f);
            f.add("wet_tip", array(0,7,0)); f.remove("water"); verdict("error", "wet", f);
            System.out.println("F row30 " + tip + ": fail / pass / missing-context error");
        }
    }
    @Test void row31TiltRelativeToCarrierHead() {
        var f = frame(0); var carrier = new Matrix4d().rotateY(1.4).rotateZ(.4);
        f.getAsJsonObject("sup").addProperty("kind", "carrier"); f.getAsJsonObject("sup").add("frame", matrix(carrier));
        f.add("root", matrix(new Matrix4d(carrier).rotateX(Math.toRadians(45)))); verdict("fail", "tilt", f);
        f.add("root", matrix(new Matrix4d(carrier).rotateX(Math.toRadians(12)))); verdict("pass", "tilt", f);
        f.getAsJsonObject("sup").remove("frame"); verdict("error", "tilt", f);
    }
    @Test void tiltYawInvariantCombinedBudgetAndDataException() {
        var f = frame(0); f.add("root", matrix(new Matrix4d().rotateY(2.7).rotateX(Math.toRadians(12)))); verdict("pass", "tilt", f);
        f.add("root", matrix(new Matrix4d().rotateZ(Math.toRadians(12)).rotateX(Math.toRadians(12)))); verdict("fail", "tilt", f);
        f.add("parameters", json("{\"tilt_max\":38,\"tilt_why\":\"authored toilet contact\"}")); verdict("pass", "tilt", f);
        f.getAsJsonObject("parameters").remove("tilt_why"); verdict("error", "tilt", f);
        f.addProperty("contact", "SUPINE"); verdict("skipped", "tilt", f);
    }
    @Test void row32FloatingRiderGap() {
        var f = frame(0); f.getAsJsonObject("sup").add("mesh", surface());
        f.add("skin_points", JsonParser.parseString("[[0,2,0],[0,3,0]]")); verdict("fail", "gap", f);
        f.add("skin_points", JsonParser.parseString("[[0,0.48,0],[0,3,0]]")); verdict("pass", "gap", f);
        f.add("skin_points", JsonParser.parseString("[[0,-2,0]]")); verdict("fail", "gap", f);
        f.add("skin_points", JsonParser.parseString("[[30,0,0]]")); verdict("error", "gap", f);
        f.getAsJsonObject("sup").remove("mesh"); verdict("error", "gap", f);
    }
    @Test void row33SustainedBodyYawLagAndWrap() {
        var fs = new ArrayList<JsonObject>();
        for (int i = 0; i <= 5; i++) { var f = frame(i); f.addProperty("yaw", 50); fs.add(f); }
        verdict("fail", "yaw", fs.toArray(JsonObject[]::new));
        verdict("suspect", "yaw", fs.subList(0,5).toArray(JsonObject[]::new));
        for (var f : fs) { f.addProperty("yaw", -179); f.getAsJsonObject("server").addProperty("by", 179); }
        verdict("pass", "yaw", fs.toArray(JsonObject[]::new));
        var f = frame(0); f.getAsJsonObject("server").addProperty("t", 1); verdict("error", "yaw", f);
        f.addProperty("yawsrc", "hull"); verdict("skipped", "yaw", f);
    }
    @Test void row34ClipFlickerAndAuthoredTransition() {
        var a = frame(0); var b = frame(1); var c = frame(2); b.addProperty("clip", "raise_stick");
        var rows = PoseChecks.evaluate(List.of(a,b,c), List.of("posejump")); assertEquals("fail", rows.get(1).getAsJsonObject().get("verdict").getAsString());
        b.addProperty("transition", true); rows = PoseChecks.evaluate(List.of(a,b,c), List.of("posejump"));
        assertEquals("skipped", rows.get(1).getAsJsonObject().get("verdict").getAsString()); verdict("pass", "posejump", b,c);
        b.addProperty("transition", false); b.addProperty("clip", "idle"); verdict("pass", "posejump", a,b,c);
    }
    @Test void posejumpBrokenRateAndMissingPrevious() {
        var a = frame(0); var b = frame(1); b.getAsJsonObject("bones").add("root", matrix(new Matrix4d().rotateX(Math.PI/2)));
        verdict("fail", "posejump", a,b); verdict("error", "posejump", a);
        b.addProperty("t", 4); verdict("error", "posejump", a,b);
        b.addProperty("u", "another actor"); verdict("error", "posejump", a,b);
    }
    @Test void row35HeldSwordAnchorAndMissingDraw() {
        var f = frame(0); var item = json("{\"held\":true,\"id\":\"sword\",\"place\":\"mouth\",\"grip\":[0,0,0],\"anchor\":[0,0,0]}"); f.add("item", item);
        item.add("drawn", matrix(new Matrix4d().translate(0,-2,0))); verdict("fail", "held", f);
        item.add("drawn", matrix(new Matrix4d().translate(0,.32,0))); verdict("pass", "held", f);
        item.add("drawn", JsonNull.INSTANCE); verdict("fail", "held", f);
        item.remove("drawn"); verdict("error", "held", f);
    }
    @Test void row36HatCutsHairAndHollowControl() {
        var bad = pair("hat", surface(), "partner_hair", box(-1,-1,-1,2)); verdict("fail", "pass", bad);
        var clean = pair("hat", surface(), "partner_hair", box(-1,1,-1,2)); verdict("pass", "pass", clean);
        // A real hollow/open surface: no invented filled hull above this floor.
        var hollow = box(0,0,0,8); hollow.addProperty("closed", false);
        // Remove the top two triangles, leaving an open cup.
        hollow.getAsJsonArray("triangles").remove(7); hollow.getAsJsonArray("triangles").remove(6);
        verdict("pass", "pass", pair("hat", hollow, "rider", box(2,2,2,2)));
        bad.getAsJsonObject("meshes").remove("partner_hair"); verdict("error", "pass", bad);
    }
    @Test void crossingsWithoutInteriorVerticesAndFrozenDepth() {
        var cross = json("{\"closed\":false,\"triangles\":[[[0,-2,0],[0,2,0],[20,2,0]]]} ");
        verdict("fail", "pass", pair("sheet", surface(), "cross", cross));
        var shallow = json("{\"closed\":false,\"triangles\":[[[0,-0.05,0],[0,0.05,0],[1,0.05,0]]]} ");
        verdict("pass", "pass", pair("sheet", surface(), "cross", shallow));
    }
    @Test void allMetricsRejectMissingEvidenceAndCliIsDeterministic() throws Exception {
        for (String metric : PoseChecks.METRICS) {
            var f = frame(0);
            switch (metric) {
                case "tilt" -> f.remove("root");
                case "wet" -> f.getAsJsonObject("parameters").add("stroke", json("{\"depth\":2}"));
                case "yaw" -> f.remove("server");
            }
            verdict("error", metric, f);
        }
        var request = json("{\"metric\":[\"tilt\"],\"frames\":[]}"); request.getAsJsonArray("frames").add(frame(0));
        var one = PoseCli.run(Path.of("unused"), request); var two = PoseCli.run(Path.of("unused"), request);
        assertEquals(one.toString(), two.toString()); assertEquals("deg", one.getAsJsonArray("results").get(0).getAsJsonObject().get("unit").getAsString());
        var floats = new JsonArray(); for (float v : new org.joml.Matrix4f().rotateX(.4f).get(new float[16])) floats.add(v);
        var f = frame(0); f.add("root", floats);
        assertEquals(result("tilt", f).toString(), result("tilt", json(f.toString())).toString(), "Float-valued capture must replay exactly");
    }
}
