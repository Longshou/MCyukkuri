package io.github.longshou.mcyukkuri.subspecies.boat;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatEntity;
import io.github.longshou.mcyukkuri.subspecies.HatBoatRoute;
import io.github.longshou.mcyukkuri.subspecies.HatDock;
import io.github.longshou.mcyukkuri.subspecies.HatVessel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one shore-berth query. Where a hull may lie against a bank, or float in open water near one, for a launch, a landing,
 * a spawn or a scenario. A bank cell is any standable dry cell beside water, short plants included (they have no collision);
 * the hull centre is derived from the brim rectangle's reach toward the water, never from animation offsets; and the
 * hull must pass {@link HullFootprint}. The answer is a {@link Berth} or a {@link Refusal} that names the real obstruction.
 */
public final class ShoreBerth {
    public enum Purpose { LAUNCH, LAND, SPAWN, SCENARIO }

    /** Gap between the near edge of the brim rectangle and the bank wall, in blocks. */
    public static final double CLEARANCE = .03;
    /** Extra room around the brim for hulls that must be able to set off from open water. */
    private static final double OPEN_MARGIN = .35;
    private static final int MAX_FOOTPRINT_TESTS = 80;

    public sealed interface Outcome permits Berth, Refusal {}

    /**
     * A bank cell, the calm water beside it and the hull pose that matches both. Open-water purposes (SPAWN, SCENARIO) have no
     * bank: {@code bank}, {@code stand}, {@code toWater}, {@code inland} and {@code along} are then null and {@code water} is
     * the hull's own cell.
     */
    public record Berth(BlockPos bank, BlockPos water, Direction toWater, Vec3 stand, Vec3 center, float yaw,
                        Vec3 inland, Vec3 along, double cost, List<Vec3> walkOff) implements Outcome {
        /** The wall plane point in front of the captain's cell (midway between the bank cell and the water cell). */
        public Vec3 wall() { return stand.add(inland.scale(-.5)); }
    }

    /** No berth: a short stable {@code reason}, the first real obstruction in {@code detail}, and what was counted on the way. */
    public record Refusal(String reason, String detail, Map<String, Integer> counts) implements Outcome {
        public String describe() { return reason + (detail.isEmpty() ? "" : " (" + detail + ")") + " " + counts; }
    }

    /**
     * One query. {@code self} is an existing hull being moved (its own footprint never obstructs it); {@code crew} the riders
     * that must also step ashore; {@code acceptWater} lets a caller exclude a water cell (for example one that just failed).
     */
    public record Query(YukkuriEntity captain, HatBoatEntity self, float hullScale, BlockPos near, Purpose purpose, BlockPos hint,
                        int range, int crew, Predicate<BlockPos> acceptWater) {
        public Query(YukkuriEntity captain, float hullScale, BlockPos near, Purpose purpose, BlockPos hint) {
            this(captain, null, hullScale, near, purpose, hint, defaultRange(purpose), 0, water -> true);
        }
        public Query withRange(int value) { return new Query(captain, self, hullScale, near, purpose, hint, value, crew, acceptWater); }
        public Query withCrew(HatBoatEntity hull, int riders) { return new Query(captain, hull, hullScale, near, purpose, hint, range, riders, acceptWater); }
        public Query withWaterFilter(Predicate<BlockPos> filter) { return new Query(captain, self, hullScale, near, purpose, hint, range, crew, filter); }
    }

    private ShoreBerth() {}

    public static int defaultRange(Purpose purpose) {
        return switch (purpose) { case LAUNCH -> 16; case LAND -> 8; case SPAWN -> 14; case SCENARIO -> 10; };
    }

    public static Outcome find(ServerLevel level, YukkuriEntity captain, float hullScale, BlockPos near, Purpose purpose, BlockPos hint) {
        return find(level, new Query(captain, hullScale, near, purpose, hint));
    }

    public static Outcome find(ServerLevel level, Query query) {
        return switch (query.purpose()) {
            case LAUNCH, LAND -> alongBank(level, query);
            case SPAWN, SCENARIO -> openWater(level, query);
        };
    }

    /** A standable dry cell with no body in mind: loaded, free of fluid, no collision (plants are fine), sturdy ground below. */
    public static boolean bankCell(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        var state = level.getBlockState(pos);
        return level.getFluidState(pos).isEmpty() && level.getFluidState(pos.below()).isEmpty()
                && state.getCollisionShape(level, pos).isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }

