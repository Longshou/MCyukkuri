package io.github.longshou.mcyukkuri.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Pixel-like accessory damage (docs/HEADWEAR.md).
 *
 * <p>The complete accessory mesh stays.  Each front/back/top/bottom box face is split into
 * square cells and a few cells are omitted; side faces stay whole.  The holes get a close-colour
 * inner cap sampled from the local cloth and a same-hue rim.  This is deliberately chunkier than
 * the old sphere cuts: MC is a pixel game, and a handful of big square notches reads much better
 * than a field of tiny pinholes.
 *
 * <p>The same rule is used for the worn hat (client/YukkuriModel) and a detached accessory item
 * (client/PropModels).  Shards are one shared cloth patch tinted by the source accessory, so
 * piece 0/1/2 look identical and are easy to manage.
 */
public final class HatDamageMask {
    public static final byte HIDDEN = 0, DRAW = 1, EDGE = 2;
    /** Kept for source compatibility with the old triangle mask; no caller should use it. */
    @Deprecated public static final byte FRAYED = EDGE;

    private record Spot(float cx, float cy, float rx, float ry, float phase) {}

    private static int seed = 24291;
    private static float targetEdge = .65f;
    private static int minSubdivision = 2, maxSubdivision = 5;
    private static float edgeBand = .10f, edgeShade = .58f, trimScale = .55f;
    private static Spot[] spots1 = {
            new Spot(.02f, .38f, .085f, .13f, .4f),
            new Spot(.98f, .38f, .085f, .13f, 1.7f),
            new Spot(.50f, .98f, .13f, .065f, 2.6f)};
    private static Spot[] spots2 = {
            new Spot(.02f, .38f, .085f, .13f, .4f),
            new Spot(.98f, .38f, .085f, .13f, 1.7f),
            new Spot(.50f, .98f, .13f, .065f, 2.6f),
            new Spot(.55f, .02f, .14f, .065f, .9f),
            new Spot(.08f, .84f, .06f, .11f, 1.3f),
            new Spot(.92f, .84f, .06f, .11f, 2.1f)};
    private static float[][] seams = {{.8f, .4f, 0f, .62f, .030f}, {.25f, -.9f, .1f, .18f, .030f}};
    private static Map<String, int[]> tints = Map.of();
    private static String shard = "accessory_shard";
    private static int generation;
    private static final String[] TRIM = {"hem", "edge", "lace", "band", "frill", "scallop", "crescent", "ribbon", "knot"};

    public static final ResourceManagerReloadListener RELOAD = HatDamageMask::reload;

