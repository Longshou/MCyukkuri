package io.github.longshou.mcyukkuri.motor;

import com.google.gson.JsonArray;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.SideProfile;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Two bodies pressed side by side, both facing the same way: where the second stands so that their side reaches over the clip
 * they press with (the rain huddle's) just meet. The reaches come from the posed runtime models (lore/side_profile.py).
 */
public final class SideContact {
    private record Sides(SideProfile right, SideProfile left) {}
    private static final Map<String, Sides> PROFILES = load();
    /** Offsets in front of or behind the partner tried for the stand (blocks): up to a quarter block, so bows and hair can pass. */
    private static final double[] BEHIND = {-.25, -.1875, -.125, -.0625, 0, .0625, .125, .1875, .25};

    /**
     * The feet of {@code mover} pressed against {@code partner}'s right side ({@code right}) or left side, both facing {@code yaw}
     * (the partner holds still), on the partner's floor; the caller finds the real floor there.
     */
    public static Vec3 beside(YukkuriEntity mover, YukkuriEntity partner, float yaw, boolean right) {
        var theirs = PROFILES.get(partner.subspecies().assetId());
        var mine = PROFILES.get(mover.subspecies().assetId());
        double across, behind = 0;
        if (theirs == null || mine == null) across = (mover.getBbWidth() + partner.getBbWidth()) * .5 + .02;
        else {
            // On the partner's right, the mover has the partner on its own left.
            var stand = SideProfile.closest(right ? theirs.right() : theirs.left(), scale(partner),
                    right ? mine.left() : mine.right(), scale(mover), 0, BEHIND);
            across = stand.across(); behind = stand.behind();
        }
        // A body facing yaw has (-cos, -sin) on its right and (sin, -cos) behind it (x, z).
        double rad = Math.toRadians(yaw), side = right ? 1 : -1;
        return partner.position().add(-Math.cos(rad) * across * side + Math.sin(rad) * behind, 0,
                -Math.sin(rad) * across * side - Math.cos(rad) * behind);
    }

    /** Blocks per model unit of this body as drawn (growth and scale). */
    private static double scale(YukkuriEntity body) { return body.growthScale() * body.getScale() / 16; }

    private static Map<String, Sides> load() {
        var json = GeometryJson.read("yukkuri_presentation/side_profile.json");
        double band = json.number("band"), z0 = json.number("z0");
        var out = new HashMap<String, Sides>();
        for (var entry : json.field("models").getAsJsonObject().entrySet()) {
            var model = entry.getValue().getAsJsonObject();
            out.put(entry.getKey(), new Sides(profile(band, z0, model.getAsJsonArray("right")), profile(band, z0, model.getAsJsonArray("left"))));
        }
        return Map.copyOf(out);
    }
    private static SideProfile profile(double band, double z0, JsonArray rows) {
        var reach = new double[rows.size()][];
        for (int y = 0; y < reach.length; y++) {
            var row = rows.get(y).getAsJsonArray();
            reach[y] = new double[row.size()];
            for (int z = 0; z < row.size(); z++) reach[y][z] = row.get(z).getAsDouble();
        }
        return new SideProfile(band, z0, reach);
    }

    private SideContact() {}
}
