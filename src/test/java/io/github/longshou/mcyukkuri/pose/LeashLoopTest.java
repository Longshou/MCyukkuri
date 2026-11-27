package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LeashLoopTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/mcyukkuri/yukkuri");
    private static final Path LIVING = Path.of("src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json");

    @Test
    void buildsCleanLoopOnEverySpecies() throws IOException {
        var living = JsonParser.parseString(Files.readString(LIVING)).getAsJsonObject().getAsJsonObject("variants");
        String[] species = {"reimu", "marisa", "alice", "patchouli", "chen", "remilia", "flandre", "myon",
                "house_reimu", "reimu_bean", "reimu_deibu", "reimu_wasa"};
        for (String id : species) {
            var model = JsonParser.parseString(Files.readString(MODELS.resolve(id + ".json"))).getAsJsonObject();
            var verts = model.getAsJsonArray("vertices");
            var acc = new ArrayList<String>();
            if (living.has(id) && living.getAsJsonObject(id).has("accessory_prefixes"))
                for (var e : living.getAsJsonObject(id).getAsJsonArray("accessory_prefixes")) acc.add(e.getAsString());
            List<Float> body = new ArrayList<>(), hair = new ArrayList<>();
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (var raw : model.getAsJsonArray("parts")) {
                var p = raw.getAsJsonObject();
                String name = p.get("name").getAsString();
                String organ = p.has("anatomy") ? p.get("anatomy").getAsString() : "";
                int start = p.get("start").getAsInt(), count = p.get("count").getAsInt();
                boolean isBody = name.startsWith("soft_head_slice_") || name.equals("rounded_body_surface")
                        || name.equals("house_body_surface") || name.equals("predator_body_surface")
                        || name.equals("deficient_body_surface") || name.endsWith("_rump_mark");
                boolean isAcc = acc.stream().anyMatch(name::startsWith);
                boolean isHair = !isBody && !isAcc && !name.startsWith("face_") && !name.contains("puff")
                        && organ.isEmpty() && !name.contains("rump") && !name.contains("rear_mark")
                        && !name.contains("tail") && !name.contains("wing");
                for (int i = start; i < start + count; i++) {
                    float x = verts.get(i * 8).getAsFloat();
                    float y = verts.get(i * 8 + 1).getAsFloat();
                    float z = verts.get(i * 8 + 2).getAsFloat();
                    if (isBody) {
                        body.add(x); body.add(y); body.add(z);
                        minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                    } else if (isHair) {
                        hair.add(x); hair.add(y); hair.add(z);
                    }
                }
            }
            var mesh = LeashLoop.build(toArray(body), body.size(), toArray(hair), hair.size(), 0f);
            assertNotNull(mesh, "null mesh for " + id);
            assertTrue(mesh.vertices().length > 1000 && mesh.vertices().length % 24 == 0, "bad tri count for " + id);
            float bodyH = maxY - minY;
            // Back knot sits behind the body (+Z) near BACK_HEIGHT.
            float[] knot = mesh.knotAnchor();
            assertTrue(knot[2] > 0, "knot should be on +Z back for " + id);
            assertEquals(minY + LeashLoop.BACK_HEIGHT * bodyH, knot[1], bodyH * .08f, "knot height for " + id);
            // Front rope stays low (below face/mouth) and never drops below the ground.
            float frontMaxY = -Float.MAX_VALUE;
            for (int i = 0; i < mesh.vertices().length; i += 8) {
                float y = mesh.vertices()[i + 1], z = mesh.vertices()[i + 2];
                assertTrue(y >= minY - bodyH * .02f, "rope below floor for " + id);
                if (z < 0) frontMaxY = Math.max(frontMaxY, y);
            }
            assertTrue(frontMaxY < minY + .30f * bodyH, "front rope too high (clips face) for " + id + ": " + frontMaxY);
        }
    }

    @Test
    void scalesProportionallyWithGrowthAndSquash() {
        float[] sphere = unitSphere(1f, 1f);
        float[] baby = unitSphere(.45f, .45f);
        float[] squashed = unitSphere(1.12f, .82f);
        var mAdult = LeashLoop.build(sphere, sphere.length, null, 0, 0f);
        var mBaby = LeashLoop.build(baby, baby.length, null, 0, 0f);
        var mSquash = LeashLoop.build(squashed, squashed.length, null, 0, 0f);
        assertNotNull(mAdult); assertNotNull(mBaby); assertNotNull(mSquash);
        assertEquals(mAdult.knotAnchor()[2] * .45f, mBaby.knotAnchor()[2], .02f);
        assertTrue(mSquash.knotAnchor()[2] > mAdult.knotAnchor()[2] * 1.07f, "squash should widen rope loop");
        assertTrue(mSquash.knotAnchor()[1] < mAdult.knotAnchor()[1] * .88f, "squash should lower rope loop");
    }

    private static float[] unitSphere(float rx, float ry) {
        List<Float> pts = new ArrayList<>();
        for (int lat = 0; lat <= 8; lat++) {
            double phi = Math.PI * lat / 8;
            float y = (float) ((1 - Math.cos(phi)) * .5 * ry);
            float r = (float) Math.sin(phi) * .5f * rx;
            for (int lon = 0; lon < 24; lon++) {
                double th = 2 * Math.PI * lon / 24;
                pts.add((float) Math.cos(th) * r);
                pts.add(y);
                pts.add((float) Math.sin(th) * r);
            }
        }
        return toArray(pts);
    }

    private static float[] toArray(List<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }
}
