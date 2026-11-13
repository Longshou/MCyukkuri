package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.joml.Vector3f;

/**
 * The space a body takes with what it wears, measured on the geometry the client draws, at rest pose:
 * <ul><li>its own hat or ribbon: the worn accessory export (feet at zero), its rise above the crown grown with the hat's own
 * scale ({@link HatScale}, which carries the aquatic hat's multiplier);</li>
 * <li>junk headwear: the junk prop placed by its authored seat for this form ({@link WornSeat}, the same matrix YukkuriModel
 * applies on the features frame, which is the model frame at rest), grown with the body only.</li></ul>
 * Shelters admit a body only when this fits their interior.
 */
public final class HeadTop {
    /** Model height of every selected body export (body_geometry.json); accessory exports share its feet origin. */
    private static final double BODY_UNITS = 9.6;
    private static final Map<String, JsonObject> PROPS = new ConcurrentHashMap<>();
    private static final Map<String, double[]> EXTENT = new ConcurrentHashMap<>();

    /** Blocks from the feet to the highest worn point. */
    public static double height(YukkuriEntity y) {
        var worn = worn(y);
        return worn == null ? y.getBbHeight() : Math.max(y.getBbHeight(), worn[1]);
    }

    /** Blocks across the widest of the body and its headwear. */
    public static double width(YukkuriEntity y) {
        var worn = worn(y);
        return worn == null ? y.getBbWidth() : Math.max(y.getBbWidth(), worn[0]);
    }

    /** Width and top (blocks above the feet) of the worn piece on this body, or null for a bare head. */
    private static double[] worn(YukkuriEntity y) {
        double body = y.growthScale() * y.getScale();
        String asset = SubspeciesLife.assetId(y.getVariant(), y.subspeciesFlags());
        var junk = y.life().custody().junk();
        if (junk != null) {
            var e = EXTENT.computeIfAbsent("junk_" + junk.id() + "@" + asset, key -> seated("junk_" + junk.id(), asset));
            // A full belly lifts the crown with everything on it (crownLift): measure from the real body top.
            return new double[]{e[0] * body, y.getBbHeight() + (e[1] - BODY_UNITS / 16) * body};
        }
        if (!y.life().hasAccessory()) return null;
        var e = EXTENT.computeIfAbsent("accessory_" + asset, HeadTop::accessory);
        double hat = HatScale.of(y.growthScale(), y.subspeciesFlags()) * y.getScale();
        return new double[]{e[0] * hat, y.getBbHeight() + Math.max(0, e[1] - BODY_UNITS / 16) * hat};
    }

    /** The junk prop's vertices through its seat for this form: width and top in blocks at body scale 1. */
    private static double[] seated(String prop, String asset) {
        var data = prop(prop);
        var anchors = data.getAsJsonObject("anchors");
        if (anchors == null || !anchors.has(asset)) throw new IllegalStateException("No worn seat for " + prop + " on " + asset);
        var seat = WornSeat.read(anchors.getAsJsonObject(asset)).matrix();
        var v = data.getAsJsonArray("vertices");
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z0 = Float.MAX_VALUE, z1 = -Float.MAX_VALUE, top = -Float.MAX_VALUE;
        var p = new Vector3f();
        for (int i = 0; i + 2 < v.size(); i += 8) {
            seat.transformPosition(p.set(v.get(i).getAsFloat() / 16, v.get(i + 1).getAsFloat() / 16, v.get(i + 2).getAsFloat() / 16));
            x0 = Math.min(x0, p.x); x1 = Math.max(x1, p.x); z0 = Math.min(z0, p.z); z1 = Math.max(z1, p.z); top = Math.max(top, p.y);
        }
        return new double[]{Math.max(x1 - x0, z1 - z0), top};
    }

    /** The worn accessory export's width and top in blocks (feet origin, scale 1). */
    private static double[] accessory(String prop) {
        var bounds = prop(prop).getAsJsonArray("bounds");
        double x = bounds.get(0).getAsJsonArray().get(1).getAsDouble() - bounds.get(0).getAsJsonArray().get(0).getAsDouble();
        double z = bounds.get(2).getAsJsonArray().get(1).getAsDouble() - bounds.get(2).getAsJsonArray().get(0).getAsDouble();
        return new double[]{Math.max(x, z) / 16, bounds.get(1).getAsJsonArray().get(1).getAsDouble() / 16};
    }

    private static JsonObject prop(String prop) {
        return PROPS.computeIfAbsent(prop, name -> {
            try (var stream = HeadTop.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/" + name + ".json")) {
                if (stream == null) throw new IllegalStateException("Missing worn prop export " + name);
                return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException error) { throw new UncheckedIOException(error); }
        });
    }

    private HeadTop() {}
}
