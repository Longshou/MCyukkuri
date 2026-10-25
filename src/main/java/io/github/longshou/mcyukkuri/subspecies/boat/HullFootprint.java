package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.registry.ModEntities;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * The one "a hull fits here" decision. A hull is its oriented brim rectangle (hat_vessel.json {@code brim} half extents times
 * the hull unit, rotated by the hull's yaw) over the thin slab the floating entity occupies. Every placement, whether a launch
 * from the bank, a spawn, a self-rescue or a scenario, asks this class and gets the first real obstruction back: the block
 * and its position, the other boat, the entity, the dry column or the world border.
 */
public final class HullFootprint {
    private static final double EPS = 1e-6;
    /** Columns whose overlap with the rectangle is no more than this are only grazed and need not be water. */
    private static final double GRAZE = .02;

    /** Result of a footprint test; {@code reason} and {@code detail} are loggable and say what obstructs. */
    public record Fit(boolean ok, String reason, String detail) {
        static final Fit OK = new Fit(true, "", "");
        static Fit no(String reason, String detail) { return new Fit(false, reason, detail); }
    }

    private HullFootprint() {}

    /** Half width across the hat (local X) of the brim at this hull scale, in blocks. */
    public static double halfWidth(float hullScale) { return HatVessel.BRIM[0] * hullScale / 16.0; }
    /** Half length along the hat (local Z) of the brim at this hull scale, in blocks. */
    public static double halfLength(float hullScale) { return HatVessel.BRIM[1] * hullScale / 16.0; }

    /** How far the rectangle reaches from its centre along the unit horizontal direction (dx, dz). */
    public static double reach(float yaw, float hullScale, double dx, double dz) {
        return rect(0, 0, yaw, hullScale, 0).radius(dx, dz);
    }

    /** The collision slab height of a hull of this scale (the entity's own dimensions). */
    public static double height(float hullScale) {
        return ModEntities.HAT_BOAT.get().getDimensions().scale(hullScale / HatVessel.AQUATIC_SCALE).height();
    }

    /**
     * Whether a hull of this scale lying at {@code feet} with this yaw is clear.
     *
     * @param context  the entity whose collision rules apply (the placing captain, or the hull itself); may be null
     * @param self     a hull that already exists and is being moved here, which never obstructs itself; may be null
     * @param margin   extra clearance in blocks around the rectangle (open-water spawns and scenarios ask for some)
     */
    public static Fit fits(ServerLevel level, Entity context, HatBoatEntity self, Vec3 feet, float yaw, float hullScale, double margin) {
        var rect = rect(feet.x, feet.z, yaw, hullScale, margin);
        int minX = (int) Math.floor(rect.minX()), maxX = (int) Math.floor(rect.maxX());
        int minZ = (int) Math.floor(rect.minZ()), maxZ = (int) Math.floor(rect.maxZ());
        double height = height(hullScale);
        double y0 = feet.y, y1 = feet.y + height;
        int minY = (int) Math.floor(y0 + EPS), maxY = (int) Math.floor(y1 - EPS);
        int waterY = BlockPos.containing(feet.x, feet.y - .12, feet.z).getY();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            var column = new BlockPos(x, waterY, z);
            if (!level.hasChunkAt(column)) return Fit.no("hull_unloaded", "chunk at " + column.toShortString() + " is not loaded");
        }
        var border = level.getWorldBorder();
        for (var corner : rect.corners()) if (!border.isWithinBounds(corner[0], corner[1]))
            return Fit.no("hull_world_border", "corner (" + fmt(corner[0]) + "," + fmt(corner[1]) + ") is outside the world border");
        var centre = BlockPos.containing(feet.x, feet.y - .12, feet.z);
        if (!HatBoatRoute.calmSurface(level, centre))
            return Fit.no("water_not_calm", "centre " + centre.toShortString() + " is not a calm water surface");

        var shapeContext = context == null ? CollisionContext.empty() : CollisionContext.of(context);
        var cell = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) {
            cell.set(x, y, z);
            var state = level.getBlockState(cell);
            if (state.isAir()) continue;
            var shape = state.getCollisionShape(level, cell, shapeContext);
            if (shape.isEmpty()) continue;
            for (var box : shape.toAabbs()) {
                double bx0 = x + box.minX, bx1 = x + box.maxX, bz0 = z + box.minZ, bz1 = z + box.maxZ;
                double by0 = y + box.minY, by1 = y + box.maxY;
                if (by1 <= y0 + EPS || by0 >= y1 - EPS) continue;
                if (rect.overlaps(bx0, bz0, bx1, bz1))
                    return Fit.no("hull_block", "block " + cell.toShortString() + " " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                            + " blocks the brim rectangle");
            }
        }
        // Every column under the rectangle must really hold water at the hull's level: no brim lying over a dry notch.
        var inner = rect(feet.x, feet.z, yaw, hullScale, -GRAZE);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!inner.overlaps(x, z, x + 1, z + 1)) continue;
            cell.set(x, waterY, z);
            if (!level.getFluidState(cell).is(FluidTags.WATER))
                return Fit.no("hull_over_land", "column " + cell.toShortString() + " under the brim has no water");
        }
        var area = new AABB(rect.minX(), y0, rect.minZ(), rect.maxX(), y1, rect.maxZ());
        for (var other : level.getEntitiesOfClass(HatBoatEntity.class, area.inflate(3, 1, 3),
                boat -> boat != self && boat != context && !boat.isRemoved() && boat.canBeCollidedWith())) {
            if (Math.abs(other.getY() - feet.y) > .6) continue;
            var theirs = rect(other.getX(), other.getZ(), other.getYRot(), other.hullScale(), 0);
            if (rect.overlaps(theirs)) return Fit.no("hull_boat", "another hat boat " + other.getUUID() + " lies at "
                    + fmt(other.getX()) + "," + fmt(other.getY()) + "," + fmt(other.getZ()));
        }
        var physical = ModEntities.HAT_BOAT.get().getDimensions().scale(hullScale / HatVessel.AQUATIC_SCALE).makeBoundingBox(feet);
        for (var shape : level.getBlockCollisions(context, physical)) if (!shape.isEmpty())
            return Fit.no("hull_block", "a block shape " + shape.bounds() + " intersects the hull box " + physical);
        for (var entity : level.getEntities(context, physical, e -> !(e instanceof HatBoatEntity) && e != self && e.canBeCollidedWith()
                && (context == null || context.canCollideWith(e))))
            return Fit.no("hull_entity", "entity " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " " + entity.getUUID()
                    + " at " + fmt(entity.getX()) + "," + fmt(entity.getY()) + "," + fmt(entity.getZ()));
        return Fit.OK;
    }

    private static String fmt(double value) { return String.format(java.util.Locale.ROOT, "%.2f", value); }

    private static Rect rect(double cx, double cz, float yaw, float hullScale, double margin) {
        double turn = Math.toRadians(180 - yaw);
        double ax = Math.cos(turn), az = -Math.sin(turn);   // the hat's local X in world, as Vec3.yRot maps it
        return new Rect(cx, cz, ax, az, -az, ax, halfWidth(hullScale) + margin, halfLength(hullScale) + margin);
    }

    /** An oriented rectangle on the horizontal plane: centre, unit axes A (across) and L (along), half extents. */
    private record Rect(double cx, double cz, double ax, double az, double lx, double lz, double hx, double hz) {
        double radius(double nx, double nz) { return hx * Math.abs(ax * nx + az * nz) + hz * Math.abs(lx * nx + lz * nz); }
        double minX() { return cx - radius(1, 0); }
        double maxX() { return cx + radius(1, 0); }
        double minZ() { return cz - radius(0, 1); }
        double maxZ() { return cz + radius(0, 1); }
        double[][] corners() {
            return new double[][]{
                    {cx + ax * hx + lx * hz, cz + az * hx + lz * hz}, {cx - ax * hx + lx * hz, cz - az * hx + lz * hz},
                    {cx + ax * hx - lx * hz, cz + az * hx - lz * hz}, {cx - ax * hx - lx * hz, cz - az * hx - lz * hz}};
        }
        boolean overlaps(double x0, double z0, double x1, double z1) {
            double bx = (x1 - x0) / 2, bz = (z1 - z0) / 2, dx = (x0 + x1) / 2 - cx, dz = (z0 + z1) / 2 - cz;
            if (Math.abs(dx) >= bx + radius(1, 0) || Math.abs(dz) >= bz + radius(0, 1)) return false;
            if (Math.abs(dx * ax + dz * az) >= hx + bx * Math.abs(ax) + bz * Math.abs(az)) return false;
            return Math.abs(dx * lx + dz * lz) < hz + bx * Math.abs(lx) + bz * Math.abs(lz);
        }
        boolean overlaps(Rect other) {
            double dx = other.cx - cx, dz = other.cz - cz;
            for (double[] n : new double[][]{{ax, az}, {lx, lz}, {other.ax, other.az}, {other.lx, other.lz}})
                if (Math.abs(dx * n[0] + dz * n[1]) >= radius(n[0], n[1]) + other.radius(n[0], n[1])) return false;
            return true;
        }
    }
}
