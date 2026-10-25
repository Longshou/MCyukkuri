package io.github.longshou.mcyukkuri.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.GZIPInputStream;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.LiveBirthGeometry;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** The authored opening shared by skin clipping and accepted-hit core vulnerability. */
public final class BodyCavity {
    public record Profile(float[] center, float[] coreRadius, float[] openingRadius,
                          float referenceDepth, float[][] outline, float[][] levels, float[] referenceBounds) {}
    private static final Profile PROFILE = load();
    /** The fit reads only these wound fields; leak clocks and peak severity change often and must not miss. */
    private record OpeningKey(int triangle, float u, float v, float size, WoundKind kind, WoundShape shape, int severity) {
        OpeningKey(BodyWound w) { this(w.triangle(), w.u(), w.v(), w.size(), w.kind(), w.shape(), w.severity()); }
    }
    private static final Map<WoundSurface, Map<OpeningKey, Opening>> CACHE = new WeakHashMap<>();
    public record CoreMesh(float[] vertices,float[] colors,float[] brokenColors) {}
    private static CoreMesh coreMesh;
    private BodyCavity() {}

    private static Profile load() {
        var input = BodyCavity.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/body_cavity.json");
        if (input == null) throw new IllegalStateException("Missing authored body cavity");
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            var data = JsonParser.parseReader(reader).getAsJsonObject();
            float[][] loft = WoundProfiles.coreLevels();
            var outline = data.getAsJsonArray("outline");
            float[][] ring = new float[outline.size()][];
            for (int i = 0; i < ring.length; i++) ring[i] = floats(outline.get(i).getAsJsonArray());
            return new Profile(floats(data.getAsJsonArray("center")), floats(data.getAsJsonArray("core_radius")),
                    floats(data.getAsJsonArray("opening_radius")), WoundProfiles.coreReferenceDepth(), ring, loft,
                    floats(data.getAsJsonArray("reference_bounds")));
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    private static float[] floats(JsonArray values) {
        float[] result = new float[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i).getAsFloat();
        return result;
    }
    public static Profile profile() { return PROFILE; }
    public static synchronized void clear() { CACHE.clear(); coreMesh=null; WoundProfiles.clear(); }
    public static synchronized CoreMesh coreMesh() {
        if(coreMesh!=null)return coreMesh;
        var stream=BodyCavity.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/body_core.json.gz");
        if(stream==null)throw new IllegalStateException("Missing authored central core");
        try(var reader=new InputStreamReader(new GZIPInputStream(stream),StandardCharsets.UTF_8)){
            var data=JsonParser.parseReader(reader).getAsJsonObject();
            return coreMesh=new CoreMesh(floats(data.getAsJsonArray("vertices")),floats(data.getAsJsonArray("colours")),floats(data.getAsJsonArray("broken_colours")));
        }catch(IOException ex){throw new UncheckedIOException(ex);}
    }

    /** These same vertices and skin weights are consumed by the cavity renderer and precise source-ray picking. */
    public static Vector3f restRim(WoundSurface mesh,WoundSurface.Hit hit,List<BodyWound> wounds) {
        return mesh.deformDisplay(mesh.rest(hit.triangle()),wounds).mul(1-hit.u()-hit.v())
                .fma(hit.u(),mesh.deformDisplay(mesh.rest(hit.triangle()+1),wounds)).fma(hit.v(),mesh.deformDisplay(mesh.rest(hit.triangle()+2),wounds));
    }
    public static Vector3f wallRest(Opening opening,Vector3f rim,float[] level) {
        var lateral=new Vector3f(opening.center).sub(opening.anchor);lateral.fma(-lateral.dot(opening.normal),opening.normal);
        var delta=new Vector3f(rim).sub(opening.anchor);
        return new Vector3f(opening.anchor).fma(level[2],lateral)
                .fma(delta.dot(opening.u)*level[0],opening.u).fma(delta.dot(opening.v)*level[0],opening.v)
                .fma(delta.dot(opening.normal)-level[1]*opening.depthScale,opening.normal);
    }
    public static float[] rimWeights(WoundSurface mesh,WoundSurface.Hit hit) {
        var combined=new HashMap<Integer,Float>();float[] factors={1-hit.u()-hit.v(),hit.u(),hit.v()};
        for(int j=0;j<3;j++){var weights=mesh.weights[hit.triangle()+j];for(int k=0;k<weights.length;k+=2)
            combined.merge((int)weights[k],weights[k+1]*factors[j],Float::sum);}
        float[] result=new float[combined.size()*2];int i=0;
        for(var entry:combined.entrySet()){result[i++]=entry.getKey();result[i++]=entry.getValue();}return result;
    }
    public static Vector3f poseWall(Vector3f rest,Vector3f restRim,float[] weights,float deep,Vector3f posedRim,
                                   Matrix4f[] world,int tissue,float pregnancy,Vector3f out,Vector3f offset,Vector3f temp) {
        out.set(posedRim);offset.set(rest).sub(restRim);
        for(int k=0;k<weights.length;k+=2){world[(int)weights[k]].transformDirection(offset,temp);out.fma(weights[k+1],temp);}
        if(deep>0){world[tissue].transformPosition(rest,temp);if(pregnancy>.001f)LiveBirthGeometry.deform(temp,pregnancy,0);out.lerp(temp,deep);}
        return out;
    }
    public static Vector3f coreRest(Opening opening,float[] source,int index) {
        return new Vector3f(source[index],source[index+1],source[index+2]).mul(opening.span).add(opening.center);
    }
    public static Vec3 coreWorld(YukkuriEntity entity,Opening opening) {
        var mesh=WoundSurface.get(entity.subspecies().assetId());
        var point=WoundPlacement.pose(entity)[mesh.tissueBone()].transformPosition(new Vector3f(opening.center));
        if(entity.livePregnancyProgress()>.001f)LiveBirthGeometry.deform(point,entity.livePregnancyProgress(),0);
        WoundPlacement.transform(entity).transformPosition(point);return entity.position().add(new Vec3(point));
    }

    /** Per user decision the core hit zone is forgiving: the opening outline scaled about its centre by this factor. */
    public static final float CORE_HIT_ZONE_SCALE=1.5f;

    /** An aimed hit whose skin surface point lies in the widened opening counts; no ray, hair/hat or wall test applies. */
    public static boolean hitsCore(YukkuriEntity entity,BodyWound wound,WoundSurface.Hit skinHit,DamageSource source) {
        var mesh=WoundSurface.get(entity.subspecies().assetId());var opening=opening(mesh,wound);
        if(opening==null)return false;
        var point=mesh.anchor(skinHit);
        return opening.front(point)&&opening.contains(opening.x(point)/CORE_HIT_ZONE_SCALE,opening.y(point)/CORE_HIT_ZONE_SCALE);
    }
    /** Both sides are solid, matching the non-culling cavity material. */
    static float intersection(Vector3f origin,Vector3f direction,Vector3f a,Vector3f b,Vector3f c) {
        var ab=new Vector3f(b).sub(a);var ac=new Vector3f(c).sub(a);var h=new Vector3f(direction).cross(ac);float determinant=ab.dot(h);
        if(Math.abs(determinant)<1e-8f)return Float.POSITIVE_INFINITY;
        float inverse=1/determinant;var s=new Vector3f(origin).sub(a);float u=s.dot(h)*inverse;
        if(u<0||u>1)return Float.POSITIVE_INFINITY;
        var q=s.cross(ab);float v=direction.dot(q)*inverse;if(v<0||u+v>1)return Float.POSITIVE_INFINITY;
        float distance=ac.dot(q)*inverse;return distance>=0?distance:Float.POSITIVE_INFINITY;
    }

    /** Bounded immutable geometry cache; no cache or extra wound state is saved. */
    public static synchronized Opening opening(WoundSurface mesh, BodyWound wound) {
        if (mesh == null || mesh.assetId.startsWith("house_") || !mesh.valid(wound)
                || !BodySigns.deep(wound) || !wound.shape().supportsCore()) return null;
        var entries = CACHE.computeIfAbsent(mesh, ignored -> new LinkedHashMap<>(128, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<OpeningKey, Opening> entry) { return size() > 128; }
        });
        var key = new OpeningKey(wound);
        if (entries.containsKey(key)) return entries.get(key);
        var result = fit(mesh, wound);
        entries.put(key, result);
        return result;
    }

