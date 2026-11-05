package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonObject;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.SurfaceTable;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.concurrent.ConcurrentHashMap;

/** One model surface, selected from its authored data; coordinates are hat units. */
public abstract class HullProfile {
    public static final String CURRENT = "turned_land_hat", BASIN = "cone_basin";
    private static final Map<String, String> FILES = Map.of(CURRENT, "hull_profiles/marisa_hat_turned", BASIN, "hull_bounds/aquatic_basin_hat_turned");
    private static final Map<String, HullProfile> FORMS = new ConcurrentHashMap<>();
    protected final JsonObject data;
    protected final double unit, keel;
    private final String id;

    protected HullProfile(String id, GeometryJson source, double keel) {
        this.id = id; data = source.data(); this.keel = keel;
        source.check(source.number("version") == 1, "version", "expected 1");
        unit = source.number("scale") / 16;
        source.check(unit > 0, "scale", "must be positive");
    }
    public static boolean known(String id) { return FILES.containsKey(id); }
    public static HullProfile of(String id) {
        if (!known(id)) throw new IllegalArgumentException("Unknown hull form " + id);
        return FORMS.computeIfAbsent(id, key -> {
            var source = GeometryJson.read("yukkuri_presentation/" + FILES.get(key) + ".json");
            return source.data().has("seating") ? new Round(key, source) : new Cradle(key, source);
        });
    }
    public HatSurface surface() { return HatSurface.LAND; }
    public boolean seated() { return false; }
    public boolean blocksWalking(double feetY, double rimY) { return true; }
    public String id() { return id; }
    public String hull() { return data.get("hull").getAsString(); }
    public JsonObject data() { return data; }
    public double measuredUnit() { return unit; }
    public double top() { return -keel / unit; }
    public double floor() { return (data.getAsJsonObject("floor").get("y").getAsDouble() - keel) / unit; }
    public abstract List<Vec> rimPoints();
    public abstract double rimY();
    public abstract double squash();
    public abstract double reach(double angle);
    public abstract double mouth(double angle);
    public abstract float[] wornBrim();
    public abstract double surface(double x, double z);
    public abstract double clearance(double x, double z);
    public float[] brim() { return new float[]{(float)Math.max(reach(0), reach(Math.PI)), (float)Math.max(reach(Math.PI/2), reach(-Math.PI/2))}; }
    public float[] opening() { return new float[]{(float)-mouth(Math.PI), (float)mouth(0), (float)-mouth(-Math.PI/2), (float)mouth(Math.PI/2)}; }
    public boolean inOpening(double x, double z) { return Math.sqrt(x * x + z * z) <= mouth(Math.atan2(z, x)); }
    /** A supported point in the hollow, including its sloping wall; the lap is only a preference. */
    public boolean resting(Vec at, BodyFootprint body, double margin) {
        return inOpening(at.x(), at.z()) && Math.abs(at.y() - support(at.x(), at.z(), body)) <= margin;
    }

