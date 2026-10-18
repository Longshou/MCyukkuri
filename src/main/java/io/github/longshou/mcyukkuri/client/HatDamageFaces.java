package io.github.longshou.mcyukkuri.client;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.longshou.mcyukkuri.MCYukkuri;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Turns the approved accessory damage recipe into square face cells.
 *
 * <p>Each two-triangle box face becomes a regular grid of square-ish cells.  Front/back/top/bottom
 * faces use that grid; thin x-facing side faces stay whole.  A removed cell gets a close-colour
 * inner cap (green stays green, red stays red, black becomes a lighter grey-lavender, pale cloth
 * becomes a darker version of itself) and its neighbour gets a same-hue rim.  This helper is
 * shared by the worn hat (YukkuriModel) and detached accessory items (PropModels).
 */
final class HatDamageFaces {
    static final ResourceLocation INNER_TEXTURE = MCYukkuri.id("textures/props/hat_damage_inner.png");
    private static final byte[] NO_CODES = new byte[0];
    private static final int[] NO_COLORS = new int[0];

    /** {@code corners == null} means draw the original triangle pair; otherwise draw the grid. */
    record Face(int originalStart, int originalCount, int[] corners, int n, int m,
                byte[] codes, int[] cap, int[] rim, boolean flip) {
        boolean original() { return corners == null; }
    }

    /** Small RGBA sampler copied out of a NativeImage so the render thread never touches the image. */
    record Sampler(int width, int height, int[] pixels) {
        int[] sample(float u, float v) {
            if (width <= 0 || height <= 0) return new int[]{200, 200, 200};
            int x = Math.max(0, Math.min(width - 1, (int) (Math.max(0f, Math.min(.9999f, u)) * width)));
            int y = Math.max(0, Math.min(height - 1, (int) (Math.max(0f, Math.min(.9999f, v)) * height)));
            int p = pixels[y * width + x];
            if (((p >>> 24) & 255) < 20) return new int[]{200, 200, 200};
            return new int[]{p & 255, (p >>> 8) & 255, (p >>> 16) & 255};
        }
    }

