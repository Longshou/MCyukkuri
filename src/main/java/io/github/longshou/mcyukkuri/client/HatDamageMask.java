package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Torn, frayed and pieced-together accessories, and the shard items cut from them (docs/HEADWEAR.md). The authored recipe
 * (art/props/hat-damage/recipe.json, exported to yukkuri_presentation/hat_damage.json) places holes, frayed rims and cracks
 * in the accessory's own bounding box, so the same rule applies to every species' worn accessory and to its item model.
 * Triangles are classified by their rest centroid: drawn, drawn with the frayed tint, or left out.
 */
public final class HatDamageMask {
    public static final byte DRAW = 0, FRAYED = 1, HIDDEN = 2;
    record Sphere(float x, float y, float z, float r) {}
    record Crack(float nx, float ny, float nz, float offset, float width) {}
    record Level(List<Sphere> holes, float fray, List<Crack> cracks) {}
    private static Level[] levels = new Level[4];
    private static List<Sphere> pieces = List.of();
    private static int tint = 0x8C7A6C;
    private static int generation;
    public static final ResourceManagerReloadListener RELOAD = HatDamageMask::reload;

    private static void reload(ResourceManager resources) {
        var loaded = new Level[4];
        List<Sphere> parsedPieces = new ArrayList<>();
        int parsedTint = 0x8C7A6C;
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/hat_damage.json")).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            var t = data.getAsJsonArray("fray_tint");
            if (t != null) parsedTint = t.get(0).getAsInt() << 16 | t.get(1).getAsInt() << 8 | t.get(2).getAsInt();
            String[] names = {null, "frayed", "torn", "pieced"};
            for (int i = 1; i < 4; i++) if (data.has(names[i])) loaded[i] = level(data.getAsJsonObject(names[i]));
            for (var p : data.getAsJsonArray("pieces")) parsedPieces.add(sphere(p.getAsJsonObject()));
        } catch (Exception error) {
            MCYukkuri.LOGGER.warn("Accessory damage presentation unavailable: {}", error.toString());
        }
        levels = loaded; pieces = List.copyOf(parsedPieces); tint = parsedTint; generation++;
    }
    private static Sphere sphere(JsonObject o) {
        var c = o.getAsJsonArray("center");
        return new Sphere(c.get(0).getAsFloat(), c.get(1).getAsFloat(), c.get(2).getAsFloat(), o.get("radius").getAsFloat());
    }
    private static Level level(JsonObject o) {
        List<Sphere> holes = new ArrayList<>();
        if (o.has("holes")) for (var h : o.getAsJsonArray("holes")) holes.add(sphere(h.getAsJsonObject()));
        List<Crack> cracks = new ArrayList<>();
        if (o.has("cracks")) for (var c : o.getAsJsonArray("cracks")) {
            var k = c.getAsJsonObject(); var n = k.getAsJsonArray("normal");
            float nx = n.get(0).getAsFloat(), ny = n.get(1).getAsFloat(), nz = n.get(2).getAsFloat();
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz); if (len < 1e-6f) continue;
            cracks.add(new Crack(nx / len, ny / len, nz / len, k.get("offset").getAsFloat(), k.get("width").getAsFloat()));
        }
        return new Level(List.copyOf(holes), o.has("fray") ? o.get("fray").getAsFloat() : 0, List.copyOf(cracks));
    }
    /** Changes on every resource reload, so cached classifications can be rebuilt. */
    public static int generation() { return generation; }
    public static int tintRed() { return tint >> 16 & 255; }
    public static int tintGreen() { return tint >> 8 & 255; }
    public static int tintBlue() { return tint & 255; }

    /** Bounds (min x,y,z, max x,y,z) of the given triangles in the eight-float vertex layout; {@code ranges} are [start, count) vertex pairs. */
    public static float[] bounds(float[] v, int[] ranges) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int r = 0; r < ranges.length; r += 2) for (int i = ranges[r]; i < ranges[r] + ranges[r + 1]; i++)
            for (int a = 0; a < 3; a++) { b[a] = Math.min(b[a], v[i * 8 + a]); b[a + 3] = Math.max(b[a + 3], v[i * 8 + a]); }
        return b;
    }
    /**
     * Class of each triangle for a damage level (1 frayed, 2 torn, 3 pieced back together), or with {@code piece >= 0} the
     * triangles belonging to that shard (DRAW) and the rest HIDDEN. One entry per triangle of the given vertex range.
     */
    public static byte[] classify(float[] v, int start, int count, float[] bounds, int level, int piece) {
        byte[] result = new byte[count / 3];
        Level spec = level > 0 && level < 4 ? levels[level] : null;
        Sphere shard = piece >= 0 && !pieces.isEmpty() ? pieces.get(piece % pieces.size()) : null;
        for (int t = 0; t < result.length; t++) {
            int first = start + t * 3;
            float x = 0, y = 0, z = 0;
            for (int c = 0; c < 3; c++) { x += v[(first + c) * 8]; y += v[(first + c) * 8 + 1]; z += v[(first + c) * 8 + 2]; }
            x = norm(x / 3, bounds[0], bounds[3]); y = norm(y / 3, bounds[1], bounds[4]); z = norm(z / 3, bounds[2], bounds[5]);
            if (piece >= 0) { result[t] = shard != null && inside(shard, x, y, z, 0) ? DRAW : HIDDEN; continue; }
            if (spec == null) continue;
            byte code = DRAW;
            for (var hole : spec.holes) {
                if (inside(hole, x, y, z, 0)) { code = HIDDEN; break; }
                if (inside(hole, x, y, z, spec.fray)) code = FRAYED;
            }
            if (code == DRAW) for (var crack : spec.cracks)
                if (Math.abs(crack.nx * x + crack.ny * y + crack.nz * z - crack.offset) < crack.width) { code = FRAYED; break; }
            result[t] = code;
        }
        return result;
    }
    private static float norm(float value, float min, float max) { return max - min < 1e-5f ? .5f : (value - min) / (max - min); }
    private static boolean inside(Sphere s, float x, float y, float z, float margin) {
        float dx = x - s.x, dy = y - s.y, dz = z - s.z, r = s.r + margin;
        return dx * dx + dy * dy + dz * dz < r * r;
    }
    private HatDamageMask() {}
}