    /** Whole body underside must lie over cloth; the highest surface-minus-underside fixes its resting height. */
    public double support(double x, double z, BodyFootprint body) {
        double height = Double.NEGATIVE_INFINITY;
        for (var p : body.columns()) {
            double y = surface(x + p.x(), z + p.z());
            if (!Double.isFinite(y)) return Double.NaN;
            height = Math.max(height, y - p.bottom());
        }
        return height;
    }
    /** Flat circular sweep used by birth trajectories and solid objects. */
    public double footprint(double x, double z, double r) {
        double height = surface(x, z);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, y = surface(x + r * Math.cos(a), z + r * Math.sin(a));
            if (Double.isFinite(y)) height = Double.isNaN(height) ? y : Math.max(height, y);
        }
        return height;
    }
    public double clear(double dx, double dz, double radius) {
        double lo = 0, hi = Math.hypot(brim()[0], brim()[1]) + radius;
        for (int i = 0; i < 30; i++) { double mid = (lo+hi)/2; if (clearance(dx*mid,dz*mid)>=radius) hi=mid; else lo=mid; }
        return hi;
    }
    /** Authored clip offset corrected along its bearing by the change in the hull's reach. */
    public Vec placementOffset(double x,double z,double yaw) {
        double length=Math.hypot(x,z),a=Math.toRadians(yaw-180),c=Math.cos(a),s=Math.sin(a);
        double bearing=Math.atan2(-z*c+x*s,-x*c-z*s),delta=reach(bearing)-of(CURRENT).reach(bearing);
        return new Vec(x*(1+delta/length),0,z*(1+delta/length));
    }
    public double standDistance(double dx,double dz,double radius,double least) { return Math.max(least,clear(dx,dz,radius)); }

    private static final class Round extends HullProfile {
        @Override public HatSurface surface() { return HullSurface.INSTANCE; }
        @Override public boolean blocksWalking(double feetY, double rimY) { return !(rimY <= feetY); }
        private final List<Vec> rim;
        Round(String id, GeometryJson source) {
            super(id, source, 0);
            rim = IntStream.range(0, 24).mapToObj(i -> new Vec(Math.cos(i * Math.PI / 12) * reach(0),
                    HullBounds.EDGE / unit, Math.sin(i * Math.PI / 12) * reach(0))).toList();
        }
        @Override public double support(double x, double z, BodyFootprint body) {
            if (!Double.isFinite(surface(x, z))) return Double.NaN;
            double height = Double.NEGATIVE_INFINITY;
            for (var p : body.columns()) {
                double y = surface(x + p.x(), z + p.z());
                if (Double.isFinite(y)) height = Math.max(height, y - p.bottom());
            }
            return height;
        }
        @Override public boolean seated() { return true; }
        @Override public List<Vec> rimPoints() { return rim; }
        @Override public double rimY() { return HullBounds.BRIM / unit; }
        @Override public double squash() { return 1; }
        @Override public double reach(double angle) { return HullBounds.RADIUS / unit; }
        @Override public double mouth(double angle) { return HullBounds.MOUTH / unit; }
        @Override public float[] wornBrim() { return brim(); }
        // sqrt, not Math.hypot: these run per footprint column per floor sample, and hypot's overflow care costs ~10x here.
        @Override public double surface(double x, double z) { return HullBounds.surface(Math.sqrt(x * x + z * z) * unit) / unit; }
        @Override public double clearance(double x, double z) { return Math.max(0, Math.sqrt(x * x + z * z) - reach(0)); }
    }

    private static final class Cradle extends HullProfile {
        private final double[] angles, brim, opening;
        private final float[] worn;
        private final SurfaceTable surface;
        private final List<Vec> outline, rim;
        private Cradle(String id, GeometryJson source) {
            super(id, source, source.number("keel.y"));
            source.check(source.string("form").equals(id), "form", "expected " + id);
            for (var key : List.of("squash", "rim.y", "floor.y")) source.number(key);
            angles = numbers(source, "azimuths"); brim = numbers(source, "brim.radius"); opening = numbers(source, "opening.radius");
            source.check(angles.length >= 8 && angles[0] == 0, "azimuths", "must start at zero with at least eight rays");
            source.check(brim.length == angles.length && opening.length == angles.length, "brim/opening.radius", "ray count mismatch");
            var points = new ArrayList<Vec>();
            for (int i=0;i<angles.length;i++) {
                source.check(angles[i]<360 && (i==0 || angles[i]>angles[i-1]), "azimuths["+i+"]", "must increase below 360");
                source.check(brim[i]>=opening[i] && opening[i]>0, "brim/opening.radius["+i+"]", "invalid nesting");
                double a=Math.toRadians(angles[i]);points.add(new Vec(Math.cos(a)*brim[i]/unit,0,Math.sin(a)*brim[i]/unit));
            }
            outline=List.copyOf(points);
            var wornBrim=numbers(source,"worn.brim");
            source.check(wornBrim.length==2&&wornBrim[0]>0&&wornBrim[1]>0,"worn.brim","expected two positive half extents");
            worn=new float[]{(float)(wornBrim[0]/unit),(float)(wornBrim[1]/unit)};
            surface=new SurfaceTable(source, "surface_triangles", unit, keel);
            points.clear();
            for(int i=0;i<24;i++)points.add(surface.rim(i*Math.PI/12));
            rim=List.copyOf(points);
        }
        @Override public List<Vec> rimPoints() { return rim; }
        @Override public double rimY() { return (data.getAsJsonObject("rim").get("y").getAsDouble() - keel) / unit; }
        @Override public double squash() { return data.get("squash").getAsDouble(); }
        @Override public double reach(double angle) { return radial(brim, angle) / unit; }
        @Override public double mouth(double angle) { return radial(opening, angle) / unit; }
        @Override public float[] wornBrim() { return worn.clone(); }
        @Override public double surface(double x, double z) { return surface.height(x,z); }
        public double clearance(double x, double z) {
            double best=Double.POSITIVE_INFINITY;boolean inside=false;
            for(int i=0,j=outline.size()-1;i<outline.size();j=i++){
                var a=outline.get(i);var b=outline.get(j);
                if((a.z()>z)!=(b.z()>z)&&x<(b.x()-a.x())*(z-a.z())/(b.z()-a.z())+a.x())inside=!inside;
                double dx=b.x()-a.x(),dz=b.z()-a.z(),t=Math.clamp(((x-a.x())*dx+(z-a.z())*dz)/(dx*dx+dz*dz),0,1);
                best=Math.min(best,Math.hypot(x-a.x()-t*dx,z-a.z()-t*dz));
            }
            return inside?0:best;
        }
        private double sector(double angle) {
            double deg = (Math.toDegrees(angle) % 360 + 360) % 360;
            int found=Arrays.binarySearch(angles,deg),i=found>=0?found:-found-2;
            return i + (deg-angles[i])/((i+1 == angles.length ? 360 : angles[i+1])-angles[i]);
        }
        private double radial(double[] values, double angle) {
            double sector = sector(angle); int i = (int)sector;
            // Intersect the ray with the chord, preserving the current form's rectangular opening.
            double a = Math.toRadians(angles[i]), b = Math.toRadians(i+1 == angles.length ? 360 : angles[i+1]);
            double t = angle-a; t -= Math.floor(t/(2*Math.PI))*2*Math.PI;
            double ra=values[i], rb=values[(i+1)%values.length];
            return ra*rb*Math.sin(b-a)/(rb*Math.sin(b-a-t)+ra*Math.sin(t));
        }
        private static double[] numbers(GeometryJson source,String field) {
            var a=source.field(field);source.check(a.isJsonArray(),field,"expected array");
            double[] out=new double[a.getAsJsonArray().size()];
            for(int i=0;i<out.length;i++){var v=a.getAsJsonArray().get(i);source.check(v.isJsonPrimitive()&&v.getAsJsonPrimitive().isNumber()&&Double.isFinite(v.getAsDouble()),field+"["+i+"]","expected finite number");out[i]=v.getAsDouble();}
            return out;
        }
    }
}
