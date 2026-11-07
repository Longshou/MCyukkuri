package io.github.longshou.mcyukkuri.pose.cli;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import io.github.longshou.mcyukkuri.pose.*;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Authored offline pose, using the same splice, sampling, composition, skin and care solver as the client. */
final class OfflineModel implements Overlays.Contact {
    final JsonObject data;
    final String id, fingerprint;
    final Rig.Bone[] bones;
    final float[][] pose, defaults;
    final Matrix4f[] world;
    final Compose compose;
    final float[] vertices;
    final Skin.Binding[] bindings;
    final int[] features;
    final Map<Integer, Matrix4f> featureTransforms = new LinkedHashMap<>();
    final int root;
    final Vector3f mouth, scratch = new Vector3f();
    final List<Integer> floor = new ArrayList<>();

    static JsonObject read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
    }
    OfflineModel(Path assets, String id) throws IOException {
        if (!id.matches("[a-z0-9_]+")) throw new IllegalArgumentException("invalid model id");
        this.id = id;
        byte[] base = Files.readAllBytes(assets.resolve("yukkuri/" + id + ".json"));
        var hash = ModelFingerprint.of(base);
        data = JsonParser.parseString(new String(base, StandardCharsets.UTF_8)).getAsJsonObject();
        var chain = new StringBuilder(hash.lf());
        for (String file : List.of("placement_fit", "motion_corrections", "house_ecology", "toilet_contact", "face_library")) {
            var source = read(assets.resolve("yukkuri_presentation/" + file + ".json"));
            var revision = source.getAsJsonObject("models").getAsJsonObject(id);
            if (revision == null) continue; // A model may have no revision in a resource.
            boolean applied = file.equals("placement_fit") ? Splice.fit(data, revision, hash::matches) : Splice.revision(data, revision);
            if (!applied) throw new IllegalArgumentException("incompatible " + file + " for " + id);
            chain.append('\n').append(file).append('\n').append(revision);
        }
        fingerprint = ModelFingerprint.of(chain.toString().getBytes(StandardCharsets.UTF_8)).lf();
        bones = Rig.bones(data.getAsJsonArray("bones")); root = bone("root");
        pose = Rig.values(bones.length); defaults = Rig.values(bones.length); world = Rig.matrices(bones.length); compose = new Compose(bones);
        vertices = Rig.floats(data.getAsJsonArray("vertices"));
        var weights = data.getAsJsonArray("weights"); bindings = new Skin.Binding[weights.size()]; features = new int[weights.size()]; Arrays.fill(features, -1);
        for (int i = 0; i < bindings.length; i++) { var w = weights.get(i).getAsJsonArray(); bindings[i] = new Skin.Binding(w.get(0).getAsInt(), w.get(1).getAsInt(), w.get(2).getAsFloat()); }
        for (var raw : data.getAsJsonArray("parts")) {
            var p = raw.getAsJsonObject(); int start = p.get("start").getAsInt(), count = p.get("count").getAsInt();
            if (group(p.get("name").getAsString()).equals("body")) for (int i = start; i < start + count; i++) floor.add(i);
            if (p.has("feature_bone")) { int b = p.get("feature_bone").getAsInt(); Arrays.fill(features, start, start + count, b); featureTransforms.put(b, new Matrix4f()); }
        }
        var living = read(assets.resolve("yukkuri_presentation/living.json")).getAsJsonObject("variants");
        var variant = living.getAsJsonObject(id);
        if (variant == null) variant = read(assets.resolve("yukkuri_presentation/subspecies.json")).getAsJsonObject(id);
        mouth = variant == null ? null : new Vector3f(Rig.floats(variant.getAsJsonArray("mouth")));
        Clips.read(data.getAsJsonObject("clips").getAsJsonObject("idle"), bones.length).sample(0, defaults);
    }
    private int bone(String name) { for (int i = 0; i < bones.length; i++) if (bones[i].name.equals(name)) return i; throw new IllegalArgumentException("missing bone: " + name); }
    JsonObject clip(String name) {
        var overrides = data.getAsJsonObject("behavior_clips");
        return overrides != null && overrides.has(name) ? overrides.getAsJsonObject(name) : PoseChecks.obj(data.getAsJsonObject("clips"), name);
    }
    void sample(String name, float t, JsonArray care) {
        for (int i = 0; i < pose.length; i++) System.arraycopy(defaults[i], 0, pose[i], 0, 9);
        Clips.read(clip(name), bones.length).sample(t, pose); compose(); updateFeatures();
        if (care != null) {
            var target = Rig.floats(care);
            if (target.length != 3 || mouth == null) throw new IllegalArgumentException("care requires xyz and declared model mouth");
            Overlays.care(this, pose, world, root, false, target[0], target[1], target[2], 1); updateFeatures();
        }
    }
    public void compose() { compose.world(pose, world, -1, 1, null, null); }
    private void updateFeatures() { featureTransforms.forEach((b, m) -> Compose.feature(bones, world, b, m, new Matrix4f())); }
    Vector3f skin(int index) { var p = new Vector3f(); Skin.base(index, p, scratch, vertices, bindings, world, null, 0, null, features, featureTransforms); return p; }
    public float minimumY() { float min = Float.POSITIVE_INFINITY; for (int i : floor) min = Math.min(min, skin(i).y); return min; }
    public Vector3f mouth() { return world[root].transformPosition(new Vector3f(mouth)); }
    // Preserve the exact float value through JSON -> double replay, not Float.toString's shorter decimal.
    static JsonArray array(float[] v) { var a = new JsonArray(); for (float x : v) a.add((double) x); return a; }
    static JsonArray matrix(Matrix4f m) { return array(m.get(new float[16])); }
    static String group(String name) {
        if (name.startsWith("braid_lock")) return "braid";
        if (name.startsWith("soft_head_slice_") || List.of("rounded_body_surface", "house_body_surface", "predator_body_surface", "deficient_body_surface").contains(name)) return "body";
        if (name.contains("hair") || name.startsWith("bang") || name.startsWith("back_lock")) return "hair";
        if (name.startsWith("hat_") || name.startsWith("brim_") || name.startsWith("bow_")) return "hat";
        return "other";
    }
    JsonObject meshes(float scale, Vector3f offset) {
        var meshes = new JsonObject();
        for (var raw : data.getAsJsonArray("parts")) {
            var part = raw.getAsJsonObject(); String group = group(part.get("name").getAsString());
            if (group.equals("other")) continue;
            if (!meshes.has(group)) { var m = new JsonObject(); m.addProperty("closed", group.equals("body")); m.add("triangles", new JsonArray()); meshes.add(group, m); }
            var ts = meshes.getAsJsonObject(group).getAsJsonArray("triangles");
            int start = part.get("start").getAsInt(), end = start + part.get("count").getAsInt();
            for (int i = start; i < end; i += 3) {
                var tri = new JsonArray(); for (int j = 0; j < 3; j++) { var p = skin(i + j).mul(scale).add(offset); tri.add(array(new float[]{p.x, p.y, p.z})); } ts.add(tri);
            }
        }
        return meshes;
    }
    JsonArray rootRing() {
        var points = new ArrayList<Vector3f>();
        for (var raw : data.getAsJsonArray("parts")) {
            var p = raw.getAsJsonObject();
            if (!List.of("braid_lock_root", "braid_lock_root_outer").contains(p.get("name").getAsString())) continue;
            int start = p.get("start").getAsInt(), end = start + p.get("count").getAsInt();
            for (int i = start; i < end; i++) points.add(skin(i));
        }
        var out = new JsonArray();
        if (points.isEmpty()) return out;
        var min = new Vector3f(Float.POSITIVE_INFINITY); var max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (var p : points) { min.min(p); max.max(p); }
        var center = min.add(max).mul(.5f); float radius = 0;
        for (var p : points) radius = Math.max(radius, p.distance(center));
        var region = new JsonObject(); region.add("center", array(new float[]{center.x, center.y, center.z}));
        region.addProperty("radius", (double) radius); region.addProperty("why", "authored braid root ring bounds"); out.add(region); return out;
    }
    JsonObject frame(String clip, double seconds, int index, JsonArray care) {
        sample(clip, (float) seconds, care); var f = new JsonObject(); var c = clip(clip);
        f.addProperty("v", 1); f.addProperty("k", "pose"); f.addProperty("src", "offline"); f.addProperty("run", "offline"); f.addProperty("u", id); f.add("win", JsonNull.INSTANCE); f.addProperty("dim", "offline"); f.addProperty("seq", index);
        f.addProperty("t", Math.floor(seconds * 20)); f.addProperty("pt", seconds * 20 - Math.floor(seconds * 20)); f.addProperty("f", index);
        f.addProperty("model", id); f.addProperty("fp", fingerprint); f.addProperty("clip", clip); f.addProperty("ct", Clips.read(c, bones.length).time((float) seconds));
        f.add("clip_markers", c.has("markers") ? c.get("markers") : new JsonObject()); f.add("markers", new JsonArray()); f.add("phase", JsonNull.INSTANCE);
        f.add("parameters", c.has("parameters") ? c.get("parameters") : new JsonObject());
        f.addProperty("contact", "UPRIGHT"); f.addProperty("yawsrc", "body");
        var forward = world[root].transformDirection(new Vector3f(0, 0, 1));
        f.addProperty("yaw", Math.toDegrees(Math.atan2(forward.x, forward.z))); f.addProperty("transition", false);
        f.add("root", matrix(world[root])); var sup = new JsonObject(); sup.addProperty("kind", "ground"); sup.add("host", JsonNull.INSTANCE); sup.add("frame", matrix(new Matrix4f())); f.add("sup", sup);
        f.add("item", JsonNull.INSTANCE); f.add("water", JsonNull.INSTANCE);
        var matrices = new JsonObject(); for (int i = 0; i < bones.length; i++) matrices.add(bones[i].name, matrix(world[i])); f.add("bones", matrices);
        var skin = new JsonObject(); skin.addProperty("puff", 0); skin.addProperty("belly", 0); skin.addProperty("contraction", 0);
        skin.add("expression", JsonNull.INSTANCE); skin.add("wound", JsonNull.INSTANCE); f.add("skin", skin);
        return f;
    }
}
