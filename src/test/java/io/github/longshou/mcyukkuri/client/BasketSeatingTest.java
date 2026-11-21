package io.github.longshou.mcyukkuri.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Seats must rest on the liner, stay inside the rim's opening and pass under the arched handle. */
class BasketSeatingTest {
    private static final float EPS = 1e-3f;

    private record Mesh(float[] vertices, JsonObject parts) {}
    private record Arrangement(List<BasketSeating.Box> boxes, float[] scales) {}

    private static Mesh basket(String id) throws IOException {
        try (var in = BasketSeatingTest.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/" + id + ".json")) {
            assertNotNull(in, "Missing basket mesh: " + id);
            var data = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            var array = data.getAsJsonArray("vertices");
            var vertices = new float[array.size()];
            for (int i = 0; i < vertices.length; i++) vertices[i] = array.get(i).getAsFloat();
            return new Mesh(vertices, data);
        }
    }

    private static List<float[]> part(Mesh mesh, String name) {
        for (var element : mesh.parts.getAsJsonArray("parts")) {
            var part = element.getAsJsonObject();
            if (!part.get("name").getAsString().equals(name)) continue;
            int start = part.get("start").getAsInt();
            int count = part.get("count").getAsInt();
            var rows = new ArrayList<float[]>();
            for (int i = start; i < start + count; i++) {
                rows.add(new float[] {mesh.vertices[i * 8], mesh.vertices[i * 8 + 1], mesh.vertices[i * 8 + 2]});
            }
            return rows;
        }
        throw new AssertionError("Missing basket part: " + name);
    }

    /** The rim's inner ring at the rim's mid height, in ring order: the opening an occupant must pass. */
    private static List<float[]> mouth(Mesh mesh) {
        var ring = new ArrayList<float[]>();
        for (var v : part(mesh, "rolled_rim")) {
            if (Math.abs(v[1] - 7f) > EPS) continue;
            if (Math.hypot(v[0] / 4.25, v[2] / 3.5) < 1.1) ring.add(v);
        }
        ring.sort(Comparator.comparingDouble(v -> Math.atan2(v[2] / 3.5, v[0] / 4.25)));
        return ring;
    }

    private static boolean inside(List<float[]> polygon, float x, float z) {
        for (int i = 0; i < polygon.size(); i++) {
            var a = polygon.get(i);
            var b = polygon.get((i + 1) % polygon.size());
            float cross = (b[0] - a[0]) * (z - a[2]) - (b[2] - a[2]) * (x - a[0]);
            if (cross < -EPS) return false;
        }
        return true;
    }

    private static BasketSeating.Box occupantBounds(String model) throws IOException {
        try (var in = BasketSeatingTest.class.getResourceAsStream("/assets/mcyukkuri/yukkuri/" + model + ".json")) {
            assertNotNull(in, "Missing model: " + model);
            var data = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            var array = data.getAsJsonArray("vertices");
            float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
            float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int i = 0; i + 2 < array.size(); i += 8) {
                float x = array.get(i).getAsFloat(), y = array.get(i + 1).getAsFloat(), z = array.get(i + 2).getAsFloat();
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
            return new BasketSeating.Box(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static void assertSeated(List<float[]> mouth, List<float[]> handle, BasketSeating.Box box,
                                     BasketSeating.Seat seat) {
        assertEquals(BasketSeating.LINER_Y, seat.y() + box.minY() * seat.scale(), EPS, "feet on the liner");
        for (float x : new float[] {box.minX(), box.maxX()}) {
            for (float z : new float[] {box.minZ(), box.maxZ()}) {
                assertTrue(inside(mouth, seat.x() + x * seat.scale(), seat.z() + z * seat.scale()),
                        "corner outside the rim opening: x=" + x + " z=" + z);
            }
        }
        float zMin = seat.z() + box.minZ() * seat.scale();
        float zMax = seat.z() + box.maxZ() * seat.scale();
        if (zMin > BasketSeating.HANDLE_HALF_DEPTH || zMax < -BasketSeating.HANDLE_HALF_DEPTH) return;
        float xMin = seat.x() + box.minX() * seat.scale();
        float xMax = seat.x() + box.maxX() * seat.scale();
        float lowest = Float.POSITIVE_INFINITY;
        for (var v : handle) if (v[0] >= xMin - EPS && v[0] <= xMax + EPS) lowest = Math.min(lowest, v[1]);
        if (lowest < Float.POSITIVE_INFINITY) {
            assertTrue(seat.y() + box.maxY() * seat.scale() <= lowest + EPS, "head clears the handle");
        }
    }

    @Test
    void allThreeBasketsShareTheSeatingMesh() throws IOException {
        var base = part(basket("yukkuri_carrier"), "wicker_body");
        for (var id : List.of("yukkuri_carrier_fine", "yukkuri_carrier_large")) {
            var other = part(basket(id), "wicker_body");
            assertEquals(base.size(), other.size());
            for (int i = 0; i < base.size(); i++) {
                for (int axis = 0; axis < 3; axis++) assertEquals(base.get(i)[axis], other.get(i)[axis], EPS);
            }
        }
    }

    @Test
    void everyOccupantRestsOnTheLinerInsideTheOpeningAndUnderTheHandle() throws IOException {
        var mesh = basket("yukkuri_carrier");
        var mouth = mouth(mesh);
        var handle = part(mesh, "arched_handle");
        var reimu = occupantBounds("reimu");
        var marisa = occupantBounds("marisa");
        assertEquals(5.5f, part(mesh, "cream_liner").stream().mapToDouble(v -> v[1]).min().orElseThrow(), EPS,
                "liner floor height");
        var cases = List.of(
                new Arrangement(List.of(reimu), new float[] {.25f}),
                new Arrangement(List.of(reimu), new float[] {.4f}),
                new Arrangement(List.of(marisa), new float[] {1f}),
                new Arrangement(List.of(reimu, reimu), new float[] {.25f, .25f}),
                new Arrangement(List.of(reimu, reimu, reimu, reimu), new float[] {.4f, .4f, .4f, .4f}),
                new Arrangement(List.of(reimu, reimu, reimu, reimu, reimu, reimu),
                        new float[] {.25f, .25f, .25f, .25f, .25f, .25f}),
                new Arrangement(List.of(reimu, marisa, reimu, marisa, reimu, marisa),
                        new float[] {.4f, .4f, .25f, .25f, .25f, .25f}));
        for (var arrangement : cases) {
            var boxes = arrangement.boxes().toArray(BasketSeating.Box[]::new);
            var seats = BasketSeating.arrange(boxes, arrangement.scales());
            for (int i = 0; i < boxes.length; i++) {
                assertTrue(seats[i].scale() > 0 && seats[i].scale() <= arrangement.scales()[i] + EPS);
                assertSeated(mouth, handle, boxes[i], seats[i]);
            }
        }
    }
}
