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
        if(f.has("geometry_error"))throw new IllegalArgumentException(s(f,"geometry_error"));
        var pairs = need(f, "pairs").getAsJsonArray();
        if(pairs.isEmpty()&&f.has("pairs_complete")&&f.get("pairs_complete").getAsBoolean()){skip(r,"no applicable pair in observed neighborhood");return;}
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
        if(f.has("gap_applicable")&&!f.get("gap_applicable").getAsBoolean()){skip(r,"no lower-surface rider contact");return;}
        var sup = obj(f, "sup"); var skin = new ArrayList<Vector3d>();
        for (var p : need(f, "skin_points").getAsJsonArray()) skin.add(vector(p));
        if (skin.isEmpty()) throw new IllegalArgumentException("missing context: rider skin points");
        var direction = s(sup, "kind").equals("ground") ? new Vector3d(0, 1, 0) : up(need(sup, "frame"));
        double g = Geometry.gap(skin, mesh(need(sup, "mesh")), direction) / 16;
        gapBand(r,g);
    }
    /** Replay the measured signed clearance without embedding captured meshes in fixtures. */
    public static JsonObject gapReading(JsonObject frame, double signedBlocks) {
        if (!Double.isFinite(signedBlocks)) throw new IllegalArgumentException("non-finite gap");
        var r=result(frame,"gap");gapBand(r,signedBlocks);return r;
    }
    private static void gapBand(JsonObject r,double g) {
        bounded(r, Math.abs(g), .03, .1, "body contact vs support surface"); r.addProperty("signed", g);
    }
    private static void wet(JsonObject f, JsonObject r) {
        var params = obj(f, "parameters");
        if (!params.has("stroke")) {
            var markers = obj(f, "clip_markers");
            if (markers.has("power_start") && markers.has("power_end")) {
                double t = n(f, "ct");
                if (t >= n(markers, "power_start") && t <= n(markers, "power_end"))
                    throw new IllegalArgumentException("missing context: power stroke depth and declared blade tip");
                skip(r, "outside power_start..power_end");
            } else skip(r, "clip has no stroke parameters");
            return;
        }
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
        Vector3d anchor;
        if (item.has("handover")) {
            var path = obj(item, "handover");
            double share = n(path, "share");
            if (share < 0 || share > 1) throw new IllegalArgumentException("handover share outside [0,1]");
            if (!path.has("from") || !path.has("to")) throw new IllegalArgumentException("missing context: handover endpoints");
            // The renderer uses the available endpoint when a real world grip is absent.
            anchor = path.get("from").isJsonNull() ? vector(need(path, "to"))
                    : path.get("to").isJsonNull() ? vector(need(path, "from"))
                    : vector(path.get("from")).lerp(vector(path.get("to")), share);
        } else anchor = vector(need(item, "anchor")); // Stationary props and captures before the handover contract.
        bounded(r, grip.distance(anchor) / 16, .02, .08,
                item.has("handover") ? "drawn grip vs blended holder anchors" : "drawn grip vs holder anchor");
    }
    private static double time(JsonObject f) { return n(f, "t") + n(f, "pt"); }
    private static boolean contiguous(JsonObject a, JsonObject b) { double dt = time(b) - time(a); return dt > 0 && dt <= 1.000001; }
    private static double poseTime(JsonObject f){return f.has("render_seconds")?n(f,"render_seconds")*20:time(f);}
    private static double yawError(JsonObject f) {
        var server = obj(f, "server");
        if (n(server, "t") != n(f, "t")) throw new IllegalArgumentException("server by is not from the same tick");
        double drawn = n(f, f.has("body_yaw") ? "body_yaw" : "yaw");
        double error = Math.abs(Math.IEEEremainder(drawn - n(server, "by"), 360));
        if (!server.has("window")) return error;
        // Vanilla ClientPacketListener interpolates movement/head packets over 3 ticks;
        // LivingEntityRenderer additionally blends yBodyRotO -> yBodyRot. This is an
        // envelope of those observed headings, not an invented packet arrival time.
        var window = server.getAsJsonArray("window");
        if (window.size() != 5) throw new IllegalArgumentException("yaw window needs five dense server ticks");
        for (int i = 0; i < window.size(); i++) {
            var sample = window.get(i).getAsJsonObject();
            if (n(sample,"t") != n(f,"t") - 4 + i) throw new IllegalArgumentException("noncontiguous yaw window");
            double a = n(sample,"by");
            if (i == 4 && a != n(server,"by")) throw new IllegalArgumentException("yaw window endpoint mismatch");
        }
        for (int i = 1; i < window.size(); i++) {
            double a = n(window.get(i).getAsJsonObject(),"by");
            double b = n(window.get(i-1).getAsJsonObject(),"by");
            double arc = Math.IEEEremainder(a-b,360), offset = Math.IEEEremainder(drawn-b,360);
            if (offset >= Math.min(0,arc) && offset <= Math.max(0,arc)) return 0;
            error = Math.min(error, Math.min(Math.abs(offset), Math.abs(Math.IEEEremainder(drawn-a,360))));
        }
        return error;
    }
    private static void yaw(List<JsonObject> fs, int i, JsonObject r) {
        var f = fs.get(i);
        if (!s(f, "yawsrc").equals("body")) { skip(r, "yaw source is not body"); return; }
        s(f, "run"); s(f, "u");
        double error = yawError(f); bounded(r, error, 10, 30,
                obj(f,"server").has("window") ? "drawn yaw vs vanilla interpolation window" : "drawn yaw vs same-tick server by");
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
        if (i == 0) throw new IllegalArgumentException("missing context: contiguous previous pose");
        var before=fs.get(i-1);
        boolean renderedNeighbors=f.has("src")&&s(f,"src").equals("client")&&before.has("f")&&f.has("f")&&n(f,"f")==n(before,"f")+1;
        if(poseTime(f)==poseTime(before)&&obj(f,"bones").equals(obj(before,"bones"))){skip(r,"unchanged pose at paused game time");return;}
        if(!(poseTime(f)>poseTime(before)&&(renderedNeighbors||contiguous(before,f))))throw new IllegalArgumentException("missing context: contiguous previous pose");
        var prev = fs.get(i - 1); var bones = obj(f, "bones"); var old = obj(prev, "bones");
        if (bones.isEmpty() || !bones.keySet().equals(old.keySet())) throw new IllegalArgumentException("missing/mismatched bone matrices");
        // Skeletal motion is measured relative to the rendered entity/holder frame.
        // Walking turns and a carrier's rotation move every bone together.
        if (f.has("pose_basis") != prev.has("pose_basis")) throw new IllegalArgumentException("missing context: paired pose_basis");
        var basis = inverseBasis(f);
        var oldBasis = inverseBasis(prev);
        double rate = 0; String where = "bones";
        JsonObject authored = f.has("authored_loop") ? obj(f,"authored_loop") : null;
        if (authored != null && (!s(f,"clip").equals(s(prev,"clip")) || n(f,"ct") >= n(prev,"ct")
                || !s(authored,"fp").equals(s(f,"fp")) || !s(f,"fp").equals(s(prev,"fp"))
                || !s(authored,"clip").equals(s(f,"clip")) || n(authored,"duration") <= 0
                || n(prev,"ct") < n(authored,"duration")/2 || n(prev,"ct") >= n(authored,"duration")
                || n(f,"ct") < 0 || n(f,"ct") >= n(authored,"duration")/2))
            throw new IllegalArgumentException("authored loop does not match captured pair");
        for (var e : bones.entrySet()) {
            var a = rotation(basis.mul(matrix(e.getValue()), new Matrix4d()));
            var b = rotation(oldBasis.mul(matrix(old.get(e.getKey())), new Matrix4d()));
            var delta = new Quaterniond(b).invert().mul(a);
            if (authored != null) {
                String bone=e.getKey();
                var start=rotation(matrix(need(obj(authored,"start"),bone)));
                var end=rotation(matrix(need(obj(authored,"end"),bone)));
                // 0.01 degree covers float composition and the 1 microsecond end probe;
                // it is numerical seam equality, not an animation-speed allowance.
                if (turn(new Quaterniond(start).invert().mul(end)) <= .01) {
                    var from=rotation(matrix(need(obj(authored,"before"),bone)));
                    var to=rotation(matrix(need(obj(authored,"after"),bone)));
                    var expected=new Quaterniond(from).invert().mul(to);
                    delta.premul(expected.invert());
                }
            }
            double angle = turn(delta);
            double v = angle * 20 / (poseTime(f) - poseTime(prev));
            if (v > rate) { rate = v; where = e.getKey(); }
        }
        // Provisional bands: half turn per second / full turn per half second. No calibration or hard gate claimed.
        bounded(r, rate, 180, 720, where);
        r.getAsJsonObject("band").addProperty("rate_calibrated", false);
        if(authored!=null)r.getAsJsonObject("band").addProperty("authored_loop_residual",true);
        int first = i, end = i + 1;
        while (first > 0 && s(fs.get(first - 1), "clip").equals(s(f, "clip")) && contiguous(fs.get(first - 1), fs.get(first))) first--;
        while (end < fs.size() && s(fs.get(end), "clip").equals(s(f, "clip")) && contiguous(fs.get(end - 1), fs.get(end))) end++;
        if (first > 0 && end < fs.size() && contiguous(fs.get(first - 1), fs.get(first)) && contiguous(fs.get(end - 1), fs.get(end))
                && time(fs.get(end)) - time(fs.get(first)) < 3) {
            boolean transitionSeen = false;
            for (int j = first; j <= end; j++) transitionSeen |= need(fs.get(j), "transition").getAsBoolean();
            if (!transitionSeen) { r.addProperty("verdict", "fail"); r.addProperty("where", "clip flicker < 3 ticks"); r.addProperty("flicker_ticks", time(fs.get(end)) - time(fs.get(first))); }
        }
    }
    private static Matrix4d inverseBasis(JsonObject f) {
        if (!f.has("pose_basis")) return new Matrix4d();
        var basis = matrix(f.get("pose_basis"));
        if (basis.determinant3x3() == 0) throw new IllegalArgumentException("singular pose basis");
        return basis.invert();
    }
    private static double turn(Quaterniond q) {
        return Math.toDegrees(2*Math.acos(Math.clamp(Math.abs(q.normalize().w),0,1)));
    }
    private static Quaterniond rotation(Matrix4d m) {
        // Authored hidden puff bones use a nonzero 1e-6 scale. Scale does not invalidate their rotation.
        if (m.determinant3x3() == 0) throw new IllegalArgumentException("singular bone matrix");
        return m.getUnnormalizedRotation(new Quaterniond()).normalize();
    }
}
