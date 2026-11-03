package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.life.SurfaceApproach;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.motor.action.Face;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;

/** A quiet seat pause then one bout of play, or cowering while danger lasts. No food transaction. */
final class PlayAboard implements Intent {
    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final String kind;
    private final Supplier<String> fear;
    private final String cause;
    private int ticks, restEnd;

    PlayAboard(YukkuriEntity body, PlacedHatEntity hat, String kind, Supplier<String> fear) {
        this.body = body;
        this.hat = hat;
        this.kind = kind;
        this.fear = fear;
        cause = hiding() ? fear.get() : "none";
    }

    static PlayAboard create(YukkuriEntity body, PlacedHatEntity hat, Supplier<String> fear) {
        return body.getVehicle() == hat ? new PlayAboard(body, hat, Behaviors.KID_POKE_STORES, fear) : null;
    }

    void tick() { if (!body.social().greeting()) ticks++; }

    static double number(String key) { return DriveWeights.of(AquaticGoal.DOMAIN, key); }
    private boolean hiding() { return kind.equals(Behaviors.KID_HIDE_BEHIND); }
    @Override public String id() { return hiding() ? "hide_aboard" : "play_aboard"; }
    @Override public long deadline() { return body.level().getGameTime() + (long) number("play_deadline") - ticks; }
    @Override public boolean satisfied() { return restEnd != 0 && ticks >= restEnd; }
    @Override public List<Method> methods() { return List.of(Plan.of(id(), this::derive)); }

    private List<Supplier<Step>> derive() {
        if (body.getVehicle() != hat || hat.isRemoved()) return null;
        return List.of(this::faceCaptain, this::pause, () -> new Step.Do(new Face(hat.getYRot())),
                this::perform, this::finish);
    }

    private Step faceCaptain() {
        var captain = hat.owner();
        return captain == null || !hat.settled(captain) || !hat.settled(body) ? null
                : new Step.Do(new Face(SurfaceApproach.yaw(body.position(), captain.position())));
    }

    private Step perform() {
        if (!(hat.behavior() instanceof HullAfloat hull) || body.getVehicle() != hat
                || onBrim(body, hat)) return finish();
        int duration = hiding() ? (int) number("play_deadline") - ticks
                : ClipMarkers.of(kind).length() * (int) number("play_loops");
        int until = ticks + duration;
        hull.playEvent(kind);
        if (hiding()) hull.playEvent("fear_" + cause);
        GameplayLog.limited(body, "aquatic", "play_start:" + kind, 100,
                "play kind={} start fear={} hat={}", kind, cause, hat.getUUID());
        return new Step.Do(Await.holding(() -> (hiding() ? fear.get().equals("none") : ticks >= until)
                || kind.equals(Behaviors.KID_POKE_STORES) && HullLarder.of(hat).count() == 0, duration + 1, kind,
                hiding() ? Math.round(ClipMarkers.of(kind).markers().get("tucked") * 20) : duration, body.social()::greeting));
    }

    private Step pause() {
        if (hiding() || onBrim(body, hat)) return null;
        int duration = ClipMarkers.of(Behaviors.HAT_CRADLE).length() * (int) number("play_rest_loops");
        int until = ticks + duration;
        return new Step.Do(Await.holding(() -> ticks >= until, duration + 1, Behaviors.HAT_CRADLE, duration, body.social()::greeting));
    }

    private Step finish() {
        restEnd = ticks;
        return new Step.Wait(this::satisfied, 1, Behaviors.HAT_CRADLE);
    }

    @Override public void ended(IntentRunner.Result result) {
        GameplayLog.limited(body, "aquatic", "play_end:" + kind, 100,
                "play kind={} end result={} fear={}", kind, result, cause);
    }

    static boolean onBrim(YukkuriEntity body, PlacedHatEntity hat) {
        return !hat.insideFloor(hat.riders().local(body.getUUID()));
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
