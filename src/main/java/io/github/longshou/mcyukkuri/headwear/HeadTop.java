package io.github.longshou.mcyukkuri.headwear;

import com.google.gson.JsonParser;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The space a body takes with what it wears, from the selected exports the client draws: the worn accessory export
 * (feet at zero, model units) or the junk prop on the crown. Shelters admit a body only when this fits their interior.
 */
public final class HeadTop {
    /** Model height of every selected body export (body_geometry.json); accessory exports share its feet origin. */
    private static final double BODY_UNITS = 9.6;
    private static final Map<String, double[]> EXTENT = new ConcurrentHashMap<>();

    /** Blocks from the feet to the highest worn point. */
    public static double height(YukkuriEntity y) {
        var size = headwear(y);
        return size == null ? y.getBbHeight() : Math.max(y.getBbHeight(), y.getBbHeight() + size[1] * scale(y));
    }

    /** Blocks across the widest of the body and its headwear. */
    public static double width(YukkuriEntity y) {
        var size = headwear(y);
        return size == null ? y.getBbWidth() : Math.max(y.getBbWidth(), size[0] * scale(y));
    }

    /** Headwear width and its rise above the crown, in blocks at hat scale 1; null when the head is bare. */
    private static double[] headwear(YukkuriEntity y) {
        var junk = y.life().custody().junk();
        if (junk != null) {
            var bounds = EXTENT.computeIfAbsent("junk_" + junk.id(), HeadTop::read);
            return new double[]{bounds[0], bounds[2]};
        }
        if (!y.life().hasAccessory()) return null;
        var bounds = EXTENT.computeIfAbsent("accessory_" + SubspeciesLife.assetId(y.getVariant(), y.subspeciesFlags()), HeadTop::read);
        return new double[]{bounds[0], Math.max(0, bounds[1] - BODY_UNITS / 16)};
    }

    private static double scale(YukkuriEntity y) { return HatScale.of(y.growthScale(), y.subspeciesFlags()) * y.getScale(); }

    /** Width, top and height of one prop export, in blocks. */
    private static double[] read(String prop) {
        try (var stream = HeadTop.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_props/" + prop + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing worn prop export " + prop);
            var bounds = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("bounds");
            double x0 = bounds.get(0).getAsJsonArray().get(0).getAsDouble(), x1 = bounds.get(0).getAsJsonArray().get(1).getAsDouble();
            double z0 = bounds.get(2).getAsJsonArray().get(0).getAsDouble(), z1 = bounds.get(2).getAsJsonArray().get(1).getAsDouble();
            double y0 = bounds.get(1).getAsJsonArray().get(0).getAsDouble(), y1 = bounds.get(1).getAsJsonArray().get(1).getAsDouble();
            return new double[]{Math.max(x1 - x0, z1 - z0) / 16, y1 / 16, (y1 - y0) / 16};
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private HeadTop() {}
}