    static Sampler load(ResourceManager resources, ResourceLocation texture) throws IOException {
        try (var stream = resources.getResourceOrThrow(texture).open(); var image = NativeImage.read(stream)) {
            int w = image.getWidth(), h = image.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) pixels[y * w + x] = image.getPixelRGBA(x, y);
            return new Sampler(w, h, pixels);
        }
    }

    static Face[] build(float[] v, int start, int count, String part, float[] bounds, int level, Sampler sampler) {
        List<Face> faces = new ArrayList<>();
        int t = 0;
        while (t < count) {
            int first = start + t;
            int[] quad = t + 6 <= count ? quad(v, first) : null;
            if (quad != null && !isSide(v, first)) {
                faces.add(grid(v, first, quad, part, bounds, level, sampler));
                t += 6;
                continue;
            }
            int originalCount = Math.min(6, count - t);
            faces.add(new Face(first, originalCount, null, 0, 0, NO_CODES, NO_COLORS, NO_COLORS, false));
            t += 6;
        }
        return faces.toArray(Face[]::new);
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

    private static Face grid(float[] v, int first, int[] corners, String part, float[] bounds, int level, Sampler sampler) {
        float[] p0 = position(v, corners[0]), p1 = position(v, corners[1]);
        float[] p2 = position(v, corners[2]), p3 = position(v, corners[3]);
        float[] uv0 = uv(v, corners[0]), uv1 = uv(v, corners[1]);
        float[] uv2 = uv(v, corners[2]), uv3 = uv(v, corners[3]);
        int n = HatDamageMask.cells(dist(p0, p1));
        int m = HatDamageMask.cells(dist(p0, p3));
        byte[] codes = new byte[n * m];
        int[] cap = new int[n * m];
        int[] rim = new int[n * m];
        float spanX = Math.max(1e-5f, bounds[3] - bounds[0]);
        float spanY = Math.max(1e-5f, bounds[4] - bounds[1]);
        float spanZ = Math.max(1e-5f, bounds[5] - bounds[2]);
        for (int i = 0; i < n; i++) for (int j = 0; j < m; j++) {
            float u = (i + .5f) / n, w = (j + .5f) / m;
            float x = bilerp(p0[0], p1[0], p2[0], p3[0], u, w);
            float y = bilerp(p0[1], p1[1], p2[1], p3[1], u, w);
            float z = bilerp(p0[2], p1[2], p2[2], p3[2], u, w);
            int cell = i * m + j;
            codes[cell] = HatDamageMask.code(level, part, (x - bounds[0]) / spanX, (y - bounds[1]) / spanY, (z - bounds[2]) / spanZ);
            float su = bilerp(uv0[0], uv1[0], uv2[0], uv3[0], u, w);
            float sv = bilerp(uv0[1], uv1[1], uv2[1], uv3[1], u, w);
            int[] c = sampler == null ? new int[]{200, 200, 200} : sampler.sample(su, sv);
            int[] colors = capRim(c[0], c[1], c[2]);
            cap[cell] = colors[0];
            rim[cell] = colors[1];
        }
        // Remove isolated interior cells left between two bites.
        byte[] original = codes.clone();
        for (int i = 1; i < n - 1; i++) for (int j = 1; j < m - 1; j++) {
            int cell = i * m + j;
            if (original[cell] == HatDamageMask.HIDDEN) continue;
            if (original[cell - m] == HatDamageMask.HIDDEN && original[cell + m] == HatDamageMask.HIDDEN
                    && original[cell - 1] == HatDamageMask.HIDDEN && original[cell + 1] == HatDamageMask.HIDDEN) codes[cell] = HatDamageMask.HIDDEN;
        }
        float ax = v[(first + 1) * 8] - v[first * 8], ay = v[(first + 1) * 8 + 1] - v[first * 8 + 1], az = v[(first + 1) * 8 + 2] - v[first * 8 + 2];
        float bx = v[(first + 2) * 8] - v[first * 8], by = v[(first + 2) * 8 + 1] - v[first * 8 + 1], bz = v[(first + 2) * 8 + 2] - v[first * 8 + 2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float ex = p1[0] - p0[0], ey = p1[1] - p0[1], ez = p1[2] - p0[2];
        float fx = p3[0] - p0[0], fy = p3[1] - p0[1], fz = p3[2] - p0[2];
        float cnx = ey * fz - ez * fy, cny = ez * fx - ex * fz, cnz = ex * fy - ey * fx;
        boolean flip = nx * cnx + ny * cny + nz * cnz < 0;
        return new Face(-1, 0, corners, n, m, codes, cap, rim, flip);
    }

    /** {packed cap, packed rim} using the local cloth colour. */
    private static int[] capRim(float r, float g, float b) {
        float luma = (r + g + b) / 3f;
        float cr, cg, cb, rr, rg, rb;
        if (luma < 70f) {
            cr = r * 2f; cg = g * 2f; cb = b * 2f;
            rr = r * 1.2f; rg = g * 1.2f; rb = b * 1.2f;
        } else if (luma < 170f) {
            cr = r * 1.25f; cg = g * 1.25f; cb = b * 1.25f;
            rr = r * .62f; rg = g * .62f; rb = b * .62f;
        } else {
            float mean = (r + g + b) / 3f;
            float tr = mean + (r - mean) * 2.2f, tg = mean + (g - mean) * 2.2f, tb = mean + (b - mean) * 2.2f;
            cr = tr * .78f; cg = tg * .78f; cb = tb * .78f;
            rr = tr * .55f; rg = tg * .55f; rb = tb * .55f;
        }
        return new int[]{pack(cr, cg, cb), pack(rr, rg, rb)};
    }

    private static int pack(float r, float g, float b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(float value) { return Math.max(0, Math.min(255, Math.round(value))); }
    private static float[] position(float[] v, int index) {
        return new float[]{v[index * 8], v[index * 8 + 1], v[index * 8 + 2]};
    }
    private static float[] uv(float[] v, int index) { return new float[]{v[index * 8 + 3], v[index * 8 + 4]}; }
    private static float dist(float[] a, float[] b) {
        float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
    private static float bilerp(float a, float b, float c, float d, float u, float v) {
        return a + (b - a) * u + (d - a) * v + (a - b + c - d) * u * v;
    }

    private HatDamageFaces() {}
}
