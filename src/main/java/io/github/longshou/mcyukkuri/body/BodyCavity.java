package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.joml.Vector3f;

/** The authored opening shared by skin clipping and accepted-hit core vulnerability. */
public final class BodyCavity {
    public record Profile(float[] center, float[] coreRadius, float[] openingRadius,
                          float referenceDepth, float[][] outline, float[][] levels) {}
    private static final Profile PROFILE = load();
    private static final Map<WoundSurface, Map<BodyWound, Opening>> CACHE = new WeakHashMap<>();
    private BodyCavity() {}

    private static Profile load() {
        var input = BodyCavity.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/body_cavity.json");
        if (input == null) throw new IllegalStateException("Missing authored body cavity");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            var levels = data.getAsJsonArray("levels");
            float[][] loft = new float[levels.size()][6];
            for (int i = 0; i < loft.length; i++) {
                var row = levels.get(i).getAsJsonArray();
                for (int j = 0; j < 3; j++) { loft[i][j] = row.get(j).getAsFloat(); loft[i][j + 3] = row.get(3).getAsJsonArray().get(j).getAsFloat(); }
            }
            var outline = data.getAsJsonArray("outline");
            float[][] ring = new float[outline.size()][];
            for (int i = 0; i < ring.length; i++) ring[i] = floats(outline.get(i).getAsJsonArray());
            return new Profile(floats(data.getAsJsonArray("center")), floats(data.getAsJsonArray("core_radius")),
                    floats(data.getAsJsonArray("opening_radius")), data.get("reference_depth").getAsFloat(), ring, loft);
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static float[] floats(JsonArray values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i).getAsFloat();
        return result;
    }
    public static Profile profile() { return PROFILE; }
    public static synchronized void clear() { CACHE.clear(); }

    /** Bounded immutable geometry cache; no cache or extra wound state is saved. */
    public static synchronized Opening opening(WoundSurface mesh, BodyWound wound) {
        if (mesh == null || !mesh.valid(wound) || !BodySigns.deep(wound)) return null;
        var entries = CACHE.computeIfAbsent(mesh, ignored -> new LinkedHashMap<>(128, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<BodyWound, Opening> entry) { return size() > 128; }
        });
        if (entries.containsKey(wound)) return entries.get(wound);
        var result = fit(mesh, wound);
        entries.put(wound, result);
        return result;
    }

    private static Opening fit(WoundSurface mesh, BodyWound wound) {
        var anchor = mesh.anchor(wound);
        var normal = mesh.normal(wound.triangle());
        if (!normal.isFinite()) return null;
        var u = WoundSurface.tangent(normal);
        var v = new Vector3f(normal).cross(u);
        var bounds = mesh.bounds();
        var span = new Vector3f(bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2]);
        var center = new Vector3f(bounds[0], bounds[1], bounds[2]).add(new Vector3f(PROFILE.center[0], PROFILE.center[1], PROFILE.center[2]).mul(span));
        float radiusU = new Vector3f(u).absolute().dot(span) * PROFILE.openingRadius[0];
        float radiusV = new Vector3f(v).absolute().dot(span) * PROFILE.openingRadius[1];
        float depth = new Vector3f(anchor).sub(center).dot(normal);
        if (depth <= .01f) return null;
        float range = span.length() * 2;
        for (int attempt = 0; attempt < 12; attempt++) {
            var rim = new WoundSurface.Hit[PROFILE.outline.length];
            boolean fitted = true;
            for (int i = 0; i < rim.length; i++) {
                var point = new Vector3f(anchor).fma(PROFILE.outline[i][0] * radiusU, u).fma(PROFILE.outline[i][1] * radiusV, v);
                var hit = mesh.ray(point.fma(range, normal), new Vector3f(normal).negate(), null, List.of());
                if (hit == null || new Vector3f(mesh.anchor(hit)).sub(center).dot(normal) <= depth * .06f) { fitted = false; break; }
                rim[i] = hit;
            }
            if (fitted) return new Opening(anchor, normal, u, v, center, span, radiusU, radiusV, depth / PROFILE.referenceDepth, rim);
            // Keep the actual injury centre. A bounded fit reduces only an outline that runs off this body.
            radiusU *= .86f; radiusV *= .86f;
        }
        return null;
    }

    public static final class Opening {
        public final Vector3f anchor, normal, u, v, center, span;
        public final float radiusU, radiusV, depthScale;
        public final WoundSurface.Hit[] rim;
        public final float[][] polygon;
        private Opening(Vector3f anchor, Vector3f normal, Vector3f u, Vector3f v, Vector3f center, Vector3f span,
                        float radiusU, float radiusV, float depthScale, WoundSurface.Hit[] rim) {
            this.anchor = anchor; this.normal = normal; this.u = u; this.v = v; this.center = center; this.span = span;
            this.radiusU = radiusU; this.radiusV = radiusV; this.depthScale = depthScale; this.rim = rim;
            polygon = new float[rim.length][2];
            for (int i = 0; i < rim.length; i++) { polygon[i][0] = PROFILE.outline[i][0] * radiusU; polygon[i][1] = PROFILE.outline[i][1] * radiusV; }
        }
        public float x(Vector3f point) { return new Vector3f(point).sub(anchor).dot(u); }
        public float y(Vector3f point) { return new Vector3f(point).sub(anchor).dot(v); }
        public boolean front(Vector3f point) { return new Vector3f(point).sub(center).dot(normal) > .25f * depthScale; }
        public boolean contains(Vector3f point) { return front(point) && contains(x(point), y(point)); }
        public boolean contains(float x, float y) {
            boolean inside = false;
            for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
                var a = polygon[i]; var b = polygon[j];
                if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
            }
            return inside;
        }
    }
}
