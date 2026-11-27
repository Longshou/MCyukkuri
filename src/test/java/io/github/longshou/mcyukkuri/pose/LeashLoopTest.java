package io.github.longshou.mcyukkuri.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class LeashLoopTest {
    private static final Path MODELS = Path.of("src/main/resources/assets/mcyukkuri/yukkuri");
    private static final Path LIVING = Path.of("src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json");
    private static final Path SUBSPECIES = Path.of("src/main/resources/assets/mcyukkuri/yukkuri_presentation/subspecies.json");

    @Test
    void buildsCleanLoopOnEverySpecies() throws IOException {
        var variants = variantPrefixes();
        for (String id : bodyModels()) {
            var model = JsonParser.parseString(Files.readString(MODELS.resolve(id + ".json"))).getAsJsonObject();
            var verts = model.getAsJsonArray("vertices");
            List<String> acc = variants.has(id) ? prefixes(variants.getAsJsonObject(id)) : List.of();
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
            var loop = new LeashLoop.Builder();
            assertTrue(loop.build(toArray(body), body.size(), toArray(hair), hair.size(), 0f), "null mesh for " + id);
            float[] mesh = loop.vertices();
            assertTrue(loop.vertexCount() > 1000 && loop.vertexCount() % 24 == 0, "bad tri count for " + id);
            float bodyH = maxY - minY;
            // Back knot sits behind the body (+Z) near BACK_HEIGHT.
            float[] knot = loop.knotAnchor();
            assertTrue(knot[2] > 0, "knot should be on +Z back for " + id);
            assertEquals(minY + LeashLoop.BACK_HEIGHT * bodyH, knot[1], bodyH * .08f, "knot height for " + id);
            // Front rope stays low (below face/mouth) and never drops below the ground.
            float frontMaxY = -Float.MAX_VALUE;
            for (int i = 0; i < loop.vertexCount(); i += 8) {
                float y = mesh[i + 1], z = mesh[i + 2];
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
        var loop = new LeashLoop.Builder();
        assertTrue(loop.build(sphere, sphere.length, null, 0, 0f));
        float adultBack = loop.knotAnchor()[2], adultHeight = loop.knotAnchor()[1];
        assertTrue(loop.build(baby, baby.length, null, 0, 0f));
        assertEquals(adultBack * .45f, loop.knotAnchor()[2], .02f);
        assertTrue(loop.build(squashed, squashed.length, null, 0, 0f));
        assertTrue(loop.knotAnchor()[2] > adultBack * 1.07f, "squash should widen rope loop");
        assertTrue(loop.knotAnchor()[1] < adultHeight * .88f, "squash should lower rope loop");
    }

    /** Every body model resource: a new species cannot be added without a loop check. */
    private static TreeSet<String> bodyModels() throws IOException {
        var ids = new TreeSet<String>();
        try (Stream<Path> files = Files.list(MODELS)) {
            for (var path : (Iterable<Path>) files.sorted()::iterator) {
                String name = path.getFileName().toString();
                if (name.endsWith(".json") && !name.equals("badge_mounts.json")) ids.add(name.substring(0, name.length() - 5));
            }
        }
        return ids;
    }

    /** Presentation variants (base and subspecies) merged, so subspecies read their own accessory prefixes. */
    private static JsonObject variantPrefixes() throws IOException {
        var all = new JsonObject();
        for (var path : List.of(LIVING, SUBSPECIES)) {
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            var from = root.has("variants") ? root.getAsJsonObject("variants") : root;
            for (var entry : from.entrySet()) all.add(entry.getKey(), entry.getValue());
        }
        return all;
    }

    private static List<String> prefixes(JsonObject variant) {
        var list = new ArrayList<String>();
        if (variant.has("accessory_prefixes"))
            for (var e : variant.getAsJsonArray("accessory_prefixes")) list.add(e.getAsString());
        return list;
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
