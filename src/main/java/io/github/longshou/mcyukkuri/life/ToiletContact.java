package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.entity.YukkuriView;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.settlement.GroupPits;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Supported toilet positions and real portions; no inventory change is earned by proximity alone. */
public final class ToiletContact {
    /** The straining clip ({@code toilet_contact.json}, 40 ticks) pushes the stool out between ticks 12 and 34; it leaves the body at DROP_TICKS. */
    public static final int RELEASE_TICKS = 40, EMERGE_FROM_TICKS = 12, DROP_TICKS = 34;
    private static final JsonObject DATA = read(), MODELS = DATA.getAsJsonObject("models"), STOOL = DATA.getAsJsonObject("stool");
    /** The stool's start inside the anus (model units) and its leaving direction, both at rest; its full length and radius (blocks). */
    private static final double[] INSIDE = numbers(STOOL.getAsJsonArray("inside_px")), DIRECTION = numbers(STOOL.getAsJsonArray("direction"));
    private static final double LENGTH = STOOL.get("length").getAsDouble(), RADIUS = STOOL.get("radius").getAsDouble();
    /** Per form and clip, the root pose at every clip tick (position in model units, rotation in degrees, scale), read once. */
    private static final Map<String, double[][]> POSES = new ConcurrentHashMap<>();
    private ToiletContact() {}

