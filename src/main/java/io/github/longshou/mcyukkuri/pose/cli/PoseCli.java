package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.joml.Vector3f;

/** One request -> one deterministic JSON result. --serve repeats that protocol on stdin/stdout. */
public final class PoseCli {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private PoseCli() {}
    public static JsonObject run(Path assets, JsonObject request) throws IOException {
        var metrics = new ArrayList<String>(); for (var m : PoseChecks.need(request, "metric").getAsJsonArray()) metrics.add(m.getAsString());
        var frames = new ArrayList<JsonObject>(); var out = new JsonObject();
        if (request.has("frames")) {
            for (var f : request.getAsJsonArray("frames")) {
                var frame = f.getAsJsonObject();
                if (PoseChecks.s(frame, "k").equals("pose")) frames.add(frame);
            }
            if (frames.isEmpty()) throw new IllegalArgumentException("missing context: pose frames");
            out.addProperty("source", "frames");
        } else {
            var model = new OfflineModel(assets, PoseChecks.s(request, "model")); String clip = PoseChecks.s(request, "clip");
            var times = times(PoseChecks.s(request, "t"));
            var partners = new LinkedHashMap<String, JsonObject>();
            if (request.has("partner")) for (var raw : request.getAsJsonArray("partner")) {
                String[] named = raw.getAsString().split("=", 2), placed = named[1].split("@", 2), kind = placed[0].split(":", 2);
                var partner = new OfflineModel(assets, kind[0]); partner.sample("idle", 0, null);
                float scale = partnerScale(assets, kind[1]);
                partners.put(named[0], partner.meshes(scale, new Vector3f(csv(placed[1]))));
            }
            for (int i = 0; i < times.size(); i++) {
                var frame = model.frame(clip, times.get(i), i, request.getAsJsonArray("care"));
                double previous = i == 0 ? -1e-8 : times.get(i - 1);
                var crossed = frame.getAsJsonArray("markers");
                var c = model.clip(clip); double duration = c.get("duration").getAsDouble();
                for (var marker : frame.getAsJsonObject("clip_markers").entrySet()) {
                    double at = marker.getValue().getAsDouble();
                    if (c.get("loop").getAsBoolean()) at += Math.max(0, Math.floor((previous - at) / duration) + 1) * duration;
                    if (at > previous && at <= times.get(i)) crossed.add(marker.getKey());
                }
                if (request.has("water")) { var water = new JsonObject(); water.add("point", request.get("water")); frame.add("water", water); }
                if (metrics.contains("pass")) {
                    var meshes = model.meshes(1, new Vector3f()); var pairs = new JsonArray();
                    if (meshes.has("braid")) { var braid = pair("braid", "body"); braid.add("allowed", model.rootRing()); pairs.add(braid); }
                    for (var partner : partners.entrySet()) {
                        for (var mesh : partner.getValue().entrySet()) meshes.add(partner.getKey() + "." + mesh.getKey(), mesh.getValue());
                        pairs.add(pair("body", partner.getKey() + ".body"));
                        if (meshes.has("braid")) pairs.add(pair("braid", partner.getKey() + ".body"));
                        if (meshes.has("hat")) {
                            pairs.add(pair("hat", partner.getKey() + ".body"));
                            if (partner.getValue().has("hair")) pairs.add(pair("hat", partner.getKey() + ".hair"));
                        }
                    }
                    frame.add("meshes", meshes); frame.add("pairs", pairs);
                    if (c.has("contacts")) for (var contact : c.getAsJsonArray("contacts")) {
                        var declared = contact.getAsJsonObject();
                        for (var raw : pairs) { var p = raw.getAsJsonObject();
                            if (p.get("a").equals(declared.get("a")) && p.get("b").equals(declared.get("b")))
                                p.getAsJsonArray("allowed").addAll(PoseChecks.need(declared, "allowed").getAsJsonArray());
                        }
                    }
                }
                // Explicit evidence augments the contract; no water/partner/prop/server state is invented.
                if (request.has("context")) for (var e : request.getAsJsonObject("context").entrySet()) frame.add(e.getKey(), e.getValue());
                frames.add(frame);
            }
            out.addProperty("model", model.id); out.addProperty("fp", model.fingerprint); out.addProperty("clip", clip);
            if (request.has("vertices")) {
                var buffer = ByteBuffer.allocate(model.bindings.length * 3 * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < model.bindings.length; i++) { var p = model.skin(i); buffer.putFloat(p.x).putFloat(p.y).putFloat(p.z); }
                Files.write(Path.of(request.get("vertices").getAsString()), buffer.array());
                out.addProperty("vertices_frame", times.getLast()); out.addProperty("vertices_count", model.bindings.length);
            }
        }
        var results = PoseChecks.evaluate(frames, metrics); out.add("results", results);
        if (request.has("pose_out")) {
            try (var writer = Files.newBufferedWriter(Path.of(request.get("pose_out").getAsString()), StandardCharsets.UTF_8)) {
                for (var f : frames) {
                    var m = new JsonArray();
                    for (var raw : results) { var r = raw.getAsJsonObject(); if (Objects.equals(r.get("u"), f.get("u")) && Objects.equals(r.get("run"), f.get("run")) && Objects.equals(r.get("f"), f.get("f"))) m.add(r); }
                    f.add("m", m); writer.write(JSON.toJson(f)); writer.newLine();
                }
            }
        }
        return out;
    }
    private static JsonObject pair(String a, String b) {
        var p = new JsonObject(); p.addProperty("a", a); p.addProperty("b", b); p.add("allowed", new JsonArray()); return p;
    }
    private static float partnerScale(Path assets, String age) throws IOException {
        if (age.equals("adult")) return 1;
        var growth = OfflineModel.read(assets.resolve("yukkuri_presentation/family.json")).getAsJsonObject("growth");
        if (age.equals("baby")) return growth.getAsJsonObject("birth").getAsJsonObject("live").get("scale").getAsFloat();
        if (age.equals("juvenile")) { var s = growth.getAsJsonArray("stages").get(1).getAsJsonObject(); return (s.get("min_scale").getAsFloat() + s.get("max_scale").getAsFloat()) / 2; }
        throw new IllegalArgumentException("unknown partner age: " + age);
    }
    private static float[] csv(String text) {
        String[] parts = text.split(","); if (parts.length != 3) throw new IllegalArgumentException("expected x,y,z");
        float[] v = new float[3]; for (int i = 0; i < 3; i++) { v[i] = Float.parseFloat(parts[i]); if (!Float.isFinite(v[i])) throw new IllegalArgumentException("non-finite offset"); } return v;
    }
    static List<Double> times(String text) {
        String[] pieces = text.split(":"); var out = new ArrayList<Double>();
        if (pieces.length == 1) { double t = Double.parseDouble(text); if (!Double.isFinite(t) || t < 0) throw new IllegalArgumentException("invalid time"); out.add(t); return out; }
        if (pieces.length != 3) throw new IllegalArgumentException("expected start:end:step seconds");
        double start = Double.parseDouble(pieces[0]), end = Double.parseDouble(pieces[1]), step = Double.parseDouble(pieces[2]);
        if (!Double.isFinite(start + end + step) || start < 0 || end < start || step <= 0 || (end - start) / step > 100000) throw new IllegalArgumentException("invalid time range");
        for (int i = 0; start + i * step <= end + 1e-9; i++) out.add(start + i * step); return out;
    }
    private static JsonObject handle(Path assets, String input) {
        try { return run(assets, JsonParser.parseString(input).getAsJsonObject()); }
        catch (IOException | JsonParseException | IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            var out = new JsonObject(); out.addProperty("error", e.getMessage()); return out;
        }
    }
    public static void main(String[] args) throws IOException {
        Path assets = Path.of(args[0]); boolean serve = args.length > 1 && args[1].equals("--serve");
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)); var output = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)) {
            if (serve) { for (String line; (line = input.readLine()) != null;) output.println(JSON.toJson(handle(assets, line))); }
            else { var text = new StringBuilder(); for (String line; (line = input.readLine()) != null;) text.append(line); var result = handle(assets, text.toString()); output.println(JSON.toJson(result)); if (result.has("error")) System.exit(2); }
        }
    }
}
