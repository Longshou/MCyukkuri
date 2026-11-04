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
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A bladder that has to go (项目设计): the seated owner turns in the hollow so her rear faces open water over the rim and strains
 * there; a young first hops onto a brim place (front of the captain first, as every brim seat), turns its rear out over the water
 * and strains the same way, then goes back by the ordinary seat rule. The stool leaves the model's own outlet at the clip's drop tick;
 * when that point is still over the hull it falls just outside the rim, into the water, never into the hat.
 */
final class BoatToilet implements Intent {
    /** Open water has to lie this far (blocks) beyond the rim in the chosen direction; the stool falls this far outside it. */
    private static final double CLEAR = .3, OUTSIDE = .12;
    private static final int TURN_DEADLINE = 80;

    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final long started;
    private Hop hop;
    private Vec3 out;
    private boolean strained, relieved;

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
            GameplayLog.limited(body, "aquatic", "toilet_water:none", 200, "boat_toilet refused hat={} reason=no_open_water_beyond_rim", hat.getUUID());
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
            return tick[0] >= ToiletContact.RELEASE_TICKS;
        }, ToiletContact.RELEASE_TICKS + 10, Behaviors.BOAT_TOILET));
    }

    private boolean drop(double before) {
        var real = ToiletContact.anus(body, ToiletContact.DROP_TICKS);
        var middle = hat.position();
        var away = new Vec3(real.x - middle.x, 0, real.z - middle.z);
        double rim = rim() + OUTSIDE;
        var outlet = real;
        if (away.length() < rim) {
            var dir = away.lengthSqr() < 1e-6 ? out : away.normalize();
            double water = HullAfloat.surface(body.level(), middle.x + dir.x * rim, middle.y - 2, middle.z + dir.z * rim);
            outlet = new Vec3(middle.x + dir.x * rim, Double.isNaN(water) ? real.y : Math.max(real.y, water + .05), middle.z + dir.z * rim);
        }
        boolean done = body.life().relieveOutboard(outlet);
        GameplayLog.event(body, "aquatic", "boat_toilet {} hat={} place={} at={} hull_distance={} outlet_distance={} bladder={} -> {}", done ? "dropped" : "refused",
                hat.getUUID(), PlayAboard.place(body, hat), GameplayLog.at(outlet),
                String.format(Locale.ROOT, "%.2f/%.2f", Math.hypot(outlet.x - middle.x, outlet.z - middle.z), rim()),
                String.format(Locale.ROOT, "%.2f/%.2f", Math.hypot(real.x - middle.x, real.z - middle.z), rim()),
                Math.round(before), Math.round(body.life().bladder()));
        return done;
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

    /** The level direction from the hull's middle toward water this body's rear can face, or null. */
    private Vec3 outward() {
        if ("brim".equals(PlayAboard.place(body, hat))) {
            var away = body.position().subtract(hat.position()).multiply(1, 0, 1);
            return away.lengthSqr() < 1e-8 || !open(away.normalize()) ? null : away.normalize();
        }
        Vec3 best = null;
        double least = Double.MAX_VALUE;
        for (int k = 0; k < 16; k++) {
            var dir = new Vec3(Math.cos(k * Math.PI / 8), 0, Math.sin(k * Math.PI / 8));
            double turn = Math.abs(Mth.wrapDegrees(yawToward(dir.reverse()) - body.yBodyRot));
            if (turn < least && open(dir)) { best = dir; least = turn; }
        }
        return best;
    }

    /** Water at the level of the hull's own surface just beyond the rim. */
    private boolean open(Vec3 dir) {
        var at = hat.position();
        for (double reach = rim() + OUTSIDE; reach <= rim() + CLEAR + 1e-6; reach += .09) {
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
