package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3f;
import com.google.gson.JsonArray;

/** Pure headwear damage recipe, topology and cap geometry shared by server placement and rendering. */
public final class HatDamageGeometry {
    public static final byte HIDDEN=0, DRAW=1, EDGE=2;
    public static final float CAP_INSET=.16f;
    public record Spot(float cx, float cy, float rx, float ry, float phase) {}

    private static final String[] TRIM = {"hem", "edge", "lace", "band", "frill", "scallop", "crescent", "ribbon", "knot"};

    /** Immutable snapshot; array storage never escapes. Client reload and server loading own separate snapshots. */
    public static final class Pattern {
        private final int seed,minSubdivision,maxSubdivision;
        private final float targetEdge,edgeBand,edgeShade,trimScale;
        private final Spot[] spots1,spots2;
        private final float[][] seams;
        private Pattern(Builder b){
            seed=b.seed;targetEdge=Math.max(.05f,b.targetEdge);
            minSubdivision=Math.max(1,Math.min(b.minSubdivision,b.maxSubdivision));maxSubdivision=Math.max(minSubdivision,b.maxSubdivision);
            edgeBand=b.edgeBand;edgeShade=b.edgeShade;trimScale=b.trimScale;
            spots1=b.spots1.clone();spots2=b.spots2.clone();seams=copy(b.seams);
        }
        public float edgeShade(){return edgeShade;}
        public int subdivisions(float[] v, int first) {
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
        public int cells(float length) {
            int n = Math.round(length / targetEdge);
            return Math.max(minSubdivision, Math.min(maxSubdivision, n));
        }

        /**
         * HIDDEN omits a cell, EDGE darkens a cell next to a bite (or a pieced seam), DRAW keeps it.
         * {@code nx,ny,nz} are the cell centroid in the accessory's own bounding box (0..1).
         */
        public byte code(int level, String part, float nx, float ny, float nz) {
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

    }
    /** Partial parsing is deliberate: preserves the client's existing reload-error/default semantics. */
    public static final class Builder {
        private int seed = 24291;
        private float targetEdge = .65f;
        private int minSubdivision = 2, maxSubdivision = 5;
        private float edgeBand = .10f, edgeShade = .58f, trimScale = .55f;
        private Spot[] spots1 = {
                new Spot(.02f, .38f, .085f, .13f, .4f),
                new Spot(.98f, .38f, .085f, .13f, 1.7f),
                new Spot(.50f, .98f, .13f, .065f, 2.6f)};
        private Spot[] spots2 = {
                new Spot(.02f, .38f, .085f, .13f, .4f),
                new Spot(.98f, .38f, .085f, .13f, 1.7f),
                new Spot(.50f, .98f, .13f, .065f, 2.6f),
                new Spot(.55f, .02f, .14f, .065f, .9f),
                new Spot(.08f, .84f, .06f, .11f, 1.3f),
                new Spot(.92f, .84f, .06f, .11f, 2.1f)};
        private float[][] seams = {{.8f, .4f, 0f, .62f, .030f}, {.25f, -.9f, .1f, .18f, .030f}};

        public Builder(Pattern previous){if(previous!=null){spots1=previous.spots1.clone();spots2=previous.spots2.clone();seams=copy(previous.seams);}}
        public void read(JsonObject data){
            var nib = data.getAsJsonObject("nibble");
            if (nib != null) {
                seed = nib.has("seed") ? nib.get("seed").getAsInt() : seed;
                targetEdge = nib.has("target_edge") ? nib.get("target_edge").getAsFloat() : targetEdge;
                minSubdivision = nib.has("min_subdivision") ? nib.get("min_subdivision").getAsInt() : minSubdivision;
                maxSubdivision = nib.has("max_subdivision") ? nib.get("max_subdivision").getAsInt() : maxSubdivision;
                edgeBand = nib.has("edge_band") ? nib.get("edge_band").getAsFloat() : edgeBand;
                edgeShade = nib.has("edge_shade") ? nib.get("edge_shade").getAsFloat() : edgeShade;
                trimScale = nib.has("trim_scale") ? nib.get("trim_scale").getAsFloat() : trimScale;
                if (nib.has("spots")) {
                    var groups = nib.getAsJsonObject("spots");
                    if (groups.has("1")) spots1 = spots(groups.getAsJsonArray("1"));
                    if (groups.has("2")) spots2 = spots(groups.getAsJsonArray("2"));
                }
                if (nib.has("seams")) {
                    var rows = nib.getAsJsonArray("seams");
                    var loaded = new float[rows.size()][];
                    for (int i = 0; i < rows.size(); i++) {
                        var row = rows.get(i).getAsJsonArray();
                        loaded[i] = new float[row.size()];
                        for (int k = 0; k < row.size(); k++) loaded[i][k] = row.get(k).getAsFloat();
                    }
                    if (loaded.length > 0) seams = loaded;
                }
            }
        }
        public Pattern build(){return new Pattern(this);}
    }
    public static Pattern defaults(){return new Builder(null).build();}
    public static Pattern read(JsonObject data){var builder=new Builder(null);builder.read(data);return builder.build();}
    private static float[][] copy(float[][] rows){var out=new float[rows.length][];for(int i=0;i<rows.length;i++)out[i]=rows[i].clone();return out;}
    private static Spot[] spots(JsonArray rows) {
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

    public record Layout(int originalStart,int originalCount,int[] corners,int n,int m,byte[] codes,boolean flip){
        public Layout{corners=corners==null?null:corners.clone();codes=codes.clone();}
        @Override public int[] corners(){return corners==null?null:corners.clone();}
        @Override public byte[] codes(){return codes.clone();}
        public boolean original(){return corners==null;}
        public byte code(int i,int j){return codes[i*m+j];}
    }
    public static Layout[] layout(float[] v, int start, int count, String part, float[] bounds, int level, Pattern pattern) {
        List<Layout> faces = new ArrayList<>();
        int t = 0;
        while (t < count) {
            int first = start + t;
            int[] quad = t + 6 <= count ? quad(v, first) : null;
            if (quad != null && !isSide(v, first)) {
                faces.add(grid(v, first, quad, part, bounds, level, pattern));
                t += 6;
                continue;
            }
            int originalCount = Math.min(6, count - t);
            faces.add(new Layout(first, originalCount, null, 0, 0, new byte[0], false));
            t += 6;
        }
        return faces.toArray(Layout[]::new);
    }

    private static boolean isSide(float[] v, int first) {
        float ax = v[(first + 1) * 8] - v[first * 8];
        float ay = v[(first + 1) * 8 + 1] - v[first * 8 + 1];
        float az = v[(first + 1) * 8 + 2] - v[first * 8 + 2];
        float bx = v[(first + 2) * 8] - v[first * 8];
        float by = v[(first + 2) * 8 + 1] - v[first * 8 + 1];
        float bz = v[(first + 2) * 8 + 2] - v[first * 8 + 2];
        float nx = ay * bz - az * by;
        float length = (float) Math.sqrt(nx * nx + (az * bx - ax * bz) * (az * bx - ax * bz) + (ax * by - ay * bx) * (ax * by - ay * bx));
        return Math.abs(nx) / Math.max(1e-6f, length) > .5f;
    }

    /** Four perimeter corners (global vertex indices) of a two-triangle face, or null when it is not a clean quad. */
    private static int[] quad(float[] v, int first) {
        Map<String, Integer> unique = new HashMap<>();
        for (int k = 0; k < 6; k++) {
            int i = first + k;
            String key = Math.round(v[i * 8] * 1000f) + "," + Math.round(v[i * 8 + 1] * 1000f) + ","
                    + Math.round(v[i * 8 + 2] * 1000f) + "," + Math.round(v[i * 8 + 3] * 1000f) + ","
                    + Math.round(v[i * 8 + 4] * 1000f);
            unique.putIfAbsent(key, i);
        }
        if (unique.size() != 4) return null;
        Map<String, Integer> firstTriangle = new HashMap<>(), secondTriangle = new HashMap<>();
        for (int k = 0; k < 3; k++) firstTriangle.put(positionKey(v, first + k), first + k);
        for (int k = 3; k < 6; k++) secondTriangle.put(positionKey(v, first + k), first + k);
        List<Integer> shared = new ArrayList<>(), opposite = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            int index = first + k;
            String key = positionKey(v, index);
            if (secondTriangle.containsKey(key) && shared.stream().noneMatch(i -> positionKey(v, i).equals(key))) shared.add(index);
        }
        if (shared.size() != 2) return null;
        for (int index : unique.values()) {
            String key = positionKey(v, index);
            if (!key.equals(positionKey(v, shared.get(0))) && !key.equals(positionKey(v, shared.get(1)))) opposite.add(index);
        }
        if (opposite.size() != 2) return null;
        return new int[]{shared.get(0), opposite.get(0), shared.get(1), opposite.get(1)};
    }

    private static String positionKey(float[] v, int index) {
        return Math.round(v[index * 8] * 1000f) + "," + Math.round(v[index * 8 + 1] * 1000f) + ","
                + Math.round(v[index * 8 + 2] * 1000f);
    }

    private static Layout grid(float[] v, int first, int[] corners, String part, float[] bounds, int level, Pattern pattern) {
        float[] p0 = position(v, corners[0]), p1 = position(v, corners[1]);
        float[] p2 = position(v, corners[2]), p3 = position(v, corners[3]);
        int n = pattern.cells(dist(p0, p1));
        int m = pattern.cells(dist(p0, p3));
        byte[] codes = new byte[n * m];
        float spanX = Math.max(1e-5f, bounds[3] - bounds[0]);
        float spanY = Math.max(1e-5f, bounds[4] - bounds[1]);
        float spanZ = Math.max(1e-5f, bounds[5] - bounds[2]);
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            float u = (i + .5f) / n, w = (j + .5f) / m;
            float x = bilerp(p0[0], p1[0], p2[0], p3[0], u, w);
            float y = bilerp(p0[1], p1[1], p2[1], p3[1], u, w);
            float z = bilerp(p0[2], p1[2], p2[2], p3[2], u, w);
            int cell = i * m + j;
            codes[cell] = pattern.code(level, part, (x - bounds[0]) / spanX, (y - bounds[1]) / spanY, (z - bounds[2]) / spanZ);

        }
        // Remove isolated interior cells left between two bites.
        byte[] original = codes.clone();
        for (int i = 1; i < n - 1; i++) for (int j = 1; j < m - 1; j++) {
            int cell = i * m + j;
            if (original[cell] == HIDDEN) continue;
            if (original[cell - m] == HIDDEN && original[cell + m] == HIDDEN
                    && original[cell - 1] == HIDDEN && original[cell + 1] == HIDDEN) codes[cell] = HIDDEN;
        }
        float ax = v[(first + 1) * 8] - v[first * 8], ay = v[(first + 1) * 8 + 1] - v[first * 8 + 1], az = v[(first + 1) * 8 + 2] - v[first * 8 + 2];
        float bx = v[(first + 2) * 8] - v[first * 8], by = v[(first + 2) * 8 + 1] - v[first * 8 + 1], bz = v[(first + 2) * 8 + 2] - v[first * 8 + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float ex = p1[0] - p0[0], ey = p1[1] - p0[1], ez = p1[2] - p0[2];
        float fx = p3[0] - p0[0], fy = p3[1] - p0[1], fz = p3[2] - p0[2];
        float cnx = ey * fz - ez * fy, cny = ez * fx - ex * fz, cnz = ex * fy - ey * fx;
        boolean flip = nx * cnx + ny * cny + nz * cnz < 0;
        return new Layout(-1, 0, corners, n, m, codes, flip);
    }

    private static float[] position(float[] v, int index) {
        return new float[]{v[index * 8], v[index * 8 + 1], v[index * 8 + 2]};
    }
    private static float dist(float[] a, float[] b) {
        float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
    public static float bilerp(float a, float b, float c, float d, float u, float v) {
        return a + (b - a) * u + (d - a) * v + (a - b + c - d) * u * v;
    }

    /** Input corners have already been skinned and hat-scaled/fitted. Writes caller-owned scratch only. */
    public static boolean cell(Vector3f[] face,int n,int m,int i,int j,byte code,boolean flip,
                               Vector3f[] out,Vector3f normal,Vector3f temp){
        float u0=i/(float)n,u1=(i+1f)/n,v0=j/(float)m,v1=(j+1f)/m;
        point(out[0],face,u0,v0);point(out[1],face,u1,v0);point(out[2],face,u1,v1);point(out[3],face,u0,v1);
        normal.set(out[1]).sub(out[0]);temp.set(out[3]).sub(out[0]);normal.cross(temp);
        if(normal.lengthSquared()<1.0e-16f)return false;
        normal.normalize();if(flip)normal.negate();
        if(code==HIDDEN)for(var p:out)p.sub(normal.x*CAP_INSET,normal.y*CAP_INSET,normal.z*CAP_INSET);
        return true;
    }
    private static void point(Vector3f out,Vector3f[] p,float u,float v){
        out.set(bilerp(p[0].x,p[1].x,p[2].x,p[3].x,u,v),bilerp(p[0].y,p[1].y,p[2].y,p[3].y,u,v),bilerp(p[0].z,p[1].z,p[2].z,p[3].z,u,v));
    }
    private HatDamageGeometry() {}
}