    private static JsonObject read() {
        try (var stream = ToiletContact.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/toilet_contact.json")) {
            if (stream == null) throw new IllegalStateException("Missing toilet contact export");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    private static double[] numbers(JsonArray values) {
        var out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i).getAsDouble();
        return out;
    }

    /**
     * The stool of the straining clip {@code clip} at {@code tick} in the actor's own model frame (blocks at growth 1, origin under the
     * body, +z the rear): it starts just inside the anus (油菊, the rump mark's centre) and points along the rear-downward normal, both
     * carried by the clip's root pose, and grows out between EMERGE_FROM_TICKS and DROP_TICKS. The client draws exactly this stool and
     * the server lets the real item go from it ({@link #release}), so what is seen leaving the body is what flies. Null for a form or
     * clip without the export.
     */
    public static Stool stool(YukkuriView actor, String clip, float tick) {
        var model = MODELS.has(actor.assetId()) ? MODELS.getAsJsonObject(actor.assetId()) : null;
        var pose = model == null ? null : pose(actor.assetId(), model, clip, tick);
        if (pose == null) return null;
        var outlet = model.getAsJsonObject("outlet");
        double[] site = numbers(outlet.getAsJsonArray("site_px")), pivot = numbers(outlet.getAsJsonArray("pivot_px"));
        double[] start = posed(pose, pivot, new double[]{site[0] + INSIDE[0], site[1] + INSIDE[1], site[2] + INSIDE[2]}, true);
        double[] along = posed(pose, pivot, DIRECTION, false);
        double q = Math.clamp((tick - EMERGE_FROM_TICKS) / (double) (DROP_TICKS - EMERGE_FROM_TICKS), 0, 1), size = actor.getVariant().width() / .75;
        q = q * q * (3 - 2 * q);
        return new Stool(new Vec3(start[0], start[1], start[2]).scale(1 / 16.0), new Vec3(along[0], along[1], along[2]).normalize(),
                LENGTH * size * q, RADIUS * size * (.55 + .45 * q));
    }

    /** A stool in the model frame (blocks at growth 1): where it starts, its unit direction, its drawn length and radius. */
    public record Stool(Vec3 start, Vec3 direction, double length, double radius) {
        /** The bottom of its middle: where a loose dropping that continues it rests (a dropping is drawn standing on its position). */
        public Vec3 bottom() { return start.add(direction.scale(length / 2)).subtract(0, radius, 0); }
    }

    /** Where the stool leaves at the drop tick with the body at a given yaw: its bottom middle, its unit direction and its length, in the world. */
    public record Release(Vec3 at, Vec3 direction, double length) {}

    /** The stool of {@code clip} at its drop tick, in the world, with the actor's body turned to {@code yaw}; null without the export. */
    public static Release release(YukkuriEntity actor, String clip, float yaw) {
        var stool = stool(actor, clip, DROP_TICKS);
        if (stool == null) return null;
        double scale = actor.growthScale() * actor.getScale(), turn = Math.toRadians(yaw);
        var at = stool.bottom().scale(scale);
        return new Release(actor.position().add(rearOffset(at.x, at.z, turn)).add(0, at.y, 0),
                rearOffset(stool.direction().x, stool.direction().z, turn).add(0, stool.direction().y, 0), stool.length() * scale);
    }

    /**
     * The anus (油菊) of the actor's own model at tick {@code clipTick} of the ground straining clip, in world coordinates: the rump
     * mark's centre carried by the clip's root pose, scaled and turned like the renderer does. Falls back to the low rear point for an
     * unknown form.
     */
    public static Vec3 anus(YukkuriEntity actor, int clipTick) {
        String asset = actor.subspecies().assetId();
        var model = MODELS.has(asset) ? MODELS.getAsJsonObject(asset) : null;
        var pose = model == null ? null : pose(asset, model, "ground_toilet", clipTick);
        if (pose == null) return rear(actor);
        var outlet = model.getAsJsonObject("outlet");
        double[] point = posed(pose, numbers(outlet.getAsJsonArray("pivot_px")), numbers(outlet.getAsJsonArray("site_px")), true);
        double scale = actor.growthScale() * actor.getScale() / 16;
        return actor.position().add(rearOffset(point[0] * scale, point[2] * scale, Math.toRadians(actor.yBodyRot))).add(0, point[1] * scale, 0);
    }

    /** The clip's root pose at {@code tick}, linear between its per-tick keys as the client samples them: position, rotation, scale. */
    private static double[] pose(String asset, JsonObject model, String clip, float tick) {
        var frames = POSES.computeIfAbsent(asset + "/" + clip, key -> frames(model, clip));
        if (frames.length == 0) return null;
        double at = Math.clamp(tick, 0, frames.length - 1);
        int low = (int) Math.floor(at), high = Math.min(low + 1, frames.length - 1);
        double q = at - low;
        var out = new double[9];
        for (int i = 0; i < 9; i++) out[i] = frames[low][i] + (frames[high][i] - frames[low][i]) * q;
        return out;
    }

    private static double[][] frames(JsonObject model, String clip) {
        var clips = model.getAsJsonObject("clips");
        if (!clips.has(clip) || !model.has("outlet")) return new double[0][];
        int root = model.getAsJsonArray("bone_names").asList().indexOf(new JsonPrimitive("root"));
        var keys = new JsonArray[3];
        for (var track : clips.getAsJsonObject(clip).getAsJsonArray("tracks")) {
            var row = track.getAsJsonObject();
            if (row.get("bone").getAsInt() != root) continue;
            int channel = switch (row.get("channel").getAsString()) { case "position" -> 0; case "rotation" -> 1; case "scale" -> 2; default -> -1; };
            if (channel >= 0) keys[channel] = row.getAsJsonArray("keys");
        }
        if (keys[0] == null || keys[1] == null || keys[2] == null) return new double[0][];
        var out = new double[keys[0].size()][9];
        for (int tick = 0; tick < out.length; tick++)
            for (int channel = 0; channel < 3; channel++) {
                var key = keys[channel].get(tick).getAsJsonArray();
                for (int axis = 0; axis < 3; axis++) out[tick][channel * 3 + axis] = key.get(axis + 1).getAsDouble();
            }
        return out;
    }

    /** A rest-space point (about the root pivot, then moved by the root position) or direction under the root pose: R = Rz Ry Rx times scale. */
    private static double[] posed(double[] pose, double[] pivot, double[] value, boolean point) {
        double x = (value[0] - (point ? pivot[0] : 0)) * pose[6], y = (value[1] - (point ? pivot[1] : 0)) * pose[7];
        double z = (value[2] - (point ? pivot[2] : 0)) * pose[8];
        double ax = Math.toRadians(pose[3]), ay = Math.toRadians(pose[4]), az = Math.toRadians(pose[5]);
        double y1 = y * Math.cos(ax) - z * Math.sin(ax), z1 = y * Math.sin(ax) + z * Math.cos(ax);
        double x2 = x * Math.cos(ay) + z1 * Math.sin(ay), z2 = -x * Math.sin(ay) + z1 * Math.cos(ay);
        double x3 = x2 * Math.cos(az) - y1 * Math.sin(az), y3 = x2 * Math.sin(az) + y1 * Math.cos(az);
        return point ? new double[]{x3 + pivot[0] + pose[0], y3 + pivot[1] + pose[1], z2 + pivot[2] + pose[2]} : new double[]{x3, y3, z2};
    }

    private static Vec3 rearOffset(double x, double z, double yaw) {
        // Model +z is the rear; the renderer turns the model by 180 - body yaw about the vertical axis:
        // theta = 180 - yaw: (x, z) -> (x cos(theta) + z sin(theta), -x sin(theta) + z cos(theta)).
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        return new Vec3(-x * cos + z * sin, 0, -x * sin - z * cos);
    }

    public static Vec3 rear(YukkuriEntity actor) {
        return FamilyFeedingContact.rearPoint(actor);
    }

    /** A blocked rear outlet never falls back to the body's centre. */
    public static boolean drop(YukkuriEntity actor, Vec3 outlet) { return drop(actor, outlet, null); }

    /**
     * The portion is set down on the ground cell under the outlet (life/Poops: capacity, tiers, overflow to a neighbour cell).
     * {@code pit}: the pit cell the member stands beside, which then takes the portion whatever lies under its rear. Only when no
     * cell can take it (water, a ledge, a shelter's inside, everything around full) does it stay a loose item as before.
     */
    public static boolean drop(YukkuriEntity actor, Vec3 outlet, BlockPos pit) {
        if (!(actor.level() instanceof ServerLevel server) || outlet == null) return false;
        var stack = DetachedItems.waste(actor);
        var origin = pit != null ? pit : floorUnder(server, outlet);
        var group = actor.settlement().group();
        if (origin != null) {
            var cells = pit == null || group == null ? List.<BlockPos>of() : GroupPits.cells(server, group);
            var placed = Poops.place(server, origin, stack.copy(), cells.isEmpty() ? null : cell -> GroupPits.inCells(cells, cell));
            if (placed != null) {
                GameplayLog.event(actor, "needs", "poop placed at={} meant={} tier={} -> {} portions={} overflow={} pit={}",
                        placed.at(), origin, placed.tierBefore(), placed.tierAfter(), placed.after(), placed.overflow(), pit != null);
                return true;
            }
        }
        boolean spawned = loose(server, stack, outlet, Vec3.ZERO) != null;
        if (spawned) GameplayLog.limited(actor, "needs", "poop_loose", 200, "poop stays a loose item reason={} meant={}",
                origin == null ? "no_floor_cell" : "no_cell_within_" + Poops.SPREAD, origin);
        return spawned;
    }

    /**
     * The stool leaves the body at {@code at} with {@code velocity} (blocks per tick) as a loose dropping: over water no cell can hold
     * it, so the item flies and falls by vanilla item motion alone (aquatic/BoatToilet plans that path). Null if it cannot exist there.
     */
    public static ItemEntity launch(YukkuriEntity actor, Vec3 at, Vec3 velocity) {
        return actor.level() instanceof ServerLevel server && at != null ? loose(server, DetachedItems.waste(actor), at, velocity) : null;
    }

    private static ItemEntity loose(ServerLevel server, ItemStack stack, Vec3 at, Vec3 velocity) {
        var waste = new ItemEntity(server, at.x, at.y, at.z, stack);
        waste.setDefaultPickUpDelay();
        waste.setDeltaMovement(velocity);
        return server.noCollision(waste, waste.getBoundingBox()) && server.addFreshEntity(waste) ? waste : null;
    }

    private static BlockPos floorUnder(ServerLevel level, Vec3 outlet) {
        var cell = BlockPos.containing(outlet);
        if (SupplyPiles.supported(level, cell)) return cell;
        return SupplyPiles.supported(level, cell.below()) ? cell.below() : null;
    }

    /** Stand on the actual collision surface, facing along the tray's longer axis. */
    public static Vec3 facilitySeat(YukkuriEntity actor, BlockPos pos) {
        if (pos == null || !(actor.level().getBlockEntity(pos) instanceof CareBlockEntity care)
                || care.kind() != CareBlock.Kind.TOILET || care.waste() >= care.capacity()) return null;
        var shape = actor.level().getBlockState(pos).getCollisionShape(actor.level(), pos);
        if (shape.isEmpty()) return null;
        var bounds = shape.bounds();
        double rear = actor.getBbWidth() * .55;
        // The outlet must fit over the collecting tray; large forms cannot use a tiny tray.
        if (rear > bounds.getXsize() - .12 || actor.getBbWidth() > bounds.getZsize() + .25) return null;
        double x = Math.max((bounds.minX + bounds.maxX) * .5, bounds.minX + .08 + rear);
        if (x > bounds.maxX - .06) return null;
        var seat = new Vec3(pos.getX() + x, pos.getY() + bounds.maxY, pos.getZ() + (bounds.minZ + bounds.maxZ) * .5);
        var body = actor.getBoundingBox().move(seat.subtract(actor.position())).deflate(.005);
        if (!actor.level().getFluidState(pos).isEmpty() || !actor.level().noCollision(actor, body)) return null;
        return seat;
    }

    public static boolean approachFacility(YukkuriEntity actor, BlockPos pos) {
        var seat = facilitySeat(actor, pos);
        if (seat == null) return false;
        if (SurfaceApproach.arrived(actor, seat)) {
            SurfaceApproach.halt(actor);
            SurfaceApproach.face(actor, -90); // face east; rear points into the tray
            return facilityContact(actor, pos);
        }
        // Once on the low tray, use ordinary MoveControl for the final centimetres.
        if (actor.onGround() && Math.abs(actor.getY() - seat.y) < .08
                && actor.position().subtract(seat).horizontalDistanceSqr() < .36) {
            actor.getNavigation().stop();
            actor.getMoveControl().setWantedPosition(seat.x, seat.y, seat.z, .55);
        } else if (actor.getNavigation().isDone() || actor.tickCount % 10 == 0) {
            actor.life().navigate(pos.above(), 1, false);
        }
        return false;
    }

    public static boolean facilityContact(YukkuriEntity actor, BlockPos pos) {
        var seat = facilitySeat(actor, pos);
        if (seat == null || !actor.onGround() || !SurfaceApproach.arrived(actor, seat)
                || Vec3.directionFromRotation(0, actor.getYRot()).dot(new Vec3(1, 0, 0)) < .98) return false;
        var rear = rear(actor);
        var shape = actor.level().getBlockState(pos).getCollisionShape(actor.level(), pos).bounds().move(pos);
        if (rear.x < shape.minX || rear.x > shape.maxX || rear.z < shape.minZ || rear.z > shape.maxZ) return false;
        var hit = actor.level().clip(new ClipContext(rear, rear.add(0, -.15, 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getDirection() == Direction.UP;
    }
}
