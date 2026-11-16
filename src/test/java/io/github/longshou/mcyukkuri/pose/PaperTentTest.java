package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.pose.check.Geometry;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperTentTest {
    private static JsonObject readResource(String path) throws IOException {
        try (var in = PaperTentTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "Missing resource: " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private record Loaded(String id, JsonObject data, Rig.Bone[] bones, Skin.Binding[] bindings, float[] rest,
                          int[] features, Map<Integer, Matrix4f> featureTransforms, List<String> accessoryPrefixes,
                          Map<String, Clips.Clip> clips, float[][] defaults) {}

    private static Loaded loadModel(String id, JsonObject fitAll, JsonObject motionAll, JsonObject living) throws IOException {
        JsonObject data = readResource("/assets/mcyukkuri/yukkuri/" + id + ".json");
        JsonObject fit = fitAll.getAsJsonObject("models").getAsJsonObject(id);
        if (fit != null) Splice.fit(data, fit, h -> true);
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
        Map<Integer, Matrix4f> featureTransforms = new HashMap<>();
        for (var raw : data.getAsJsonArray("parts")) {
            var p = raw.getAsJsonObject();
            if (p.has("feature_bone")) {
                int b = p.get("feature_bone").getAsInt();
                int s = p.get("start").getAsInt(), c = p.get("count").getAsInt();
                Arrays.fill(features, s, s + c, b);
                featureTransforms.put(b, new Matrix4f());
            }
        }
        List<String> acc = new ArrayList<>();
        var vObj = living.getAsJsonObject("variants").getAsJsonObject(id);
        if (vObj != null && vObj.has("accessory_prefixes")) {
            for (var el : vObj.getAsJsonArray("accessory_prefixes")) acc.add(el.getAsString());
        }
        Map<String, Clips.Clip> clips = new HashMap<>();
        data.getAsJsonObject("clips").entrySet().forEach(e -> clips.put(e.getKey(), Clips.read(e.getValue().getAsJsonObject(), bones.length)));
        if (data.has("behavior_clips")) {
            data.getAsJsonObject("behavior_clips").entrySet().forEach(e -> clips.put(e.getKey(), Clips.read(e.getValue().getAsJsonObject(), bones.length)));
        }
        float[][] defaults = Rig.values(bones.length);
        clips.get("idle").sample(0, defaults);
        return new Loaded(id, data, bones, bindings, rest, features, featureTransforms, acc, clips, defaults);
    }

    private static boolean bodySurface(String name) {
        return name.startsWith("soft_head_slice_") || name.equals("rounded_body_surface")
                || name.equals("deficient_body_surface") || name.equals("predator_body_surface") || name.equals("house_body_surface");
    }

    private static void verifyPose(Loaded m, String clipName, float time, float scale, boolean accessory,
                                   List<WallYield.Face> wallFaces) {
        float[][] pose = Rig.values(m.bones.length);
        for (int i = 0; i < pose.length; i++) System.arraycopy(m.defaults[i], 0, pose[i], 0, 9);
        Clips.Clip clip = m.clips.getOrDefault(clipName, m.clips.get("idle"));
        clip.sample(time, pose);
        Matrix4f[] world = Rig.matrices(m.bones.length);
        new Compose(m.bones).world(pose, world, -1, 1, null, null);
        m.featureTransforms.forEach((b, mat) -> Compose.feature(m.bones, world, b, mat, new Matrix4f()));

        var partsJson = m.data.getAsJsonArray("parts");
        int nParts = partsJson.size();
        String[] names = new String[nParts];
        int[] starts = new int[nParts], counts = new int[nParts];
        for (int i = 0; i < nParts; i++) {
            var p = partsJson.get(i).getAsJsonObject();
            names[i] = p.get("name").getAsString();
            starts[i] = p.get("start").getAsInt();
            counts[i] = p.get("count").getAsInt();
        }

        Vector3f scratch = new Vector3f();
        WallYield yield = null;
        Matrix4f toWall = wallFaces.isEmpty() ? null : new Matrix4f();
        Matrix4f fromWall = wallFaces.isEmpty() ? null : new Matrix4f();
        if (!wallFaces.isEmpty()) {
            yield = WallYield.of(m.bones, m.bindings, m.rest, names, starts, counts);
            if (yield != null) {
                Matrix4f modelToWall = new Matrix4f().scale(scale / 16f);
                yield.solve((idx, out) -> Skin.base(idx, out, scratch, m.rest, m.bindings, world, null, 0, null, m.features, m.featureTransforms),
                        world, modelToWall, wallFaces, kind -> false);
            }
        }

        List<Float> bodyList = new ArrayList<>(), skinOnlyList = new ArrayList<>(), meshList = new ArrayList<>(), hatList = new ArrayList<>();
        Vector3f pt = new Vector3f();
        for (int pi = 0; pi < nParts; pi++) {
            var p = partsJson.get(pi).getAsJsonObject();
            String name = names[pi];
            if (p.has("anatomy") && !p.get("anatomy").getAsString().isEmpty()) continue;
            if (name.contains("puff") || name.startsWith("face_")) continue;
            boolean isAcc = m.accessoryPrefixes.stream().anyMatch(name::startsWith);
            if (isAcc && !accessory) continue;
            boolean isSkin = bodySurface(name);
            boolean isBody = isSkin || name.endsWith("_rump_mark");
            boolean isLoose = WallYield.loose(name);
            for (int v = starts[pi]; v < starts[pi] + counts[pi]; v++) {
                Skin.base(v, pt, scratch, m.rest, m.bindings, world, null, 0, null, m.features, m.featureTransforms);
                if (yield != null) yield.apply(v, pt);
                pt.mul(scale / 16f);
                if (!wallFaces.isEmpty() && (isLoose || isAcc)) {
                    PaperTent.pressPoint(pt, toWall, fromWall, wallFaces, 0f);
                }
                if (isSkin) { skinOnlyList.add(pt.x); skinOnlyList.add(pt.y); skinOnlyList.add(pt.z); }
                if (isBody) { bodyList.add(pt.x); bodyList.add(pt.y); bodyList.add(pt.z); }
                if (isAcc) { hatList.add(pt.x); hatList.add(pt.y); hatList.add(pt.z); }
                else { meshList.add(pt.x); meshList.add(pt.y); meshList.add(pt.z); }
            }
        }
        float[] bodyArr = toArray(bodyList), skinArr = toArray(skinOnlyList), meshArr = toArray(meshList), hatArr = toArray(hatList);
        PaperTent.Grid grid = PaperTent.build(bodyArr, bodyArr.length, meshArr, meshArr.length, hatArr, hatArr.length, wallFaces, toWall);
        assertNotNull(grid, "PaperTent returned null for " + m.id);

        String ctx = m.id + "/" + clipName + "@" + time + "s scale=" + scale + " acc=" + accessory + " walls=" + wallFaces.size();
        Geometry.Mesh tentUnder = sheetMesh(grid.under(), grid.rows(), grid.cols());
        Geometry.Mesh tentTop = sheetMesh(grid.top(), grid.rows(), grid.cols());
        Geometry.Mesh closedBody = triMesh(skinArr, true);
        Geometry.Mesh fullMesh = triMesh(meshArr, false);

        assertClear(ctx + " [under vs closed body]", tentUnder, closedBody);
        assertClear(ctx + " [top vs closed body]", tentTop, closedBody);
        assertClear(ctx + " [under vs mesh+hair+braid]", tentUnder, fullMesh);
        assertClear(ctx + " [top vs mesh+hair+braid]", tentTop, fullMesh);
        if (hatArr.length >= 9) {
            Geometry.Mesh hatMesh = triMesh(hatArr, false);
            assertClear(ctx + " [under vs hat]", tentUnder, hatMesh);
            assertClear(ctx + " [top vs hat]", tentTop, hatMesh);
        }

        for (var face : wallFaces) {
            float wallPlane = face.distance() + 4f / 4096f;
            for (int r = 0; r <= grid.rows(); r++) {
                for (int c = 0; c <= grid.cols(); c++) {
                    float[] tp = grid.top()[r][c];
                    if (tp[1] >= face.low() && tp[1] <= face.high()) {
                        float toward = face.x() * tp[0] + face.z() * tp[2];
                        assertTrue(toward <= wallPlane + 1e-5f, ctx + " crossed real wall: " + toward + " > " + wallPlane);
                    }
                }
            }
        }
    }

    private static void assertClear(String ctx, Geometry.Mesh a, Geometry.Mesh b) {
        var pen = Geometry.penetration(a, b, List.of());
        assertEquals(0.0, pen.depth(), 1e-6, () -> ctx + " penetration=" + pen.depth() + " at " + pen.point());
    }

    private static Geometry.Mesh sheetMesh(float[][][] sheet, int rows, int cols) {
        List<Geometry.Triangle> tris = new ArrayList<>(rows * cols * 2);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Vector3d p00 = vec(sheet[r][c]), p10 = vec(sheet[r + 1][c]);
                Vector3d p11 = vec(sheet[r + 1][c + 1]), p01 = vec(sheet[r][c + 1]);
                tris.add(new Geometry.Triangle(p00, p10, p11));
                tris.add(new Geometry.Triangle(p00, p11, p01));
            }
        }
        return new Geometry.Mesh(tris, false);
    }

    private static Geometry.Mesh triMesh(float[] arr, boolean closed) {
        List<Geometry.Triangle> tris = new ArrayList<>(arr.length / 9);
        for (int i = 0; i + 8 < arr.length; i += 9) {
            tris.add(new Geometry.Triangle(
                    new Vector3d(arr[i], arr[i + 1], arr[i + 2]),
                    new Vector3d(arr[i + 3], arr[i + 4], arr[i + 5]),
                    new Vector3d(arr[i + 6], arr[i + 7], arr[i + 8])));
        }
        return new Geometry.Mesh(tris, closed);
    }

    private static Vector3d vec(float[] p) { return new Vector3d(p[0], p[1], p[2]); }

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    @Test void paperTentClearsLivePosedBodyHatBraidsBabiesAndWallSwing() throws IOException {
        JsonObject fit = readResource("/assets/mcyukkuri/yukkuri_presentation/placement_fit.json");
        JsonObject motions = readResource("/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json");
        JsonObject living = readResource("/assets/mcyukkuri/yukkuri_presentation/living.json");

        for (String id : List.of("reimu", "marisa", "patchouli", "alice", "myon", "reimu_wasa", "reimu_deibu", "reimu_bean", "marisa_bean")) {
            Loaded m = loadModel(id, fit, motions, living);
            verifyPose(m, "idle", 0f, 1f, true, List.of());
            verifyPose(m, "cold_shiver", 0.6f, 1f, true, List.of());
            verifyPose(m, "cold_shiver", 1.2f, 1f, true, List.of());
            verifyPose(m, "paper_cover_under", 0.76f, 1f, true, List.of());
            verifyPose(m, "paper_cover_under", 1.4f, 1f, true, List.of());
            verifyPose(m, "sleep", 0.8f, 1f, true, List.of());
        }

        Loaded marisa = loadModel("marisa", fit, motions, living);
        Loaded reimu = loadModel("reimu", fit, motions, living);
        verifyPose(marisa, "cold_shiver", 0.6f, 1f, false, List.of());
        verifyPose(reimu, "cold_shiver", 0.6f, .42f, true, List.of());
        verifyPose(reimu, "sleep", 0.8f, .42f, false, List.of());
        verifyPose(marisa, "cold_shiver", 0.6f, .42f, true, List.of());

        verifyPose(marisa, "cold_shiver", 0.6f, 1f, true, List.of(new WallYield.Face(1f, 0f, .45f, 0f, 1.25f)));
        verifyPose(marisa, "cold_shiver", 0.6f, 1f, true, List.of(new WallYield.Face(-1f, 0f, .45f, 0f, 1.25f)));
        verifyPose(marisa, "cold_shiver", 0.6f, 1f, true, List.of(new WallYield.Face(0f, 1f, .42f, 0f, 1.25f)));
        verifyPose(marisa, "cold_shiver", 0.6f, 1f, true, List.of(
                new WallYield.Face(1f, 0f, .45f, 0f, 1.25f), new WallYield.Face(0f, 1f, .42f, 0f, 1.25f)));
        verifyPose(reimu, "cold_shiver", 0.6f, 1f, true, List.of(new WallYield.Face(0f, 1f, .42f, 0f, 1.25f)));
        verifyPose(reimu, "cold_shiver", 0.6f, 1f, true, List.of(new WallYield.Face(1f, 0f, .45f, 0f, 1.25f)));
    }
}
