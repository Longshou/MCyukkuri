package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the bevelled cold-stiff ice shell fits every runtime model, growth scale, headwear state and crack stage without clipping. */
class IceShellTest {
    private static final String[] ALL_MODELS = {
            "alice", "chen", "chen_longtail", "chen_roundtail", "flandre",
            "house_marisa", "house_reimu", "marisa", "marisa_bean", "marisa_face",
            "marisa_kotatsu", "marisa_sparse", "marisa_tsumuri", "myon", "myon_redeye",
            "patchouli", "reimu", "reimu_bean", "reimu_deibu", "reimu_face",
            "reimu_sparse", "reimu_wasa", "reimu_wasa_deibu", "remilia"
    };

    private static JsonObject readResource(String path) throws IOException {
        try (var in = IceShellTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void everyRuntimeModelAndScaleFitsStrictlyInsideBevelledShell() throws IOException {
        JsonObject fitAll = readResource("/assets/mcyukkuri/yukkuri_presentation/placement_fit.json");
        JsonObject motionAll = readResource("/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json");
        JsonObject living = readResource("/assets/mcyukkuri/yukkuri_presentation/living.json");
        float[] scales = {1.0f, 0.58f, 0.28f}; // adult, child, baby
        var shell = new IceShell();

        for (String id : ALL_MODELS) {
            JsonObject data = readResource("/assets/mcyukkuri/yukkuri/" + id + ".json");
            JsonObject fitJson = fitAll.getAsJsonObject("models").getAsJsonObject(id);
            if (fitJson != null) Splice.fit(data, fitJson, h -> true);
            JsonObject rev = motionAll.getAsJsonObject("models").getAsJsonObject(id);
            if (rev != null) Splice.revision(data, rev);

            Rig.Bone[] bones = Rig.bones(data.getAsJsonArray("bones"));
            float[] rest = Rig.floats(data.getAsJsonArray("vertices"));
            var weights = data.getAsJsonArray("weights");
            Skin.Binding[] bindings = new Skin.Binding[weights.size()];
            for (int i = 0; i < bindings.length; i++) {
                float[] w = Rig.floats(weights.get(i).getAsJsonArray());
                bindings[i] = new Skin.Binding((int) w[0], (int) w[1], w[2]);
            }
            int[] features = new int[bindings.length];
            Arrays.fill(features, -1);
            Matrix4f[] featureTransforms = new Matrix4f[bones.length];
            for (var raw : data.getAsJsonArray("parts")) {
                var p = raw.getAsJsonObject();
                if (p.has("feature_bone")) {
                    int b = p.get("feature_bone").getAsInt();
                    Arrays.fill(features, p.get("start").getAsInt(), p.get("start").getAsInt() + p.get("count").getAsInt(), b);
                    featureTransforms[b] = new Matrix4f();
                }
            }
            List<String> acc = new ArrayList<>();
            var vObj = living.getAsJsonObject("variants").getAsJsonObject(id);
            if (vObj != null && vObj.has("accessory_prefixes"))
                for (var el : vObj.getAsJsonArray("accessory_prefixes")) acc.add(el.getAsString());

            Map<String, Clips.Clip> clips = new HashMap<>();
            data.getAsJsonObject("clips").entrySet().forEach(e -> clips.put(e.getKey(), Clips.read(e.getValue().getAsJsonObject(), bones.length)));
            if (data.has("behavior_clips"))
                data.getAsJsonObject("behavior_clips").entrySet().forEach(e -> clips.put(e.getKey(), Clips.read(e.getValue().getAsJsonObject(), bones.length)));

            assertTrue(clips.containsKey("cold_curl"), id + " missing cold_curl");
            assertTrue(clips.containsKey("cold_stiff"), id + " missing cold_stiff");
            assertTrue(clips.containsKey("cold_thaw"), id + " missing cold_thaw");

            float[][] pose = Rig.values(bones.length);
            clips.get("idle").sample(0, pose);
            clips.get("cold_stiff").sample(0, pose);
            Matrix4f[] world = Rig.matrices(bones.length);
            new Compose(bones).world(pose, world, -1, 1, null, null);
            Compose.features(bones, world, featureTransforms, new Matrix4f());

            for (boolean accessory : new boolean[]{true, false}) {
                var pts = new ArrayList<Float>();
                float bodyMinY = Float.MAX_VALUE, bodyMaxY = -Float.MAX_VALUE;
                var pt = new Vector3f();
                var scratch = new Vector3f();
                for (var raw : data.getAsJsonArray("parts")) {
                    var p = raw.getAsJsonObject();
                    String name = p.get("name").getAsString();
                    String anatomy = p.has("anatomy") ? p.get("anatomy").getAsString() : "";
                    if (!anatomy.isEmpty() || name.startsWith("face_") || name.contains("puff")) continue;
                    boolean isAcc = acc.stream().anyMatch(name::startsWith);
                    if (isAcc && !accessory) continue;
                    boolean isBody = LeashRing.bodySurface(name);
                    int s = p.get("start").getAsInt(), c = p.get("count").getAsInt();
                    for (int v = s; v < s + c; v++) {
                        Skin.base(v, pt, scratch, rest, bindings, world, null, 0f, null, features, featureTransforms);
                        pts.add(pt.x / 16f); pts.add(pt.y / 16f); pts.add(pt.z / 16f);
                        if (isBody) { bodyMinY = Math.min(bodyMinY, pt.y / 16f); bodyMaxY = Math.max(bodyMaxY, pt.y / 16f); }
                    }
                }
                float[] basePts = new float[pts.size()];
                for (int i = 0; i < basePts.length; i++) basePts[i] = pts.get(i);
                float bodyH = bodyMaxY - bodyMinY;
                assertTrue(bodyH > 0.05f, id + " degenerate body height");

                for (float scale : scales) {
                    float[] scaled = new float[basePts.length];
                    for (int i = 0; i < basePts.length; i++) scaled[i] = basePts[i] * scale;
                    var fit = IceShell.fit(scaled, scaled.length, bodyH * scale, scale / 16f);
                    assertNotNull(fit, id + " fit null");
                    assertTrue(fit.bv() > 0 && fit.bt() > 0, id + " bevels must be positive");
                    assertTrue(2f * (fit.bv() + fit.bt()) < Math.min(fit.x1() - fit.x0(), fit.z1() - fit.z0()),
                            id + " top cap degenerate");

                    // Every visible body, hair and headwear vertex must lie strictly inside all 17 outer planes.
                    float worst = -Float.MAX_VALUE;
                    for (int i = 0; i < scaled.length; i += 3) {
                        float d = fit.signedDistance(scaled[i], scaled[i + 1], scaled[i + 2]);
                        if (d > worst) worst = d;
                    }
                    assertTrue(worst <= 1e-4f, id + " acc=" + accessory + " scale=" + scale + " clipped shell by " + worst);

                    // Build all 4 crack stages and verify watertight triangle count, finite vertices, outward normals and tile UVs.
                    for (int crack = 0; crack <= IceShell.MAX_CRACK; crack++) {
                        shell.build(fit, 1f, crack);
                        assertEquals(IceShell.TRIANGLES * IceShell.TRIANGLE, shell.length());
                        float[] verts = shell.vertices();
                        float uMin = (crack & 1) * 0.5f, uMax = uMin + 0.5f;
                        float vMin = (crack >> 1) * 0.5f, vMax = vMin + 0.5f;
                        for (int k = 0; k < shell.length(); k += 8) {
                            for (int c = 0; c < 8; c++) assertTrue(Float.isFinite(verts[k + c]), "non-finite vertex");
                            assertTrue(verts[k + 3] > uMin && verts[k + 3] < uMax, "U out of crack tile " + crack);
                            assertTrue(verts[k + 4] > vMin && verts[k + 4] < vMax, "V out of crack tile " + crack);
                        }
                    }
                }
            }
        }
    }

    @Test
    void growthRampStartsLowAndReachesFullHeightAt40Ticks() {
        assertEquals(0f, IceShell.growth(0f), 1e-6f);
        assertTrue(IceShell.growth(20f) > 0.5f && IceShell.growth(20f) < 1f);
        assertEquals(1f, IceShell.growth(IceShell.GROW_TICKS), 1e-6f);
        assertEquals(1f, IceShell.growth(200f), 1e-6f);
    }
}
