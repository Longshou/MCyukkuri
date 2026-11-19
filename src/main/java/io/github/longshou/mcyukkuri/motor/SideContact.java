package io.github.longshou.mcyukkuri.motor;

import com.google.gson.JsonArray;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.motor.geom.GeometryJson;
import io.github.longshou.mcyukkuri.motor.geom.SideProfile;
import io.github.longshou.mcyukkuri.pose.SideSurface;
import io.github.longshou.mcyukkuri.pose.SnugglePose;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Stands for two bodies pressing side by side, from the fitted runtime meshes posed by lore/side_profile.py.
 */
public final class SideContact {
    private record Sides(SideProfile right, SideProfile left) {}
    private static final Map<String, Sides> HUDDLE = load();
    /** Offsets in front of or behind the partner tried for the stand (blocks): up to a quarter block, so bows and hair can pass. */
    private static final double[] BEHIND = {-.25, -.1875, -.125, -.0625, 0, .0625, .125, .1875, .25};

    /**
     * The feet of {@code mover} pressed against {@code partner}'s right side ({@code right}) or left side, both facing {@code yaw}
     * (the partner holds still), on the partner's floor; the caller finds the real floor there.
     */
    public static Vec3 beside(YukkuriEntity mover, YukkuriEntity partner, float yaw, boolean right) {
        var theirs = HUDDLE.get(partner.subspecies().assetId());
        var mine = HUDDLE.get(mover.subspecies().assetId());
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

    /** The opening cheek contact with both sets of feet on the partner's floor. */
    public static Vec3 levelStand(YukkuriEntity mover, YukkuriEntity partner, float yaw) {
        return snuggleStand(mover, partner, yaw, 0, 0);
    }

    /** The opening contact at the mover's current height has to share a real posed point across the rise. */
    public static Vec3 riseStand(YukkuriEntity mover, YukkuriEntity partner, float yaw) {
        return snuggleStand(mover, partner, yaw, mover.getY() - partner.getY(), 0);
    }

    public static Vec3 snuggleStand(YukkuriEntity mover, YukkuriEntity partner, float yaw, double lift, int tick) {
        var left = SnugglePose.at(partner.subspecies().assetId(), false, tick);
        var right = SnugglePose.at(mover.subspecies().assetId(), true, tick);
        var contact = SideSurface.meet(left, scale(partner), right, scale(mover), lift, 0);
        if (contact == null) return null;
        var stand = offset(partner.position().add(0, lift, 0), yaw, false, contact.separation(), 0);
        if (lift == 0) return stand;
        // The constraint is the shared skin point of this frame. A riser cannot form the contact.
        var from = offset(partner.position().add(0, contact.height(), 0), yaw, false, 0, contact.depth());
        var to = offset(from, yaw, false, contact.separation(), 0);
        return mover.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mover))
                .getType() == HitResult.Type.MISS ? stand : null;
    }

    private static Vec3 offset(Vec3 partner, float yaw, boolean right, double across, double behind) {
        // A body facing yaw has (-cos, -sin) on its right and (sin, -cos) behind it (x, z).
        double rad = Math.toRadians(yaw), side = right ? 1 : -1;
        return partner.add(-Math.cos(rad) * across * side + Math.sin(rad) * behind, 0,
                -Math.sin(rad) * across * side - Math.cos(rad) * behind);
    }

    /** Blocks per model unit of this body as drawn (growth and scale). */
    private static double scale(YukkuriEntity body) { return body.growthScale() * body.getScale() / 16; }

    private static Map<String, Sides> load() {
        var json = GeometryJson.read("yukkuri_presentation/side_profile.json");
        double depthBand = json.number("band");
        return load(json, "models", depthBand, depthBand, json.number("z0"));
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
