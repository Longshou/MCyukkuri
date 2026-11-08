package io.github.longshou.mcyukkuri.client.capture;

import com.google.gson.*;
import io.github.longshou.mcyukkuri.client.YukkuriModel;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.headwear.PlacedHatView;
import io.github.longshou.mcyukkuri.pose.check.PoseChecks;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Render-thread-only observation. Never samples animation or changes an entity. */
public final class PoseTap {
    private static final boolean ENABLED = Boolean.getBoolean("mcyukkuri.capture");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Map<String, JsonObject> CURRENT = new LinkedHashMap<>();
    private static final Map<String, JsonObject> PREVIOUS = new HashMap<>();
    private static BufferedWriter writer;
    private static String run, window;
    private static long frame, sequence;

    private PoseTap() {}
    public static JsonArray matrix(Matrix4f value) {
        var a = new JsonArray();
        for (float x : value.get(new float[16])) a.add((double)x);
        return a;
    }
    public static boolean active() { return ENABLED && writer != null; }
    public static void begin(Path directory) {
        try { writer = Files.newBufferedWriter(directory.resolve("pose.jsonl")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        run = directory.getParent().getFileName().toString(); window = directory.getFileName().toString();
        frame = 0; CURRENT.clear(); PREVIOUS.clear();
    }
    public static void model(YukkuriView entity, YukkuriModel model) {
        if (active()) CURRENT.put(entity.getUUID().toString(), model.capturePose());
    }
    private static Matrix4f origin(YukkuriView entity, float partial) {
        var p = entity.getPosition(partial);
        return new Matrix4f().translation((float)p.x * 16, (float)p.y * 16, (float)p.z * 16).scale(16);
    }
    public static void body(YukkuriView entity, Matrix4f start, Matrix4f drawn, float partial) {
        if (!active()) return;
        String uuid = entity.getUUID().toString();
        var f = CURRENT.get(uuid);
        envelope(f, uuid, entity.level().getGameTime(), entity.level().dimension().location().toString(), partial);
        f.addProperty("model", entity.assetId()); f.addProperty("phase", entity.getBehaviorId());
        f.addProperty("contact", entity.contactPose());
        String yawSource = entity.vehicleYukkuri() != null ? "carry" : entity.vehicleHat() != null ? "hull"
                : List.of("IMPACT", "STRUGGLE", "EXHAUSTED").contains(entity.waterDistressPhase()) ? "distress" : "body";
        f.addProperty("yawsrc", yawSource);
        var transform = origin(entity, partial).mul(new Matrix4f(start).invert()).mul(drawn).scale(1/16f);
        var root = transform.mul(new Matrix4f(PoseChecks.matrix(f.get("root"))), new Matrix4f());
        var forward = root.transformDirection(new Vector3f(0, 0, -1));
        f.addProperty("yaw", Math.toDegrees(Math.atan2(-forward.x, forward.z)));
        f.add("root", matrix(root));
        var bones = f.getAsJsonObject("bones");
        for (String name : new ArrayList<>(bones.keySet()))
            bones.add(name, matrix(transform.mul(new Matrix4f(PoseChecks.matrix(bones.get(name))), new Matrix4f())));
        var sup = new JsonObject();
        sup.addProperty("kind", entity.vehicleYukkuri()!=null ? "carrier" : entity.vehicleHat()!=null ? "hull" : "ground");
        sup.add("host", entity.vehicleYukkuri()!=null ? new JsonPrimitive(entity.vehicleYukkuri().getUUID().toString())
                : entity.vehicleHat()!=null ? new JsonPrimitive(entity.vehicleHat().getUUID().toString()) : JsonNull.INSTANCE);
        f.add("sup", sup);
        var item = new JsonObject(); item.addProperty("held", !entity.getHeldItem().isEmpty());
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(entity.getHeldItem().getItem()).toString());
        if (!entity.getHeldItem().isEmpty()) item.addProperty("place", entity.heldPlace().place().name());
        item.add("drawn", JsonNull.INSTANCE); f.add("item", item);
        var water = new JsonObject(); water.add("point", JsonNull.INSTANCE); f.add("water", water);
        var crossed = new JsonArray(); var previous = PREVIOUS.get(uuid);
        for (var marker : f.getAsJsonObject("clip_markers").entrySet()) {
            if (!marker.getValue().isJsonPrimitive()) continue;
            double time = marker.getValue().getAsDouble(), now = f.get("ct").getAsDouble();
            double before = previous != null && previous.get("clip").equals(f.get("clip")) ? previous.get("ct").getAsDouble() : -1;
            if ((before <= now ? before < time : true) && time <= now) crossed.add(marker.getKey());
        }
        f.add("markers", crossed);
        // Absence of authored transition evidence must remain visible to the shared checker.
    }
    public static void held(YukkuriView entity, Matrix4f drawn, float partial) {
        if (!active()) return;
        var item = CURRENT.get(entity.getUUID().toString()).getAsJsonObject("item");
        item.add("drawn", drawn == null ? JsonNull.INSTANCE : matrix(origin(entity, partial).mul(drawn)));
    }
    public static void hat(PlacedHatView hat, Matrix4f start, Matrix4f drawn, float partial) {
        if (!active()) return;
        var f = new JsonObject();
        envelope(f, hat.getUUID().toString(), hat.level().getGameTime(), hat.level().dimension().location().toString(), partial);
        f.addProperty("k", "support"); f.addProperty("model", hat.profile().hull());
        var p = hat.getPosition(partial);
        var transform = new Matrix4f().translation((float)p.x*16, (float)p.y*16, (float)p.z*16).scale(16)
                .mul(new Matrix4f(start).invert()).mul(drawn);
        f.add("root", matrix(transform)); CURRENT.put(hat.getUUID().toString(), f);
    }
    private static void envelope(JsonObject f, String uuid, long tick, String dimension, float partial) {
        f.addProperty("v", 1); f.addProperty("k", "pose"); f.addProperty("src", "client");
        f.addProperty("run", run); f.addProperty("win", window); f.addProperty("seq", sequence++);
        f.addProperty("f", frame); f.addProperty("u", uuid); f.addProperty("t", tick);
        f.addProperty("dim", dimension); f.addProperty("pt", partial);
    }
    public static long frameId() { return frame; }
    public static boolean markerFrame() {
        for (var e : CURRENT.entrySet()) {
            var f = e.getValue();
            if (!f.has("markers")) continue;
            var previous = PREVIOUS.get(e.getKey());
            if (!f.getAsJsonArray("markers").isEmpty() || previous == null
                    || !previous.get("clip").equals(f.get("clip")) || !previous.get("phase").equals(f.get("phase"))) return true;
        }
        return false;
    }
    public static void finishFrame(boolean shot) {
        if (!active()) return;
        try {
            for (var entry : CURRENT.entrySet()) {
                var f = entry.getValue();
                if (f.get("k").getAsString().equals("pose")) {
                    var sup = f.getAsJsonObject("sup");
                    if (!sup.get("host").isJsonNull()) {
                        var host = CURRENT.get(sup.get("host").getAsString());
                        if (host != null && sup.get("kind").getAsString().equals("hull")) sup.add("frame", host.get("root"));
                    }
                    var previous = PREVIOUS.get(entry.getKey());
                    var samples = previous == null ? List.of(f) : List.of(previous, f);
                    var results = PoseChecks.evaluate(samples, PoseChecks.METRICS);
                    var latest = new JsonArray();
                    for (var r : results) if (r.getAsJsonObject().get("f").equals(f.get("f"))) latest.add(r);
                    f.add("m", latest);
                    PREVIOUS.put(entry.getKey(), f);
                }
                var output = f.deepCopy();
                if (!shot) { output.remove("bones"); output.remove("skin"); }
                writer.write(JSON.toJson(output)); writer.newLine();
            }
            PREVIOUS.keySet().retainAll(CURRENT.keySet());
            CURRENT.clear(); frame++;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static void end() {
        try { writer.close(); } catch (IOException e) { throw new UncheckedIOException(e); }
        writer = null; CURRENT.clear(); PREVIOUS.clear();
    }
}