    private static void reload(ResourceManager resources) {
        int parsedSeed = 24291;
        float parsedTarget = .65f;
        int parsedMin = 2, parsedMax = 5;
        float parsedBand = .10f, parsedShade = .58f, parsedTrim = .55f;
        Spot[] parsed1 = spots1, parsed2 = spots2;
        float[][] parsedSeams = seams;
        Map<String, int[]> parsedTints = new HashMap<>();
        String parsedShard = "accessory_shard";
        try (var reader = resources.getResourceOrThrow(MCYukkuri.id("yukkuri_presentation/hat_damage.json")).openAsReader()) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            var nib = data.getAsJsonObject("nibble");
            if (nib != null) {
                parsedSeed = nib.has("seed") ? nib.get("seed").getAsInt() : parsedSeed;
                parsedTarget = nib.has("target_edge") ? nib.get("target_edge").getAsFloat() : parsedTarget;
                parsedMin = nib.has("min_subdivision") ? nib.get("min_subdivision").getAsInt() : parsedMin;
                parsedMax = nib.has("max_subdivision") ? nib.get("max_subdivision").getAsInt() : parsedMax;
                parsedBand = nib.has("edge_band") ? nib.get("edge_band").getAsFloat() : parsedBand;
                parsedShade = nib.has("edge_shade") ? nib.get("edge_shade").getAsFloat() : parsedShade;
                parsedTrim = nib.has("trim_scale") ? nib.get("trim_scale").getAsFloat() : parsedTrim;
                if (nib.has("spots")) {
                    var groups = nib.getAsJsonObject("spots");
                    if (groups.has("1")) parsed1 = spots(groups.getAsJsonArray("1"));
                    if (groups.has("2")) parsed2 = spots(groups.getAsJsonArray("2"));
                }
                if (nib.has("seams")) {
                    var rows = nib.getAsJsonArray("seams");
                    var loaded = new float[rows.size()][];
                    for (int i = 0; i < rows.size(); i++) {
                        var row = rows.get(i).getAsJsonArray();
                        loaded[i] = new float[row.size()];
                        for (int k = 0; k < row.size(); k++) loaded[i][k] = row.get(k).getAsFloat();
                    }
                    if (loaded.length > 0) parsedSeams = loaded;
                }
            }
            if (data.has("shard")) parsedShard = data.get("shard").getAsString();
            if (data.has("tints")) for (var entry : data.getAsJsonObject("tints").entrySet()) {
                var row = entry.getValue().getAsJsonArray();
                if (row.size() >= 3) parsedTints.put(entry.getKey(), new int[]{row.get(0).getAsInt(), row.get(1).getAsInt(), row.get(2).getAsInt()});
            }
        } catch (Exception error) {
            MCYukkuri.LOGGER.warn("Accessory damage presentation unavailable: {}", error.toString());
        }
        seed = parsedSeed;
        targetEdge = Math.max(.05f, parsedTarget);
        minSubdivision = Math.max(1, Math.min(parsedMin, parsedMax));
        maxSubdivision = Math.max(minSubdivision, parsedMax);
        edgeBand = parsedBand; edgeShade = parsedShade; trimScale = parsedTrim;
        spots1 = parsed1; spots2 = parsed2; seams = parsedSeams;
        tints = Map.copyOf(parsedTints);
        shard = parsedShard;
        generation++;
    }

    private static Spot[] spots(com.google.gson.JsonArray rows) {
        var loaded = new Spot[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i).getAsJsonObject();
            var center = row.getAsJsonArray("center");
            var radius = row.getAsJsonArray("radius");
            loaded[i] = new Spot(center.get(0).getAsFloat(), center.get(1).getAsFloat(),
                    radius.get(0).getAsFloat(), radius.get(1).getAsFloat(),
                    row.has("phase") ? row.get("phase").getAsFloat() : 0f);
        }
        return loaded;
    }

    /** Changes on every resource reload, so cached sub-triangle patterns can be rebuilt. */
    public static int generation() { return generation; }
    public static String shard() { return shard; }
    public static float edgeShade() { return edgeShade; }

    /** Tint for the shared shard model.  Longest matching family wins, e.g. marisa_sparse -> marisa. */
    public static int[] tint(String model) {
        int[] exact = tints.get(model);
        if (exact != null) return exact;
        String best = null;
        for (String key : tints.keySet())
            if (!key.equals("fallback") && model.startsWith(key) && (best == null || key.length() > best.length())) best = key;
        if (best != null) return tints.get(best);
        int[] fallback = tints.get("fallback");
        return fallback == null ? new int[]{196, 190, 180} : fallback;
    }

    /**
     * How many cells per edge this source triangle needs so a removed tile stays chunky.
     * Positions are the authoring 16-units-per-block values, the same space as the recipe.
     */
    public static int subdivisions(float[] v, int first) {
        float ax = v[first * 8], ay = v[first * 8 + 1], az = v[first * 8 + 2];
        float bx = v[(first + 1) * 8], by = v[(first + 1) * 8 + 1], bz = v[(first + 1) * 8 + 2];
        float cx = v[(first + 2) * 8], cy = v[(first + 2) * 8 + 1], cz = v[(first + 2) * 8 + 2];
        float ab = (ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz);
        float bc = (bx - cx) * (bx - cx) + (by - cy) * (by - cy) + (bz - cz) * (bz - cz);
        float ca = (cx - ax) * (cx - ax) + (cy - ay) * (cy - ay) + (cz - az) * (cz - az);
        int n = Math.round((float) Math.sqrt(Math.max(ab, Math.max(bc, ca))) / targetEdge);
        return Math.max(minSubdivision, Math.min(maxSubdivision, n));
    }

    /** Cell count for a face edge, so every removed square stays chunky in MC's pixel scale. */
    public static int cells(float length) {
        int n = Math.round(length / targetEdge);
        return Math.max(minSubdivision, Math.min(maxSubdivision, n));
    }

    /**
     * HIDDEN omits a cell, EDGE darkens a cell next to a bite (or a pieced seam), DRAW keeps it.
     * {@code nx,ny,nz} are the cell centroid in the accessory's own bounding box (0..1).
     */
    public static byte code(int level, String part, float nx, float ny, float nz) {
        if (level <= 0) return DRAW;
        if (level >= 3) {
            for (float[] seam : seams)
                if (seam.length >= 5 && Math.abs(seam[0] * nx + seam[1] * ny + seam[2] * nz - seam[3]) < seam[4]) return EDGE;
            return DRAW;
        }
        float scale = isTrim(part) ? trimScale : 1f;
        if (part.contains("crescent")) scale *= .45f;
        Spot[] spots = level == 1 ? spots1 : spots2;
        for (Spot spot : spots) {
            float dx = (nx - spot.cx) / (spot.rx * scale), dy = (ny - spot.cy) / (spot.ry * scale);
            float radius = (float) Math.sqrt(dx * dx + dy * dy);
            float angle = (float) Math.atan2(dy, dx);
            float q = (float) (Math.floor((angle + Math.PI) / .24f) * .24f);
            float wave = .16f * (float) Math.sin(7f * q + spot.phase) + .10f * (float) Math.sin(4f * q + spot.phase * 1.7f);
            wave += .05f * (cellNoise(nx * 42f, ny * 42f, nz * 42f, seed) - .5f);
            if (radius < 1f + wave) return HIDDEN;
            if (radius < 1f + wave + edgeBand) return EDGE;
        }
        return DRAW;
    }

    private static boolean isTrim(String part) {
        for (String key : TRIM) if (part.contains(key)) return true;
        return false;
    }

    /** Deterministic cell hash, matching the art preview's chunky bite edges. */
    private static float cellNoise(float x, float y, float z, int salt) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        int h = ix * 374761393 + iy * 668265263 + iz * 2147483647 + salt * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0x7fffffff) / (float) 0x7fffffff;
    }

    /** Bounds (min x,y,z, max x,y,z) of the given triangles in the eight-float vertex layout. */
    public static float[] bounds(float[] v, int[] ranges) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int r = 0; r < ranges.length; r += 2) for (int i = ranges[r]; i < ranges[r] + ranges[r + 1]; i++)
            for (int a = 0; a < 3; a++) { b[a] = Math.min(b[a], v[i * 8 + a]); b[a + 3] = Math.max(b[a + 3], v[i * 8 + a]); }
        return b;
    }

    private HatDamageMask() {}
}
