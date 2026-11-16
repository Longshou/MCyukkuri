package io.github.longshou.mcyukkuri.motor;

import com.google.gson.JsonArray;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.SideProfile;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * Stands for two bodies pressing side by side, from the fitted runtime meshes posed by lore/side_profile.py.
 */
public final class SideContact {
    private record Sides(SideProfile right, SideProfile left) {}
    private record Profiles(Map<String, Sides> huddle, Map<String, Sides> snuggle) {}
    private static final Profiles PROFILES = load();
    public static final double SNUGGLE_STEP = .35;
    /** Sole heights come from collision shapes; allow for the floating-point error of reaching a surface. */
    public static final double SUPPORT_EPSILON = 1e-4;
    /** Offsets in front of or behind the partner tried for the stand (blocks): up to a quarter block, so bows and hair can pass. */
    private static final double[] BEHIND = {-.25, -.1875, -.125, -.0625, 0, .0625, .125, .1875, .25};

    /**
     * The feet of {@code mover} pressed against {@code partner}'s right side ({@code right}) or left side, both facing {@code yaw}
     * (the partner holds still), on the partner's floor; the caller finds the real floor there.
     */
    public static Vec3 beside(YukkuriEntity mover, YukkuriEntity partner, float yaw, boolean right) {
        var theirs = PROFILES.huddle().get(partner.subspecies().assetId());
        var mine = PROFILES.huddle().get(mover.subspecies().assetId());
        double across, behind = 0;
        if (theirs == null || mine == null) across = (mover.getBbWidth() + partner.getBbWidth()) * .5 + .02;
        else {
            // On the partner's right, the mover has the partner on its own left.
            var stand = SideProfile.closest(right ? theirs.right() : theirs.left(), scale(partner),
                    right ? mine.left() : mine.right(), scale(mover), 0, BEHIND);
            // Walking may stop on the inside edge of its arrival envelope; that edge must still clear the posed surfaces.
            across = stand.across() + SurfaceApproach.ARRIVAL; behind = stand.behind();
        }
        return offset(partner.position(), yaw, right, across, behind);
    }

    /** A snuggle on the real stand surface. An unequal pair lines up fore and aft to keep the rub clear of hair and bows. */
    public static Vec3 snuggle(YukkuriEntity mover, YukkuriEntity partner, float yaw) {
        double mine = scale(mover), theirs = scale(partner);
        var floor = mine == theirs ? SurfaceApproach.floor(mover, beside(mover, partner, yaw, false))
                : snuggleFloor(mover, partner, yaw, mine, theirs, 0);
        if (floor == null) return null;
        double dy = floor.y - partner.getY();
        if (Math.abs(dy) <= SUPPORT_EPSILON) return floor;
        if (Math.abs(dy) > SNUGGLE_STEP) return null;
        var at = snuggleFloor(mover, partner, yaw, mine, theirs, dy);
        return at != null && Math.abs(at.y - floor.y) <= SUPPORT_EPSILON ? at : null;
    }

    private static Vec3 snuggleFloor(YukkuriEntity mover, YukkuriEntity partner, float yaw, double mine, double theirs, double dy) {
        var left = PROFILES.snuggle().get(partner.subspecies().assetId()).left();
        var right = PROFILES.snuggle().get(mover.subspecies().assetId()).right();
        double across = SideProfile.touching(left, theirs, right, mine, dy, 0);
        if (across == 0) return null;
        // The posed envelope includes different beats of the clip. Allow a light press and keep it inside walking's arrival slack.
        double stand = across - 2 * SurfaceApproach.ARRIVAL;
        return SurfaceApproach.floor(mover, offset(partner.position().add(0, dy, 0), yaw, false, stand, 0));
    }

    private static Vec3 offset(Vec3 partner, float yaw, boolean right, double across, double behind) {
        // A body facing yaw has (-cos, -sin) on its right and (sin, -cos) behind it (x, z).
        double rad = Math.toRadians(yaw), side = right ? 1 : -1;
        return partner.add(-Math.cos(rad) * across * side + Math.sin(rad) * behind, 0,
                -Math.sin(rad) * across * side - Math.cos(rad) * behind);
    }

    /** Blocks per model unit of this body as drawn (growth and scale). */
    private static double scale(YukkuriEntity body) { return body.growthScale() * body.getScale() / 16; }

    private static Profiles load() {
        var json = GeometryJson.read("yukkuri_presentation/side_profile.json");
        double depthBand = json.number("band");
        return new Profiles(load(json, "models", depthBand, depthBand, json.number("z0")),
                load(json, "snuggle.models", json.number("snuggle.height_band"), depthBand, json.number("snuggle.z0")));
    }

    private static Map<String, Sides> load(GeometryJson json, String field, double heightBand, double depthBand, double z0) {
        var out = new HashMap<String, Sides>();
        for (var entry : json.field(field).getAsJsonObject().entrySet()) {
            var model = entry.getValue().getAsJsonObject();
            out.put(entry.getKey(), new Sides(profile(heightBand, depthBand, z0, model.getAsJsonArray("right")),
                    profile(heightBand, depthBand, z0, model.getAsJsonArray("left"))));
        }
        return Map.copyOf(out);
    }
    private static SideProfile profile(double heightBand, double depthBand, double z0, JsonArray rows) {
        var reach = new double[rows.size()][];
        for (int y = 0; y < reach.length; y++) {
            var row = rows.get(y).getAsJsonArray();
            reach[y] = new double[row.size()];
            for (int z = 0; z < row.size(); z++) reach[y][z] = row.get(z).getAsDouble();
        }
        return new SideProfile(heightBand, depthBand, z0, reach);
    }

    private SideContact() {}
}
