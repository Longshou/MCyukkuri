package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.headwear.PlacedHatEntity;
import io.github.longshou.mcyukkuri.intent.DriveWeights;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.IntentRunner;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;
import io.github.longshou.mcyukkuri.motor.action.Await;
import io.github.longshou.mcyukkuri.status.GameplayLog;
import java.util.List;
import java.util.function.Supplier;

/** One bout of play, or cowering while danger lasts, followed by a quiet pause. No food transaction. */
final class PlayAboard implements Intent {
    private final YukkuriEntity body;
    private final PlacedHatEntity hat;
    private final String kind;
    private final Runnable ended;
    private final long started;
    private long restEnd;

    PlayAboard(YukkuriEntity body, PlacedHatEntity hat, String kind, Runnable ended) {
        this.body = body;
        this.hat = hat;
        this.kind = kind;
        this.ended = ended;
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
        return List.of(this::perform, this::rest);
    }

    private Step perform() {
        int ticks = ClipMarkers.of(kind).length() * (int) number(hiding() ? "hide_loops" : "play_loops");
        long until = body.level().getGameTime() + ticks;
        GameplayLog.limited(body, "aquatic", "play_start:" + kind, 100,
                "play kind={} start fear={} hat={}", kind, fear(body, hat), hat.getUUID());
        return new Step.Do(Await.holding(() -> body.level().getGameTime() >= until
                || hiding() && fear(body, hat).equals("none"), ticks + 1, kind,
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
        ended.run();
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