    /** The nearest bank cell beside water within a few blocks of {@code origin}, or null. Replaceable plants do not disqualify a cell. */
    public static BlockPos nearestBank(Level level, BlockPos origin) {
        BlockPos best = null; double bestDistance = Double.MAX_VALUE;
        for (var p : BlockPos.betweenClosed(origin.offset(-8, -1, -8), origin.offset(8, 2, 8))) {
            if (!bankCell(level, p)) continue;
            boolean shore = false;
            for (var side : Direction.Plane.HORIZONTAL)
                if (level.getFluidState(p.relative(side).below()).is(FluidTags.WATER) || level.getFluidState(p.relative(side)).is(FluidTags.WATER)) shore = true;
            double d = p.distSqr(origin);
            if (shore && d < bestDistance) { bestDistance = d; best = p.immutable(); }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ along a bank

    private record Candidate(BlockPos stand, BlockPos water, Direction toWater, Vec3 standFeet, Vec3 center, float yaw, Vec3 inland, Vec3 along, double cost) {}

    private static Outcome alongBank(ServerLevel level, Query q) {
        var captain = q.captain();
        var counts = new LinkedHashMap<String, Integer>();
        var candidates = new ArrayList<Candidate>();
        for (var p : BlockPos.betweenClosed(q.near().offset(-q.range(), -2, -q.range()), q.near().offset(q.range(), 2, q.range()))) {
            if (!HatBoatRoute.calmSurface(level, p) || !q.acceptWater().test(p)) continue;
            count(counts, "calm_water");
            for (var side : Direction.Plane.HORIZONTAL) {
                var stand = p.relative(side).above();
                if (!level.getFluidState(p.relative(side)).isEmpty() || !level.hasChunkAt(stand)) continue;
                count(counts, "bank_cells");
                if (!(captain == null ? bankCell(level, stand) : captain.life().dryStand(stand))) { count(counts, "not_standable"); continue; }
                candidates.add(candidate(level, q, p.immutable(), stand.immutable(), side.getOpposite()));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::cost));
        String detail = "";
        int tested = 0;
        for (var c : candidates) {
            if (tested++ >= MAX_FOOTPRINT_TESTS) { count(counts, "untested"); break; }
            var fit = HullFootprint.fits(level, q.self() != null ? q.self() : captain, q.self(), c.center(), c.yaw(), q.hullScale(), 0);
            if (!fit.ok()) { count(counts, fit.reason()); if (detail.isEmpty()) detail = fit.detail() + " at bank " + c.stand().toShortString(); continue; }
            if (q.purpose() == Purpose.LAUNCH && captain != null && !captain.life().reachable(c.stand(), false)) {
                count(counts, "unreachable");
                if (detail.isEmpty()) detail = "bank " + c.stand().toShortString() + " cannot be walked to";
                continue;
            }
            return new Berth(c.stand(), c.water(), c.toWater(), c.standFeet(), c.center(), c.yaw(), c.inland(), c.along(), c.cost(),
                    captain == null ? List.of() : walkOff(captain, c));
        }
        String reason;
        if (!counts.containsKey("calm_water")) reason = "no_calm_water";
        else if (!counts.containsKey("bank_cells")) reason = "no_bank_beside_water";
        else if (candidates.isEmpty()) reason = "no_standable_bank";
        else if (counts.containsKey("unreachable") && counts.keySet().stream().noneMatch(k -> k.startsWith("hull_") || k.startsWith("water_"))) reason = "bank_unreachable";
        else reason = "hull_does_not_fit";
        return new Refusal(reason, detail, counts);
    }

    /** The hull pose lying alongside {@code stand}'s bank wall, from the brim's reach toward the water. */
    private static Candidate candidate(ServerLevel level, Query q, BlockPos water, BlockPos stand, Direction toWater) {
        var placement = HatVessel.placement(true, HatVessel.Role.SELF);
        var n = new Vec3(toWater.getStepX(), 0, toWater.getStepZ());
        float ownerYaw = (float) Math.toDegrees(Math.atan2(-n.x, n.z));
        float yaw = Mth.wrapDegrees(ownerYaw + placement.yaw());
        var standFeet = Vec3.atBottomCenterOf(stand);
        double distance = .5 + HullFootprint.reach(yaw, q.hullScale(), n.x, n.z) + CLEARANCE;
        double cx = standFeet.x + n.x * distance, cz = standFeet.z + n.z * distance;
        var centreCell = BlockPos.containing(cx, water.getY() + .5, cz);
        var centre = new Vec3(cx, HatBoatRoute.surface(level, centreCell).y, cz);
        var along = new Vec3(-n.z, 0, n.x);
        double cost;
        if (q.purpose() == Purpose.LAND) {
            var from = q.self() != null ? q.self().position() : Vec3.atCenterOf(q.near());
            double yawGap = q.self() != null ? Math.abs(Mth.wrapDegrees(yaw - q.self().getYRot())) / 90.0 : 0;
            int room = 0;
            if (q.captain() != null) for (int k = -2; k <= 2; k++) {
                var side = stand.relative(Direction.getNearest(along.x * k, 0, along.z * k));
                if (k == 0 || level.hasChunkAt(side) && q.captain().life().dryStand(side) || level.hasChunkAt(side.above()) && q.captain().life().dryStand(side.above())) room++;
            }
            cost = centre.distanceTo(from) + yawGap + (room < q.crew() + 1 ? 100 : 0) + hintBonus(q.hint(), stand);
        } else cost = stand.distSqr(q.near()) + hintBonus(q.hint(), stand);
        return new Candidate(stand, water, toWater, standFeet, centre, yaw, n.scale(-1), along, cost);
    }

    private static double hintBonus(BlockPos hint, BlockPos stand) {
        return hint == null ? 0 : hint.equals(stand) ? -20 : hint.distSqr(stand) <= 4 ? -4 : 0;
    }

    /** Standing places along the wall for riders stepping off, found with the captain's body as the probe. */
    private static List<Vec3> walkOff(YukkuriEntity captain, Candidate c) {
        var spots = new ArrayList<Vec3>();
        var wall = c.standFeet().add(c.inland().scale(-.5));
        double width = Math.max(captain.getBbWidth(), .2);
        for (int k = -3; k <= 3; k++) {
            var at = HatDock.snapToGround(captain, wall.add(c.along().scale(k * Math.max(.3, width * .9))).add(c.inland().scale(width * .5 + .22)));
            if (at != null && HatDock.standable(captain, at)) spots.add(at);
        }
        return List.copyOf(spots);
    }

    // ------------------------------------------------------------------------------------------------ open water

    private static Outcome openWater(ServerLevel level, Query q) {
        var counts = new LinkedHashMap<String, Integer>();
        boolean scenario = q.purpose() == Purpose.SCENARIO;
        double margin = scenario ? OPEN_MARGIN : 0;
        var cells = new ArrayList<BlockPos>();
        var origin = Vec3.atCenterOf(q.near());
        for (var p : BlockPos.betweenClosed(q.near().offset(-q.range(), -3, -q.range()), q.near().offset(q.range(), 3, q.range()))) {
            if (!level.hasChunkAt(p) || !HatBoatRoute.calmSurface(level, p) || !q.acceptWater().test(p)) continue;
            count(counts, "calm_water");
            double d = Math.hypot(p.getX() + .5 - origin.x, p.getZ() + .5 - origin.z);
            if (scenario && (d < 2 || d > q.range())) { count(counts, "out_of_band"); continue; }
            cells.add(p.immutable());
        }
        cells.sort(Comparator.comparingDouble(p -> scenario
                ? Math.abs(Math.hypot(p.getX() + .5 - origin.x, p.getZ() + .5 - origin.z) - 4) + Math.abs(p.getY() - q.near().getY() + 1) * 2
                : p.distSqr(q.near())));
        float baseYaw = q.captain() == null ? 0 : q.captain().yBodyRot;
        String detail = "";
        int tested = 0;
        for (var p : cells) {
            var feet = HatBoatRoute.surface(level, p);
            for (float turn : new float[]{0, 90}) {
                if (tested++ >= MAX_FOOTPRINT_TESTS) { count(counts, "untested"); return open(counts, detail, cells.isEmpty()); }
                float yaw = Mth.wrapDegrees(baseYaw + turn);
                var fit = HullFootprint.fits(level, q.captain(), q.self(), feet, yaw, q.hullScale(), margin);
                if (fit.ok()) return new Berth(null, p, null, null, feet, yaw, null, null, p.distSqr(q.near()), List.of());
                count(counts, fit.reason());
                if (detail.isEmpty()) detail = fit.detail() + " at water " + p.toShortString();
            }
        }
        return open(counts, detail, cells.isEmpty());
    }

    private static Refusal open(Map<String, Integer> counts, String detail, boolean noCells) {
        String reason = !counts.containsKey("calm_water") ? "no_calm_water" : noCells ? "no_water_in_band" : "hull_does_not_fit";
        return new Refusal(reason, detail, counts);
    }

    private static void count(Map<String, Integer> counts, String key) { counts.merge(key, 1, Integer::sum); }

    /**
     * Whether the specific bank/water pair is a berth for this hull, used when a caller has already chosen both (a captain
     * standing at the bank cell she walked to). Returns the same Berth or Refusal as {@link #find}.
     */
    public static Outcome berthAt(ServerLevel level, YukkuriEntity captain, float hullScale, BlockPos stand, BlockPos water, Purpose purpose) {
        var counts = new LinkedHashMap<String, Integer>();
        var toWater = Direction.fromDelta(water.getX() - stand.getX(), 0, water.getZ() - stand.getZ());
        if (toWater == null || water.getY() != stand.getY() - 1)
            return new Refusal("bank_water_not_adjacent", "bank " + stand.toShortString() + " water " + water.toShortString(), counts);
        if (!HatBoatRoute.calmSurface(level, water))
            return new Refusal("water_not_calm", "water " + water.toShortString(), counts);
        var q = new Query(captain, hullScale, stand, purpose, stand);
        var c = candidate(level, q, water, stand, toWater);
        var fit = HullFootprint.fits(level, captain, null, c.center(), c.yaw(), hullScale, 0);
        if (!fit.ok()) { count(counts, fit.reason()); return new Refusal("hull_does_not_fit", fit.detail(), counts); }
        return new Berth(stand, water, toWater, c.standFeet(), c.center(), c.yaw(), c.inland(), c.along(), c.cost(), List.of());
    }
}
