package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.headwear.HullBounds;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.Motor;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.motor.action.Hop;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;

/** One bout of play, or cowering while danger lasts, followed by a quiet pause. No food transaction. */
final class PlayAboard implements Intent {
    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final String kind;
    private final long started;
    private long restEnd;

    PlayAboard(YukkuriEntity body, PlacedHatEntity hat, String kind) {
        this.body = body;
        this.hat = hat;
        this.kind = kind;
        started = body.level().getGameTime();
    }

    static double number(String key) { return DriveWeights.of(AquaticGoal.DOMAIN, key); }
    private boolean hiding() { return kind.equals(Behaviors.KID_HIDE_BEHIND); }
    @Override public String id() { return hiding() ? "hide_aboard" : "play_aboard"; }
    @Override public long deadline() { return started + (long) number("play_deadline"); }
    @Override public boolean satisfied() { return restEnd != 0 && body.level().getGameTime() >= restEnd; }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        if (body.getVehicle() != hat || hat.isRemoved()) return null;
        return List.of(this::outbound, () -> new Step.Do(new Face(hat.getYRot() - (float) bearing())),
                this::perform, this::returnSeat, this::rest);
    }

    private boolean brimPlay() { return hiding() || kind.equals(Behaviors.KID_BRIM_SPLASH); }
    private double bearing() { return brimPlay() ? number(hiding() ? "play_hide_bearing" : "play_splash_bearing") : 0; }

    private Step outbound() {
        if (!brimPlay()) return returnSeat();
        if (!AquaticLife.jumper(body) || !hat.steady()) return null;
        var p = HullBounds.brim(body.growthScale() * body.getScale() * HullBounds.SCALE / hat.hullScale(), Math.toRadians(bearing()));
        var local = new Vec3(p.x(), p.y(), p.z()).scale(1 / HullBounds.UNIT);
        return hat.riders().local(body.getUUID()).distanceTo(local) * hat.unit() < .05 ? null : new Step.Do(new Hop(hat, local));
    }

    private Step returnSeat() {
        if (!onBrim(body, hat) || hiding() && !fear(body, hat).equals("none")) return null;
        if (hat.seatDestination(body) != null) return new Step.Do(new Hop(hat));
        GameplayLog.limited(body, "aquatic", "play_no_seat", 100, "play return blocked reason=no_seat hat={}", hat.getUUID());
        return null;
    }

    private Step perform() {
        if (!brimPlay() && onBrim(body, hat)) return rest();
        int ticks = ClipMarkers.of(kind).length() * (int) number(hiding() ? "hide_loops" : "play_loops");
        long until = body.level().getGameTime() + ticks;
        var hull = (HullAfloat) hat.behavior();
        hull.playEvent(kind);
        if (hiding()) hull.playEvent("fear_" + fear(body, hat));
        GameplayLog.limited(body, "aquatic", "play_start:" + kind, 100,
                "play kind={} start fear={} hat={}", kind, fear(body, hat), hat.getUUID());
        return new Step.Do(Await.holding(() -> body.level().getGameTime() >= until
                || hiding() && fear(body, hat).equals("none")
                || kind.equals(Behaviors.KID_MIMIC_PADDLE) && (hat.owner() == null || !(hat.owner().motor().current() instanceof Paddle))
                || kind.equals(Behaviors.KID_POKE_STORES) && HullLarder.of(hat).count() == 0, ticks + 1, kind,
                hiding() ? Math.round(ClipMarkers.of(kind).markers().get("tucked") * 20) : ticks));
    }

    private Step rest() {
        int ticks = hiding() && !fear(body, hat).equals("none") ? 1
                : ClipMarkers.of(Behaviors.HAT_CRADLE).length() * (int) number("play_rest_loops");
        restEnd = body.level().getGameTime() + ticks;
        return new Step.Wait(this::satisfied, ticks + 1, Behaviors.HAT_CRADLE);
    }

    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.limited(body, "aquatic", "play_end:" + kind, 100,
                "play kind={} end result={} fear={}", kind, result, fear(body, hat));
    }

    static boolean onBrim(YukkuriEntity body, PlacedHatEntity hat) {
        return hat.riders().local(body.getUUID()).horizontalDistance() * HullBounds.UNIT >= HullBounds.MOUTH;
    }

    /** Called by the young's own goal, never by the hull's passive clock. */
    static boolean fall(YukkuriEntity body) {
        if (body.family().adult() || !(body.getVehicle() instanceof PlacedHatEntity hat)
                || !(hat.behavior() instanceof HullAfloat hull) || !hull.brimFall() || !onBrim(body, hat)) return false;
        var outward = body.position().subtract(hat.position()).multiply(1, 0, 1).normalize();
        Motor.dismount(body, body.position(), hat.getDeltaMovement().add(outward.scale(HullPresentation.number("throw_horizontal"))), "brim_hard_rock");
        hull.playEvent("fall_ins");
        GameplayLog.event(body, "aquatic", "play fall_in cause=hard_rock hat={}", hat.getUUID());
        return true;
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
