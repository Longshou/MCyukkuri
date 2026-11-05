package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.ParentCarry;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.ContactGeometry;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.motor.surface.SeatTarget;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A young plays where a player can see it: from the hollow it hops out onto the nearest brim place its own hop reaches and plays
 * there; on the head it only looks about. A water act (splash, or copying the captain's paddle) needs a braided body and real water
 * at the level of its own place, so it happens on the brim with the braid side turned out over the water and never on the head. A
 * scared young goes to the safest place that really exists, a free cabin place it can hop into (her head is set only by her braid);
 * with none it holds on where it is and shrinks, and it never waits for a place. No food transaction.
 */
final class PlayAboard implements Intent {
    /** How far outside its place the water is looked for, in what steps, and how far off the place's own level it may lie (blocks). */
    private static final double WATER_REACH = .3, WATER_STEP = .06, WATER_BAND = .25;
    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final Supplier<String> fear;
    private final boolean scared;
    private final String cause;
    private final long started;
    private Hop outHop;
    private String act, where;
    private boolean ended;

    private PlayAboard(YukkuriEntity body, PlacedHatEntity hat, Supplier<String> fear, boolean scared) {
        this.body = body;
        this.hat = hat;
        this.fear = fear;
        this.scared = scared;
        cause = scared ? fear.get() : "none";
        started = body.level().getGameTime();
    }

    /** One bout of play where this young can really be seen. */
    static PlayAboard create(YukkuriEntity body, PlacedHatEntity hat, Supplier<String> fear) { return new PlayAboard(body, hat, fear, false); }

    /** Cowering while the danger lasts. */
    static PlayAboard shrink(YukkuriEntity body, PlacedHatEntity hat, Supplier<String> fear) { return new PlayAboard(body, hat, fear, true); }

    static double number(String key) { return DriveWeights.of(AquaticGoal.DOMAIN, key); }
    @Override public String id() { return scared ? "hide_aboard" : "play_aboard"; }
    @Override public long deadline() { return started + (long) number("play_deadline"); }
    @Override public boolean satisfied() { return ended; }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private long now() { return body.level().getGameTime(); }

    /** Where this young sits on its parent's boat: in the hollow, on the head, or on the brim ring. */
    static String place(YukkuriEntity body, PlacedHatEntity hat) {
        if (body.getVehicle() instanceof YukkuriEntity carrier && carrier == hat.placeCarrier()
                && ParentCarry.hold(carrier, body) == ParentCarry.Hold.HEAD) return "head";
        if (body.getVehicle() != hat) return null;
        return hat.insideFloor(hat.riders().local(body.getUUID())) ? "cabin" : "brim";
    }

    /** A young the player can watch play: on the brim, on the head, or in the hollow where the riders leave it a cabin place of its
     *  own and a brim place its own hop out reaches (the same admissions a taken seat and the hop read). */
    static boolean canPlay(YukkuriEntity body, PlacedHatEntity hat) {
        var place = place(body, hat);
        if (!body.childhood().canExercise()) return false;
        if ("cabin".equals(place)) return !hat.places(body, SeatTarget.Kind.CABIN, 1).isEmpty() && hopOut(body, hat) != null;
        return "brim".equals(place) || "head".equals(place);
    }

    static boolean onBrim(YukkuriEntity body, PlacedHatEntity hat) { return "brim".equals(place(body, hat)); }

    private List<Supplier<Step>> derive() {
        var place = place(body, hat);
        if (place == null) return null;
        if (scared) return List.of(this::inside, this::perform, this::finish);
        if ("cabin".equals(place)) {
            outHop = hopOut(body, hat);
            if (outHop == null) {
                GameplayLog.limited(body, "aquatic", "play_hop:none", 100, "play refused hat={} reason=no_brim_place_in_hop_reach", hat.getUUID());
                return null;
            }
        }
        return List.of(this::out, this::perform, this::quiet, this::inside, this::finish);
    }

    /** The brim place nearest this young that its own hop out of the hollow reaches (turn included). */
    private static Hop hopOut(YukkuriEntity body, PlacedHatEntity hat) {
        var here = hat.riders().local(body.getUUID());
        return hat.places(body, SeatTarget.Kind.BRIM, Integer.MAX_VALUE).stream()
                .sorted(Comparator.comparingDouble(place -> place.local().distanceToSqr(here)))
                .map(place -> new Hop(hat, place.local())).filter(hop -> hop.check(body).ok()).findFirst().orElse(null);
    }

    private Step out() { return outHop == null || !"cabin".equals(place(body, hat)) ? null : new Step.Do(outHop); }

    /** The act this young really performs where it now stands. */
    private Step perform() {
        if (act != null) return null;
        where = place(body, hat);
        var out = outward();
        boolean water = !scared && "brim".equals(where) && out != null && ContactGeometry.braided(body) && wet(out);
        // Neither the hollow nor the brim ring admits a walking turn, so the braid side is set over the water with the motor's own turn writer.
        if (water) Motor.face(body, braidOutYaw(out));
        boolean braidOut = water && Math.abs(Mth.wrapDegrees(braidOutYaw(out) - body.yBodyRot)) <= Face.FACE_ARRIVED;
        boolean paddling = water && hat.owner() != null && hat.owner().motor().current() instanceof Paddle;
        String reason;
        if (scared) { act = Behaviors.KID_HIDE_BEHIND; reason = "fear:" + cause; }
        else if (water) { act = paddling ? Behaviors.KID_MIMIC_PADDLE : Behaviors.KID_BRIM_SPLASH; reason = paddling ? "captain_paddle" : "brim_water"; }
        else { act = Behaviors.BOAT_RIM_PEEK; reason = "head".equals(where) ? "head_dry" : "no_water_act"; }
        if (hat.behavior() instanceof HullAfloat hull) {
            hull.playEvent(act);
            if (scared) hull.playEvent("fear_" + cause);
        }
        GameplayLog.event(body, "aquatic", "play kind={} start spot={} reason={} braid_out={} hat={}", act, where, reason, braidOut, hat.getUUID());
        if (scared) return new Step.Do(Await.holding(() -> fear.get().equals("none"), (int) number("play_deadline") + 1, act,
                ClipMarkers.of(act).ticks("tucked", 12), () -> false));
        return pause(act, (int) number("play_loops"));
    }

    /** A quiet pause on the spot once the act is over: the young keeps playing where it can be seen. */
    private Step quiet() { return pause(Behaviors.HAT_CRADLE, (int) number("play_rest_loops")); }

    private Step pause(String clip, int loops) {
        int ticks = ClipMarkers.of(clip).length() * loops;
        long until = now() + ticks;
        return new Step.Do(new Await(() -> now() >= until, ticks + 1, clip));
    }

    /**
     * Fright, or a gasp after the water, takes a young on the brim to a free cabin place its own hop reaches. With none it stays
     * and holds on while the parent's ordinary care can lift it to her free head. Nothing here waits for a place.
     */
    private Step inside() {
        if (!"brim".equals(place(body, hat)) || !scared && !Care.upset(body)) return null;
        String why = scared ? "fear:" + cause : "care";
        var cabin = hat.places(body, SeatTarget.Kind.CABIN, 1);
        var hop = cabin.isEmpty() ? null : new Hop(hat, cabin.getFirst().local());
        var check = hop == null ? null : hop.check(body);
        if (check != null && check.ok()) {
            GameplayLog.event(body, "aquatic", "play return inside hat={} place=cabin reason={}", hat.getUUID(), why);
            return new Step.Do(hop);
        }
        GameplayLog.event(body, "aquatic", "play stays on brim hat={} cabin={} head={} reason={}", hat.getUUID(),
                check == null ? "none" : check.reason(), hat.places(body, SeatTarget.Kind.HEAD, 1).isEmpty() ? "none" : "free", why);
        return null;
    }

    private Step finish() {
        ended = true;
        return new Step.Wait(this::satisfied, 1, act == null ? Behaviors.HAT_CRADLE : act);
    }

    @Override public void ended(IntentRunner.Result result) {
        if (act != null) GameplayLog.event(body, "aquatic", "play kind={} end spot={} result={} fear={} hat={}",
                act, where, result, fear.get(), hat.getUUID());
    }

    /** Real water at the level of this young's own place just outside it: what a water act needs, and what the head never has. */
    private boolean wet(Vec3 out) {
        var at = body.position();
        for (double step = WATER_STEP; step <= WATER_REACH + 1e-6; step += WATER_STEP) {
            double y = HullAfloat.surface(body.level(), at.x + out.x * step, at.y - .1, at.z + out.z * step);
            if (Math.abs(y - at.y) <= WATER_BAND) return true;
        }
        return false;
    }

    /** The level way out from the hull's middle through where this young stands, or null at the middle itself. */
    private Vec3 outward() {
        var away = body.position().subtract(hat.position()).multiply(1, 0, 1);
        return away.lengthSqr() < 1e-8 ? null : away.normalize();
    }

    /** The yaw that turns the braid side along {@code out}: the bearing less the braid root's own angle off the face, as {@code braidYaw} has it. */
    private static float braidOutYaw(Vec3 out) {
        var root = ContactGeometry.braidRootModel(false);
        return (float) (Math.toDegrees(Math.atan2(out.z, out.x)) - 90 - Math.toDegrees(Math.atan2(root.x, -root.z)));
    }

    static String fear(YukkuriEntity body, PlacedHatEntity hat) {
        if (!hat.steady()) return "hard_rock";
        if (body.level().isThundering()) return "thunder";
        double range = number("play_fear_range");
        if (!body.level().getEntitiesOfClass(YukkuriEntity.class, hat.getBoundingBox().inflate(range),
                y -> y.isAlive() && y.getVariant().isPredator() && body.hasLineOfSight(y)).isEmpty()) return "predator";
        for (var player : body.level().players()) {
            if (player.isSpectator() || !player.isSprinting() || player.distanceToSqr(hat) > range * range) continue;
            if (player.getLookAngle().dot(hat.position().subtract(player.position())) > 0) return "rushing_player";
        }
        return "none";
    }
}
