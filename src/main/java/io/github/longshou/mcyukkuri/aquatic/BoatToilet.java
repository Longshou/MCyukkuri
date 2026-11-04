package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.Desire;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.life.ToiletContact;
import io.github.longshou.mcyukkuri.motor.BodyAbility;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.Vec;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A bladder that has to go (项目设计, user 2026-11-05 #7): the seated owner turns in the hollow so her rear faces open water over the
 * rim, tips onto her front and raises her rear toward that side (the {@code boat_toilet} clip); a young first hops onto a brim place
 * (front of the captain first, as every brim seat), turns its rear out over the water and does the same, then goes back by the
 * ordinary seat rule. At the clip's drop tick the stool leaves the very place the client draws it leaving the rear
 * (ToiletContact.release) along its own direction, pushed just hard enough that its real item flight clears the hat and comes down
 * in the water beyond the rim ({@link #plan}); a rear side that no such push serves is not chosen.
 */
final class BoatToilet implements Intent {
    /** Open water has to lie within this far (blocks) beyond the rim in the chosen direction; the stool comes down inside that band. */
    private static final double CLEAR = .3;
    private static final int TURN_DEADLINE = 80;

    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final long started;
    private Hop hop;
    private Vec3 out;
    private boolean strained, relieved;
    /** The stool this intent let go, watched only to report where it really came down. */
    private ItemEntity stool;

    private BoatToilet(YukkuriEntity body, PlacedHatEntity hat) {
        this.body = body;
        this.hat = hat;
        started = body.level().getGameTime();
    }

    /** The want, from the real bladder and a place this body can really use. */
    static void offer(List<Desire> out, YukkuriEntity body, PlacedHatEntity hat) {
        var life = body.life();
        double from = AquaticGoal.num("toilet_from", 70), bladder = life.bladder();
        if (bladder < from || !body.childhood().firstToiletDone() || life.constipated() || body.leafSwaddle().wrapped()
                || life.isSleeping() || life.mealTicks() > 0 || body.family().birthRole() != null || !hat.steady()) return;
        var place = PlayAboard.place(body, hat);
        boolean adult = body.family().adult();
        if (adult ? !"cabin".equals(place) : !("brim".equals(place) || "cabin".equals(place) && AquaticLife.jumper(body))) return;
        double want = AquaticGoal.num("toilet_want", 40) + (bladder - from) * AquaticGoal.num("toilet_slope", 2);
        out.add(Desire.of("boat_toilet", want, List.of(hat.getUUID(), place), () -> String.format(Locale.ROOT, "bladder=%.0f place=%s", bladder, place),
                () -> new BoatToilet(body, hat)));
    }

    @Override public String id() { return "boat_toilet"; }
    @Override public long deadline() { return started + (long) AquaticGoal.num("toilet_deadline", 600); }
    @Override public boolean satisfied() { return relieved && (body.family().adult() || "cabin".equals(PlayAboard.place(body, hat))); }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        var place = PlayAboard.place(body, hat);
        if (strained || place == null || "head".equals(place)) return null;
        if ("cabin".equals(place) && !body.family().adult()) {
            hop = brimHop();
            if (hop == null) {
                GameplayLog.limited(body, "aquatic", "toilet_hop:none", 200, "boat_toilet refused hat={} reason=no_brim_place_in_hop_reach", hat.getUUID());
                return null;
            }
            return List.of(this::up, this::turn, this::strain, this::back);
        }
        return List.of(this::turn, this::strain, this::back);
    }

    /** A brim place with open water beyond it that its own hop out of the hollow reaches. */
    private Hop brimHop() {
        return hat.places(body, SeatTarget.Kind.BRIM, Integer.MAX_VALUE).stream()
                .filter(place -> {
                    var level = levelDir(place);
                    return level != null && open(level);
                })
                .map(place -> new Hop(hat, place.local())).filter(h -> h.check(body).ok()).findFirst().orElse(null);
    }

    private Vec3 levelDir(SeatTarget place) {
        var away = place.world().subtract(hat.position()).multiply(1, 0, 1);
        return away.lengthSqr() < 1e-8 ? null : away.normalize();
    }

    private Step up() { return "cabin".equals(PlayAboard.place(body, hat)) ? new Step.Do(hop) : null; }

    /** Rear to open water: the owner in the hollow takes the water side she turns least to; a young on the brim looks in from where it stands. */
    private Step turn() {
        out = outward();
        if (out == null) {
            GameplayLog.limited(body, "aquatic", "toilet_water:none", 200, "boat_toilet refused hat={} reason=no_open_water_throw_beyond_rim", hat.getUUID());
            return null;
        }
        float yaw = yawToward(out.reverse());
        if (Math.abs(Mth.wrapDegrees(yaw - body.yBodyRot)) <= Face.FACE_ARRIVED) return null;
        GameplayLog.event(body, "aquatic", "boat_toilet turn hat={} place={} rear_out={} yaw={}", hat.getUUID(), PlayAboard.place(body, hat),
                GameplayLog.at(out), Math.round(yaw));
        // The hollow admits a real turn; the brim ring does not, so the young turns by the motor's own writer at its own rate.
        return "brim".equals(PlayAboard.place(body, hat)) ? new Step.Do(new Await(() -> turned(yaw), TURN_DEADLINE, Behaviors.HAT_CRADLE, "toilet_turn"))
                : new Step.Do(new Face(yaw));
    }

    private boolean turned(float yaw) {
        double left = Mth.wrapDegrees(yaw - body.yBodyRot);
        if (Math.abs(left) <= Face.FACE_ARRIVED) return true;
        Motor.face(body, body.yBodyRot + (float) Math.copySign(Math.min(Math.abs(left), BodyAbility.of(body).turnRate()), left));
        return false;
    }

    /** The straining clip; the stool is let go at its drop tick, and only that real release empties the bladder. */
    private Step strain() {
        if (out == null) return null;
        strained = true;
        int[] tick = {0};
        double before = body.life().bladder();
        return new Step.Do(new Await(() -> {
            if (++tick[0] == ToiletContact.DROP_TICKS) relieved = drop(before);
            if (tick[0] == ToiletContact.RELEASE_TICKS) landed();
            return tick[0] >= ToiletContact.RELEASE_TICKS;
        }, ToiletContact.RELEASE_TICKS + 10, Behaviors.BOAT_TOILET));
    }

    private boolean drop(double before) {
        var plan = plan(out, body.yBodyRot);
        var middle = hat.position();
        stool = plan == null ? null : body.life().relieveOutboard(plan.release().at(), plan.push());
        if (stool == null) {
            GameplayLog.event(body, "aquatic", "boat_toilet refused hat={} place={} reason={} bladder={}", hat.getUUID(), PlayAboard.place(body, hat),
                    plan == null ? "no_throw_beyond_rim" : "not_released", Math.round(before));
            return false;
        }
        var at = plan.release().at();
        GameplayLog.event(body, "aquatic", "boat_toilet dropped hat={} place={} at={} release_distance={} push={} lands={} land_distance={} air_ticks={} bladder={} -> {}",
                hat.getUUID(), PlayAboard.place(body, hat), GameplayLog.at(at), distance(at, middle),
                String.format(Locale.ROOT, "%.3f", Math.sqrt(plan.push().lengthSqr())), GameplayLog.at(plan.lands()), distance(plan.lands(), middle),
                plan.ticks(), Math.round(before), Math.round(body.life().bladder()));
        return true;
    }

    /** Where the released stool really is when the clip ends (its flight lasts a few ticks): outside the rim and in the water, or not. */
    private void landed() {
        if (stool == null) return;
        GameplayLog.event(body, "aquatic", "boat_toilet stool hat={} at={} hull_distance={} in_water={} removed={}", hat.getUUID(),
                GameplayLog.at(stool.position()), distance(stool.position(), hat.position()), stool.isInWater(), stool.isRemoved());
        stool = null;
    }

    private String distance(Vec3 at, Vec3 middle) {
        return String.format(Locale.ROOT, "%.2f/%.2f", Math.hypot(at.x - middle.x, at.z - middle.z), rim());
    }

    /** One release: the stool's place and direction at the drop tick, the push it leaves with, where it comes down and after how many ticks. */
    private record Throw(ToiletContact.Release release, Vec3 push, Vec3 lands, int ticks) {}

    /**
     * The stool's way out for a rear turned to {@code out} with the body at {@code yaw}: released where the stool really is at the clip's
     * drop tick, along its own direction, with the slowest push whose vanilla item path keeps it (its half length either way) above the
     * hull's surface and brings it down on the water beyond the rim, within the open band (Ballistic.itemPush); null when no push the body
     * can give does. The choice of the rear side (outward) and the release itself (drop) both ask this.
     */
    private Throw plan(Vec3 out, float yaw) {
        var release = ToiletContact.release(body, Behaviors.BOAT_TOILET, yaw);
        var middle = hat.position();
        double rim = rim(), water = HullAfloat.surface(body.level(), middle.x + out.x * rim, middle.y - 2, middle.z + out.z * rim);
        if (release == null || Double.isNaN(water)) return null;
        var at = release.at();
        double half = release.length() / 2, drop = water - at.y;
        var dir = new Vec(release.direction().x, release.direction().y, release.direction().z);
        var push = Ballistic.itemPush(dir, drop, points -> clears(at, points, half, rim));
        if (push == null) return null;
        var path = Ballistic.itemPath(push, drop);
        var last = path.getLast();
        return new Throw(release, new Vec3(push.x(), push.y(), push.z()), at.add(last.x(), last.y(), last.z()), path.size() - 1);
    }

    /** The path from {@code at} (each tick's chord in quarters; a falling curve lies above its chords) clears the hull and ends on open water past the rim. */
    private boolean clears(Vec3 at, List<Vec> points, double half, double rim) {
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            for (int quarter = 0; quarter < 4; quarter++) {
                double q = quarter / 4.0;
                if (!aboveHull(at.add(a.x() + (b.x() - a.x()) * q, a.y() + (b.y() - a.y()) * q, a.z() + (b.z() - a.z()) * q), half)) return false;
            }
        }
        var end = at.add(points.getLast().x(), points.getLast().y(), points.getLast().z());
        double reach = Math.hypot(end.x - hat.getX(), end.z - hat.getZ());
        return reach - half >= rim && reach <= rim + CLEAR;
    }

    /** A point (the stool's bottom) is above the hull's own surface across its half length either way; past the rim there is no hull. */
    private boolean aboveHull(Vec3 world, double half) {
        var local = hat.toLocal(world);
        double scale = hat.hullScale() / HullBounds.SCALE, radius = Math.hypot(local.x, local.z) * HullBounds.UNIT, height = local.y * HullBounds.UNIT;
        for (double r : new double[]{Math.max(0, radius - half / scale), radius, radius + half / scale}) {
            double surface = HullBounds.surface(r);
            if (!Double.isNaN(surface) && height < surface) return false;
        }
        return true;
    }

    /** Back into the hollow the way it hopped out (the ordinary hop to a cabin place); the owner never left hers. */
    private Step back() {
        if (!"brim".equals(PlayAboard.place(body, hat))) return null;
        var cabin = hat.places(body, SeatTarget.Kind.CABIN, 1);
        var hop = cabin.isEmpty() ? null : new Hop(hat, cabin.getFirst().local());
        var check = hop == null ? null : hop.check(body);
        if (check != null && check.ok()) {
            GameplayLog.event(body, "aquatic", "boat_toilet back hat={} place=cabin", hat.getUUID());
            return new Step.Do(hop);
        }
        GameplayLog.limited(body, "aquatic", "toilet_back:none", 200, "boat_toilet stays on brim hat={} reason={}", hat.getUUID(),
                check == null ? "no_cabin_place" : check.reason());
        return null;
    }

    /** The hull's outer rim (blocks) at this hat's size. */
    private double rim() { return HullBounds.RADIUS * hat.hullScale() / HullBounds.SCALE; }

    /** The level direction from the hull's middle toward water this body's rear can face and its stool can reach, or null. */
    private Vec3 outward() {
        if ("brim".equals(PlayAboard.place(body, hat))) {
            var away = body.position().subtract(hat.position()).multiply(1, 0, 1);
            return away.lengthSqr() < 1e-8 || !usable(away.normalize()) ? null : away.normalize();
        }
        // The owner turns least: the water sides in the order of her turn, the first whose stool clears the rim.
        return IntStream.range(0, 16).mapToObj(k -> new Vec3(Math.cos(k * Math.PI / 8), 0, Math.sin(k * Math.PI / 8)))
                .sorted(Comparator.comparingDouble(dir -> Math.abs(Mth.wrapDegrees(yawToward(dir.reverse()) - body.yBodyRot))))
                .filter(this::usable).findFirst().orElse(null);
    }

    /** Open water beyond the rim on that side, and a stool released with the rear turned there comes down in it. */
    private boolean usable(Vec3 dir) { return open(dir) && plan(dir, yawToward(dir.reverse())) != null; }

    /** Water at the level of the hull's own surface just beyond the rim. */
    private boolean open(Vec3 dir) {
        var at = hat.position();
        for (double reach = rim(); reach <= rim() + CLEAR + 1e-6; reach += .09) {
            double y = HullAfloat.surface(body.level(), at.x + dir.x * reach, at.y - 2, at.z + dir.z * reach);
            if (Double.isNaN(y) || Math.abs(y - at.y) > 1) return false;
        }
        return true;
    }

    private static float yawToward(Vec3 dir) { return (float) (Math.toDegrees(Math.atan2(dir.z, dir.x)) - 90); }

    @Override public void ended(IntentRunner.Result result) {
        if (strained) GameplayLog.event(body, "aquatic", "boat_toilet end hat={} result={} relieved={}", hat.getUUID(), result, relieved);
    }
}
