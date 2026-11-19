package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.body.ModelFingerprint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Fitted cheek triangles from the same mirrored clips and skinning used by the renderer. */
public final class SnugglePose {
    private static final ConcurrentHashMap<String, SnugglePose> MODELS = new ConcurrentHashMap<>();
    private static final String PRESENTATION = "yukkuri_presentation/";
    private static final class Revisions {
        static final JsonObject FIT = json(resource(PRESENTATION + "placement_fit.json")).getAsJsonObject("models");
        static final JsonObject MOTION = json(resource(PRESENTATION + "motion_corrections.json")).getAsJsonObject("models");
    }

    private final Rig.Bone[] bones;
    private final Compose compose;
    private final float[][] rest, pose;
    private final Matrix4f[] world;
    private final float[] skin;
    private final Skin.Binding[] bindings;
    private final Clips.Clip left, right;
    private final SideSurface.Pose[] leftFrames, rightFrames;

    private SnugglePose(String id) {
        byte[] bytes = resource("yukkuri/" + id + ".json");
        var data = json(bytes);
        var fit = Revisions.FIT.getAsJsonObject(id);
        if (fit != null) Splice.fit(data, fit, ModelFingerprint.of(bytes)::matches);
        Splice.revision(data, Revisions.MOTION.getAsJsonObject(id));
        bones = Rig.bones(data.getAsJsonArray("bones"));
        var clips = data.getAsJsonObject("clips");
        left = Clips.read(clips.getAsJsonObject("snuggle_left"), bones.length);
        right = Clips.read(clips.getAsJsonObject("snuggle_right"), bones.length);
        leftFrames = new SideSurface.Pose[Math.round(20 * left.duration)];
        rightFrames = new SideSurface.Pose[Math.round(20 * right.duration)];
        compose = new Compose(bones);
        world = Rig.matrices(bones.length);
        rest = Rig.values(bones.length);
        pose = Rig.values(bones.length);
        Clips.read(clips.getAsJsonObject("idle"), bones.length).sample(0, rest);

        var vertices = data.getAsJsonArray("vertices");
        var weights = data.getAsJsonArray("weights");
        var body = data.getAsJsonArray("parts").asList().stream().map(part -> part.getAsJsonObject())
                .filter(part -> part.get("name").getAsString().endsWith("_body_surface")).findFirst().orElseThrow();
        int start = body.get("start").getAsInt(), count = body.get("count").getAsInt();
        skin = new float[3 * count];
        bindings = new Skin.Binding[count];
        for (int i = 0; i < count; i++) {
            int vertex = start + i;
            for (int axis = 0; axis < 3; axis++) skin[3 * i + axis] = vertices.get(8 * vertex + axis).getAsFloat();
            var weight = weights.get(vertex).getAsJsonArray();
            bindings[i] = new Skin.Binding(weight.get(0).getAsInt(), weight.get(1).getAsInt(), weight.get(2).getAsFloat());
        }
    }

    private static byte[] resource(String file) {
        try (var stream = SnugglePose.class.getResourceAsStream("/assets/mcyukkuri/" + file)) {
            if (stream == null) throw new IllegalArgumentException("Missing " + file);
            return stream.readAllBytes();
        } catch (IOException e) { throw new UncheckedIOException(file, e); }
    }

    private static JsonObject json(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public static SideSurface.Pose at(String model, boolean right, int tick) {
        return MODELS.computeIfAbsent(model, SnugglePose::new).frame(right, tick);
    }

    private synchronized SideSurface.Pose frame(boolean rightSide, int tick) {
        var frames = rightSide ? rightFrames : leftFrames;
        int i = tick % frames.length;
        if (frames[i] != null) return frames[i];
        for (int j = 0; j < pose.length; j++) System.arraycopy(rest[j], 0, pose[j], 0, 9);
        (rightSide ? right : left).sample(i / 20f, pose);
        compose.world(pose, world, -1, 1, null, null);
        var point = new Vector3f();
        var second = new Vector3f();
        var posed = new float[skin.length];
        for (int j = 0; j < bindings.length; j++) {
            int k = 3 * j;
            point.set(skin[k], skin[k + 1], skin[k + 2]);
            Skin.blend(point, second, bindings[j], world);
            posed[k] = point.x; posed[k + 1] = point.y; posed[k + 2] = point.z;
        }
        return frames[i] = new SideSurface.Pose(posed);
    }
}
