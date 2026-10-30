package io.github.longshou.mcyukkuri.aquatic;

import io.github.longshou.mcyukkuri.entity.Behaviors;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.intent.Intent;
import io.github.longshou.mcyukkuri.intent.Method;
import io.github.longshou.mcyukkuri.intent.Step;
import io.github.longshou.mcyukkuri.motor.action.Paddle;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;

/**
 * Out on the water (水上生活): one stretch of the afloat routine. With her stick and the energy for it
 * she paddles ({@code Paddle}, paying energy each stroke) to calm open water a few blocks off over the loaded calm surface, then
 * drifts and rests ({@code boat_drift}); without them she only drifts. Done when the rest is over; the drive then weighs her
 * wants again.
 */
final class Cruise implements Intent {
    /** One stretch at most; one rest (ticks); the least energy she paddles on; the open water she heads for (blocks off). */
    private static final int DEADLINE = 900, REST = 200, MIN = 4, MAX = 10;
    private static final double ENERGY = 30, ARRIVE = .8;

    private final YukkuriEntity owner;
    private final long started;
    private BlockPos goal;
    private boolean planned;
    private long restEnd;

    Cruise(YukkuriEntity owner) {
        this.owner = owner;
        this.started = owner.level().getGameTime();
    }

    @Override public String id() { return "cruise"; }
    @Override public long deadline() { return started + DEADLINE; }
    @Override public boolean satisfied() { return restEnd > 0 && owner.level().getGameTime() >= restEnd; }
    @Override public List<Method> methods() { return List.of(Plan.of("paddle_and_drift", this::derive)); }

    private List<Supplier<Step>> derive() {
        var hat = AquaticLife.hat(owner);
        if (!AquaticLife.aboard(owner, hat) || !AquaticLife.afloat(hat)) return null;
        if (!planned) {
            planned = true;
            if (AquaticLife.stick(owner) && owner.life().energy() >= ENERGY) goal = Waters.wander(hat, MIN, MAX);
        }
        if (goal != null && Waters.surface(owner.level(), goal).subtract(hat.position()).horizontalDistance() > ARRIVE) {
            var route = Waters.route(hat, goal);
            Waters.log(hat, owner, goal, route, "cruise");
            if (!route.isEmpty()) return List.of(() -> new Step.Do(new Paddle(Waters.leg(route), ARRIVE)));
            goal = null;
        }
        if (restEnd == 0) restEnd = owner.level().getGameTime() + REST;
        return List.of(() -> new Step.Wait(this::satisfied, REST + 20, Behaviors.BOAT_DRIFT));
    }
}
