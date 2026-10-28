package io.github.longshou.mcyukkuri.life;

import io.github.longshou.mcyukkuri.body.DetachedItems;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.FamilyFeedingContact;
import io.github.longshou.mcyukkuri.house.SupplyPiles;
import io.github.longshou.mcyukkuri.settlement.GroupPits;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.List;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Supported toilet positions and real portions; no inventory change is earned by proximity alone. */
public final class ToiletContact {
    /** The straining clip ({@code toilet_contact.json}, 40 ticks) pushes the stool out between ticks 12 and 34; it leaves the body at DROP_TICKS. */
    public static final int RELEASE_TICKS = 40, EMERGE_FROM_TICKS = 12, DROP_TICKS = 34;
    private static final JsonObject MODELS = readModels();
    private ToiletContact() {}

    private static JsonObject readModels() {
        try (var stream = ToiletContact.class.getResourceAsStream("/assets/mcyukkuri/yukkuri_presentation/toilet_contact.json")) {
            if (stream == null) throw new IllegalStateException("Missing toilet contact export");
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("models");
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }

    /**
     * The anus (油菊) of the actor's own model at tick {@code clipTick} of the straining clip, in world coordinates: the rump mark's
     * centre carried by the clip's root track, then scaled and turned like the renderer does. The same model and clip drive the client's
     * emerging stool, so what is seen leaving the body is what lands. Falls back to the low rear point for an unknown form.
     */
    public static Vec3 anus(YukkuriEntity actor, int clipTick) {
        var model = MODELS.has(actor.subspecies().assetId()) ? MODELS.getAsJsonObject(actor.subspecies().assetId()) : null;
        if (model == null || !model.has("outlet")) return rear(actor);
        var track = model.getAsJsonObject("outlet").getAsJsonArray("track");
        var point = track.get(Math.clamp(clipTick, 0, track.size() - 1)).getAsJsonArray();
        double scale = actor.growthScale() * actor.getScale();
        double x = point.get(0).getAsDouble() * scale, y = point.get(1).getAsDouble() * scale, z = point.get(2).getAsDouble() * scale;
        // Model +z is the rear; the renderer turns the model by 180 - body yaw about the vertical axis.
        double yaw = Math.toRadians(actor.yBodyRot);
        return actor.position().add(rearOffset(x, z, yaw)).add(0, y, 0);
    }

    private static Vec3 rearOffset(double x, double z, double yaw) {
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
        var waste = new ItemEntity(server, outlet.x, outlet.y, outlet.z, stack);
        waste.setDefaultPickUpDelay();
        waste.setDeltaMovement(Vec3.ZERO);
        boolean spawned = server.noCollision(waste, waste.getBoundingBox()) && server.addFreshEntity(waste);
        if (spawned) GameplayLog.limited(actor, "needs", "poop_loose", 200, "poop stays a loose item reason={} meant={}",
                origin == null ? "no_floor_cell" : "no_cell_within_" + Poops.SPREAD, origin);
        return spawned;
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
