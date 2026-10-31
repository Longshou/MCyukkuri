package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.motor.geom.BodyFootprint;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Arrays;

/** Measured hull profiles, converted from blocks at the exported scale to unscaled hat units, keel at zero. */
public final class HullProfile {
    public static final String CURRENT = "turned_land_hat", BASIN = "cone_basin";
    private static final Map<String, HullProfile> FORMS = Map.of(
            CURRENT, new HullProfile("marisa_hat_turned"), BASIN, new HullProfile("aquatic_basin_hat_turned"));
    private final JsonObject data;
    private final double[] angles, brim, opening;
    private final double[][][] inner, outer;
    private final double unit, keel;

    private HullProfile(String hull) {
        data = read("yukkuri_presentation/hull_profiles/" + hull + ".json");
        unit = data.get("scale").getAsDouble() / 16;
        keel = data.getAsJsonObject("keel").get("y").getAsDouble();
        angles = numbers(data.getAsJsonArray("azimuths"));
        brim = numbers(data.getAsJsonObject("brim").getAsJsonArray("radius"));
        opening = numbers(data.getAsJsonObject("opening").getAsJsonArray("radius"));
        inner = sections(data.getAsJsonArray("inner")); outer = sections(data.getAsJsonArray("outer"));
    }
    public static HullProfile of(String id) {
        var profile = FORMS.get(id);
        if (profile == null) throw new IllegalArgumentException("Unknown hull form " + id);
        return profile;
    }
    public String id() { return data.get("form").getAsString(); }
    public String hull() { return data.get("hull").getAsString(); }
    /** Original signed, block-unit measurements for the shell buoyancy and stores consumers. */
    public JsonObject data() { return data; }
    public double measuredUnit() { return unit; }
    public double top() { return -keel / unit; }
    public double rimY() { return (data.getAsJsonObject("rim").get("y").getAsDouble() - keel) / unit; }
    public double floor() { return (data.getAsJsonObject("floor").get("y").getAsDouble() - keel) / unit; }
    public double squash() { return data.get("squash").getAsDouble(); }
    public double tolerance() { return data.get("level_tolerance").getAsDouble() / unit; }
    public double reach(double angle) { return radial(brim, angle) / unit; }
    public double mouth(double angle) { return radial(opening, angle) / unit; }
    public double floorRadius() {
        return Arrays.stream(numbers(data.getAsJsonObject("floor").getAsJsonArray("radius"))).max().orElseThrow()/unit;
    }
    public float[] brim() { return new float[]{(float)Math.max(reach(0), reach(Math.PI)), (float)Math.max(reach(Math.PI/2), reach(-Math.PI/2))}; }
    public float[] opening() { return new float[]{(float)-mouth(Math.PI), (float)mouth(0), (float)-mouth(-Math.PI/2), (float)mouth(Math.PI/2)}; }
    public boolean inOpening(double x, double z) { return Math.hypot(x, z) <= mouth(Math.atan2(z, x)); }

    /** Linear interpolation between measured rays and samples; repeated radii retain the vertical lip. */
    public double surface(double x, double z) {
        double a = Math.atan2(z, x), r = Math.hypot(x, z) * unit;
        if (r > radial(brim, a)) return Double.NaN;
        double sector = sector(a); int i = (int)sector, j = (i + 1) % angles.length;
        boolean hollow = r < radial(opening, a);
        var rays = hollow ? inner : outer;
        double u = r / radial(hollow ? opening : brim, a);
        double y0 = sample(rays[i], u * (hollow ? opening[i] : brim[i]), hollow ? 1 : 2);
        double y1 = sample(rays[j], u * (hollow ? opening[j] : brim[j]), hollow ? 1 : 2);
        return (y0 + (y1 - y0) * (sector - i) - keel) / unit;
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
    public double clearance(double x, double z) {
        if (Math.hypot(x, z) <= reach(Math.atan2(z, x))) return 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 64; i++) {
            double a = i * Math.PI / 32, b = (i + 1) * Math.PI / 32;
            double ax = Math.cos(a)*reach(a), az = Math.sin(a)*reach(a), dx = Math.cos(b)*reach(b)-ax, dz = Math.sin(b)*reach(b)-az;
            double t = Math.clamp(((x-ax)*dx+(z-az)*dz)/(dx*dx+dz*dz), 0, 1);
            best = Math.min(best, Math.hypot(x-ax-t*dx, z-az-t*dz));
        }
        return best;
    }
    public double clear(double dx, double dz, double radius) {
        double lo = 0, hi = Math.hypot(brim()[0], brim()[1]) + radius;
        for (int i = 0; i < 30; i++) { double mid = (lo+hi)/2; if (clearance(dx*mid,dz*mid)>=radius) hi=mid; else lo=mid; }
        return hi;
    }
    private double sector(double angle) {
        double deg = (Math.toDegrees(angle) + 360) % 360;
        int i = 0; while (i+1 < angles.length && angles[i+1] <= deg) i++;
        return i + (deg-angles[i])/((i+1 == angles.length ? 360 : angles[i+1])-angles[i]);
    }
    private double radial(double[] values, double angle) {
        double sector = sector(angle); int i = (int)sector;
        // Intersect the ray with the chord, preserving the current form's rectangular opening.
        double a = Math.toRadians(angles[i]), b = Math.toRadians(i+1 == angles.length ? 360 : angles[i+1]);
        double t = angle-a; t -= Math.floor(t/(2*Math.PI))*2*Math.PI;
        double ra=values[i], rb=values[(i+1)%values.length];
        if (ra == rb) return ra;
        return ra*rb*Math.sin(b-a)/(rb*Math.sin(b-a-t)+ra*Math.sin(t));
    }
    private static double sample(double[][] ray, double r, int column) {
        int i = 1; while (i < ray.length && ray[i][0] <= r) i++;
        if (i == ray.length) return ray[i-1][column];
        var a = ray[i-1]; var b = ray[i];
        return a[column] + (b[column]-a[column])*(r-a[0])/(b[0]-a[0]);
    }
    private static double[] numbers(JsonArray array) { double[] out=new double[array.size()]; for(int i=0;i<out.length;i++)out[i]=array.get(i).getAsDouble(); return out; }
    private static double[][][] sections(JsonArray array) {
        double[][][] out = new double[array.size()][][];
        for(int i=0;i<out.length;i++){var ray=array.get(i).getAsJsonArray();out[i]=new double[ray.size()][];for(int j=0;j<ray.size();j++)out[i][j]=numbers(ray.get(j).getAsJsonArray());}
        return out;
    }
    public static JsonObject read(String path) {
        var stream = HullProfile.class.getResourceAsStream("/assets/mcyukkuri/" + path);
        if (stream == null) throw new IllegalStateException("Missing hull geometry resource " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