    private static Opening fit(WoundSurface mesh, BodyWound wound) {
        var fit=WoundProfiles.fit(mesh.assetId);var scale=fit.scale();
        var shape=WoundProfiles.grade(wound.shape(),3);
        var outline=shape.outline();
        var hairOutline=outline;
        // The exposed-core channel removes the same tissue from skin and covering
        // hair. Ordinary wounds keep their smaller, cause-specific hair outline.
        var radius=WoundProfiles.coreRadius(wound.shape());
        var anchor = mesh.anchor(wound);
        var normal = mesh.normal(wound.triangle());
        if (!normal.isFinite()) return null;
        var u = WoundSurface.tangent(normal);
        var v = new Vector3f(normal).cross(u);
        var bounds = PROFILE.referenceBounds;
        var span = new Vector3f(bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2]).mul(scale);
        var center = new Vector3f(fit.center());
        float openingScale=wound.shape()==WoundShape.BLUNT?.76f:1;
        float radiusU = radius[0]*openingScale*wound.size()*new Vector3f(u).mul(scale).length();
        float radiusV = radius[1]*openingScale*wound.size()*new Vector3f(v).mul(scale).length()*WoundProfiles.verticalScale(mesh.assetId,normal);
        float depth = new Vector3f(anchor).sub(center).dot(normal);
        if (depth <= .01f) return null;
        float range = span.length() * 2;
        for (int attempt = 0; attempt < 12; attempt++) {
            var rim = new WoundSurface.Hit[outline.length];
            boolean fitted = true;
            for (int i = 0; i < rim.length; i++) {
                var point = new Vector3f(anchor).fma(outline[i][0] * radiusU, u).fma(outline[i][1] * radiusV, v);
                var hit = mesh.ray(point.fma(range, normal), new Vector3f(normal).negate(), null, List.of());
                if (hit == null || new Vector3f(mesh.anchor(hit)).sub(center).dot(normal) <= depth * .06f) { fitted = false; break; }
                rim[i] = hit;
            }
            if (fitted) return new Opening(anchor, normal, u, v, center, span, radiusU, radiusV, depth / PROFILE.referenceDepth,
                    2.5f*new Vector3f(normal).mul(scale).length(),rim,outline,hairOutline);
            // Keep the actual injury centre. A bounded fit reduces only an outline that runs off this body.
            radiusU *= .86f; radiusV *= .86f;
        }
        return null;
    }

    public static final class Opening {
        public final Vector3f anchor, normal, u, v, center, span;
        public final float radiusU, radiusV, depthScale, projectionDepth;
        public final WoundSurface.Hit[] rim;
        public final float[][] polygon, hairPolygon;
        private Opening(Vector3f anchor, Vector3f normal, Vector3f u, Vector3f v, Vector3f center, Vector3f span,
                        float radiusU, float radiusV, float depthScale,float projectionDepth,WoundSurface.Hit[] rim,float[][] outline,float[][] hairOutline) {
            this.anchor = anchor; this.normal = normal; this.u = u; this.v = v; this.center = center; this.span = span;
            this.radiusU = radiusU; this.radiusV = radiusV; this.depthScale = depthScale; this.rim = rim;
            this.projectionDepth=projectionDepth;
            polygon = new float[outline.length][2];hairPolygon = new float[hairOutline.length][2];
            for (int i = 0; i < polygon.length; i++) { polygon[i][0] = outline[i][0] * radiusU; polygon[i][1] = outline[i][1] * radiusV; }
            for (int i = 0; i < hairPolygon.length; i++) { hairPolygon[i][0] = hairOutline[i][0] * radiusU; hairPolygon[i][1] = hairOutline[i][1] * radiusV; }
        }
        public float x(Vector3f point) { return new Vector3f(point).sub(anchor).dot(u); }
        public float y(Vector3f point) { return new Vector3f(point).sub(anchor).dot(v); }
        public boolean front(Vector3f point) { return new Vector3f(point).sub(center).dot(normal) > .25f * depthScale; }
        public boolean contains(Vector3f point) { return front(point) && contains(x(point), y(point)); }
        public boolean contains(float x, float y) {
            return contains(polygon,x,y);
        }
        public boolean containsHair(Vector3f point) {
            return front(point) && Math.abs(new Vector3f(point).sub(anchor).dot(normal))<projectionDepth && contains(hairPolygon,x(point),y(point));
        }
        private static boolean contains(float[][] polygon,float x,float y) {
            boolean inside = false;
            for (int i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
                var a = polygon[i]; var b = polygon[j];
                if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside;
            }
            return inside;
        }
    }
}
