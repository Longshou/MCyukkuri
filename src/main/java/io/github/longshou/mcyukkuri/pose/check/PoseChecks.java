package io.github.longshou.mcyukkuri.pose.check;

import com.google.gson.*;
import java.util.*;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Read-only, deterministic checks shared by capture and the CLI. JSON is the external evidence boundary. */
public final class PoseChecks {
    private PoseChecks() {}
    public static final List<String> METRICS = List.of("pass", "tilt", "gap", "wet", "held", "yaw", "posejump");
    public static JsonElement need(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) throw new IllegalArgumentException("missing context: " + key);
        return o.get(key);
    }
    public static double number(JsonElement e) {
        // Use the JSON number representation so in-memory Float inputs replay identically from JSONL.
        double n = Double.parseDouble(e.getAsString());
        if (!Double.isFinite(n)) throw new IllegalArgumentException("non-finite input");
        return n;
    }
    public static double n(JsonObject o, String key) { return number(need(o, key)); }
    public static String s(JsonObject o, String key) { return need(o, key).getAsString(); }
    public static JsonObject obj(JsonObject o, String key) { return need(o, key).getAsJsonObject(); }
    public static Vector3d vector(JsonElement e) {
        var a = e.getAsJsonArray();
        if (a.size() != 3) throw new IllegalArgumentException("expected xyz vector");
        return new Vector3d(number(a.get(0)), number(a.get(1)), number(a.get(2)));
    }
    public static Matrix4d matrix(JsonElement e) {
        var a = e.getAsJsonArray();
        if (a.size() != 16) throw new IllegalArgumentException("expected 16 column-major matrix values");
        double[] v = new double[16]; for (int i = 0; i < 16; i++) v[i] = number(a.get(i));
        return new Matrix4d().set(v);
    }
    private static Vector3d up(JsonElement m) {
        var v = matrix(m).transformDirection(new Vector3d(0, 1, 0));
        if (v.lengthSquared() == 0) throw new IllegalArgumentException("zero support/root up");
        return v.normalize();
    }
    public static JsonArray xyz(Vector3d v) { var a = new JsonArray(); a.add(v.x); a.add(v.y); a.add(v.z); return a; }
    public static Geometry.Mesh mesh(JsonElement e) {
        var o = e.getAsJsonObject(); var triangles = new ArrayList<Geometry.Triangle>();
        for (var row : need(o, "triangles").getAsJsonArray()) {
            var t = row.getAsJsonArray();
            if (t.size() != 3) throw new IllegalArgumentException("expected three triangle corners");
            triangles.add(new Geometry.Triangle(vector(t.get(0)), vector(t.get(1)), vector(t.get(2))));
        }
        return new Geometry.Mesh(triangles, need(o, "closed").getAsBoolean());
    }
    private static String unit(String metric) {
        return switch (metric) { case "pass", "wet" -> "mu"; case "tilt", "yaw" -> "deg"; case "posejump" -> "deg/s"; default -> "block"; };
    }
    private static JsonObject result(JsonObject frame, String metric) {
        var r = new JsonObject(); r.add("t", need(frame, "t")); r.addProperty("metric", metric);
        r.add("value", JsonNull.INSTANCE); r.addProperty("unit", unit(metric));
        var band = new JsonObject();
        band.addProperty("basis", metric.equals("pass") || metric.equals("wet") ? "frozen" : "hypothesis");
        band.addProperty("report_only", true); r.add("band", band); r.addProperty("where", "");
        for (String k : List.of("u", "run", "win", "f", "pt", "ct")) if (frame.has(k)) r.add(k, frame.get(k));
        return r;
    }
    private static void bounded(JsonObject r, double value, double pass, double fail, String where) {
        r.addProperty("value", value); r.addProperty("where", where);
        r.getAsJsonObject("band").addProperty("pass_max", pass); r.getAsJsonObject("band").addProperty("suspect_max", fail);
        r.addProperty("verdict", value <= pass + 1e-8 ? "pass" : value <= fail + 1e-8 ? "suspect" : "fail");
    }
    private static void skip(JsonObject r, String why) { r.addProperty("verdict", "skipped"); r.addProperty("where", why); }
    /** Input order is retained. Temporal checks partition by run, window and actor, never across actors. */
    public static JsonArray evaluate(List<JsonObject> frames, List<String> metrics) {
        for (String m : metrics) if (!METRICS.contains(m)) throw new IllegalArgumentException("unknown metric: " + m);
        var streams = new LinkedHashMap<String, List<JsonObject>>();
        for (var f : frames) {
            String key = String.valueOf(f.get("run")) + ":" + f.get("win") + ":" + f.get("u");
            streams.computeIfAbsent(key, k -> new ArrayList<>()).add(f);
        }
        var out = new JsonArray();
        for (var stream : streams.values()) for (int i = 0; i < stream.size(); i++) for (String metric : metrics) {
            var f = stream.get(i); var r = result(f, metric);
            try {
                switch (metric) {
                    case "tilt" -> tilt(f, r);
                    case "pass" -> penetration(f, r);
                    case "gap" -> gap(f, r);
                    case "wet" -> wet(f, r);
                    case "held" -> held(f, r);
                    case "yaw" -> yaw(stream, i, r);
                    case "posejump" -> jump(stream, i, r);
                    default -> throw new AssertionError(metric);
                }
            } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
                r.addProperty("verdict", "error"); r.addProperty("where", e.getMessage());
            }
            out.add(r);
        }
        return out;
    }
    private static void tilt(JsonObject f, JsonObject r) {
        String contact = s(f, "contact");
        if (List.of("SUPINE", "FIXED", "HOOK", "ROLL").contains(contact)) { skip(r, "non-upright contact " + contact); return; }
        var sup = obj(f, "sup"); String kind = s(sup, "kind");
        if (!List.of("ground", "hull", "carrier").contains(kind)) throw new IllegalArgumentException("unknown support kind");
        var support = kind.equals("ground") ? new Vector3d(0, 1, 0) : up(need(sup, "frame"));
        var params = obj(f, "parameters"); double limit = 12;
        if (params.has("tilt_max")) { limit = n(params, "tilt_max"); s(params, "tilt_why"); }
        double angle = Math.toDegrees(Math.acos(Math.clamp(up(need(f, "root")).dot(support), -1, 1)));
        bounded(r, angle, limit, limit + 3, "root vs " + kind + " up");
        if (params.has("tilt_why")) r.getAsJsonObject("band").add("why", params.get("tilt_why"));
    }
    private static void penetration(JsonObject f, JsonObject r) {
        var pairs = need(f, "pairs").getAsJsonArray();
        if (pairs.isEmpty()) throw new IllegalArgumentException("missing context: declared collision pairs");
        var meshes = obj(f, "meshes"); var cache = new HashMap<String, Geometry.Mesh>();
        double worst = 0; String where = "declared pairs";
        for (var raw : pairs) {
            var pair = raw.getAsJsonObject(); String a = s(pair, "a"), b = s(pair, "b");
            var ma = cache.computeIfAbsent(a, k -> mesh(need(meshes, k)));
            var mb = cache.computeIfAbsent(b, k -> mesh(need(meshes, k)));
            var allowed = new ArrayList<Geometry.Region>();
            for (var reg : need(pair, "allowed").getAsJsonArray()) {
                var area = reg.getAsJsonObject(); s(area, "why"); double radius = n(area, "radius");
                if (radius < 0) throw new IllegalArgumentException("negative allowed radius");
                allowed.add(new Geometry.Region(vector(need(area, "center")), radius));
            }
            var hit = Geometry.penetration(ma, mb, allowed);
            if (hit.depth() > worst) { worst = hit.depth(); where = a + " x " + b; r.add("point", xyz(hit.point())); }
        }
        bounded(r, worst, .1, .1, where);
    }
    private static void gap(JsonObject f, JsonObject r) {
        var sup = obj(f, "sup"); var skin = new ArrayList<Vector3d>();
        for (var p : need(f, "skin_points").getAsJsonArray()) skin.add(vector(p));
        if (skin.isEmpty()) throw new IllegalArgumentException("missing context: rider skin points");
        var direction = s(sup, "kind").equals("ground") ? new Vector3d(0, 1, 0) : up(need(sup, "frame"));
        double g = Geometry.gap(skin, mesh(need(sup, "mesh")), direction) / 16;
        bounded(r, Math.abs(g), .03, .1, "lowest skin vs support triangles"); r.addProperty("signed", g);
    }
    private static void wet(JsonObject f, JsonObject r) {
        var params = obj(f, "parameters");
        if (!params.has("stroke")) { skip(r, "clip has no stroke parameters"); return; }
        var stroke = obj(params, "stroke"); var markers = obj(f, "clip_markers");
        double t = n(f, "ct"), start = n(markers, "contact"), end = n(markers, "stroke");
        if (t < start || t > end) { skip(r, "outside contact..stroke"); return; }
        double water = n(obj(f, "water"), "point") * 16;
        var tip = vector(need(f, "wet_tip")); // BraidChain end or declared prop blade tip, already posed.
        double target = n(stroke, "depth") / 2, depth = water - tip.y;
        if (!(target > 0)) throw new IllegalArgumentException("stroke.depth must be positive mu");
        r.addProperty("value", depth); r.addProperty("where", "declared blade/braid tip");
        r.getAsJsonObject("band").addProperty("pass_min", target); r.getAsJsonObject("band").addProperty("suspect_min", 0);
        r.addProperty("verdict", depth >= target ? "pass" : depth >= 0 ? "suspect" : "fail");
    }
    private static void held(JsonObject f, JsonObject r) {
        var item = obj(f, "item");
        if (!need(item, "held").getAsBoolean()) { skip(r, "no server-held item"); return; }
        s(item, "id"); s(item, "place");
        if (!item.has("drawn")) throw new IllegalArgumentException("missing context: item.drawn");
        if (item.get("drawn").isJsonNull()) { r.addProperty("verdict", "fail"); r.addProperty("where", "server-held item not drawn"); return; }
        var grip = matrix(item.get("drawn")).transformPosition(vector(need(item, "grip")));
        bounded(r, grip.distance(vector(need(item, "anchor"))) / 16, .02, .08, "drawn grip vs holder anchor");
    }
    private static double time(JsonObject f) { return n(f, "t") + n(f, "pt"); }
    private static boolean contiguous(JsonObject a, JsonObject b) { double dt = time(b) - time(a); return dt > 0 && dt <= 1.000001; }
    private static double yawError(JsonObject f) {
        var server = obj(f, "server");
        if (n(server, "t") != n(f, "t")) throw new IllegalArgumentException("server by is not from the same tick");
        return Math.abs(Math.IEEEremainder(n(f, "yaw") - n(server, "by"), 360));
    }
    private static void yaw(List<JsonObject> fs, int i, JsonObject r) {
        var f = fs.get(i);
        if (!s(f, "yawsrc").equals("body")) { skip(r, "yaw source is not body"); return; }
        s(f, "run"); s(f, "u");
        double error = yawError(f); bounded(r, error, 10, 30, "drawn yaw vs same-tick server by");
        double start = time(f);
        if (error > 30) {
            for (int j = i - 1; j >= 0; j--) {
                var prev = fs.get(j);
                if (!contiguous(prev, fs.get(j + 1)) || !s(prev, "yawsrc").equals("body") || yawError(prev) <= 30) break;
                start = time(prev);
            }
            if (time(f) - start < 5) r.addProperty("verdict", "suspect");
        }
        r.getAsJsonObject("band").addProperty("fail_duration_ticks", 5); r.addProperty("duration_ticks", time(f) - start);
    }
    private static void jump(List<JsonObject> fs, int i, JsonObject r) {
        var f = fs.get(i);
        s(f, "run"); s(f, "u");
        boolean transition = need(f, "transition").getAsBoolean();
        if (transition) { skip(r, "authored transition"); return; }
        if (i == 0 || !contiguous(fs.get(i - 1), f)) throw new IllegalArgumentException("missing context: contiguous previous pose");
        var prev = fs.get(i - 1); var bones = obj(f, "bones"); var old = obj(prev, "bones");
        if (bones.isEmpty() || !bones.keySet().equals(old.keySet())) throw new IllegalArgumentException("missing/mismatched bone matrices");
        double rate = 0; String where = "bones";
        for (var e : bones.entrySet()) {
            var a = rotation(e.getValue());
            var b = rotation(old.get(e.getKey()));
            double angle = Math.toDegrees(2 * Math.acos(Math.clamp(Math.abs(a.dot(b)), 0, 1)));
            double v = angle * 20 / (time(f) - time(prev));
            if (v > rate) { rate = v; where = e.getKey(); }
        }
        // Provisional bands: half turn per second / full turn per half second. No calibration or hard gate claimed.
        bounded(r, rate, 180, 720, where);
        r.getAsJsonObject("band").addProperty("rate_calibrated", false);
        int first = i, end = i + 1;
        while (first > 0 && s(fs.get(first - 1), "clip").equals(s(f, "clip")) && contiguous(fs.get(first - 1), fs.get(first))) first--;
        while (end < fs.size() && s(fs.get(end), "clip").equals(s(f, "clip")) && contiguous(fs.get(end - 1), fs.get(end))) end++;
        if (first > 0 && end < fs.size() && contiguous(fs.get(first - 1), fs.get(first)) && contiguous(fs.get(end - 1), fs.get(end))
                && time(fs.get(end)) - time(fs.get(first)) < 3) {
            boolean authored = false;
            for (int j = first; j <= end; j++) authored |= need(fs.get(j), "transition").getAsBoolean();
            if (!authored) { r.addProperty("verdict", "fail"); r.addProperty("where", "clip flicker < 3 ticks"); r.addProperty("flicker_ticks", time(fs.get(end)) - time(fs.get(first))); }
        }
    }
    private static Quaterniond rotation(JsonElement value) {
        var m = matrix(value);
        if (Math.abs(m.determinant3x3()) < 1e-12) throw new IllegalArgumentException("singular bone matrix");
        return m.getUnnormalizedRotation(new Quaterniond()).normalize();
    }
}
