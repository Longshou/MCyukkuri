package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import java.util.Locale;

/** What one body can do right now: the S/E/H/I/F formulas over {@link AbilityTable} coefficients and {@link AbilityModifier} factors; the host load L is 1 when a caller knows none. */
public record BodyAbility(boolean canJump, double hopHeight, double hopReach, double stepUp, double walkSpeed,
                          double turnRate, double swimSpeed, double climbRise, double grip, double scale) {
    /** A body that carries nothing: the host load factor L stays 1. */
    public static BodyAbility of(YukkuriEntity body) { return of(body, 0); }

    /** Reads the ability of {@code body}, where {@code load} is the host's load in the units of L. The {@code scale} is S, which the arc solver uses for its minimum clearance. */
    public static BodyAbility of(YukkuriEntity body, double load) {
        AbilityMath.Values ability = AbilityMath.compute(inputs(body, load), AbilityTable.current(),
                AbilityModifier.of(body));
        return new BodyAbility(ability.canJump(), ability.hopHeight(), ability.hopReach(), ability.stepUp(),
                ability.walkSpeed(), ability.turnRate(), ability.swimSpeed(), ability.climbRise(), ability.grip(),
                ability.size());
    }

    /** The pace it presses or swims with (blocks a tick): its swim speed in water, its walk speed out of it. */
    public double pace(boolean inWater) { return inWater ? swimSpeed : walkSpeed; }

    /** Holding a body scales Walk and Swim by {@code 1 - .5 * heldWidth / carrierWidth}. */
    public static double carrying(YukkuriEntity body) {
        for (var passenger : body.getPassengers())
            if (passenger instanceof YukkuriEntity held) return 1 - .5 * held.getBbWidth() / Math.max(1E-6, body.getBbWidth());
        return 1;
    }

    /** The parameter fingerprint of the loaded ability data, for the {@code body.ability} readout. */
    public String fingerprint() { return AbilityTable.fingerprint(); }

    /** Every value plus the data fingerprint. */
    public String detail() {
        return String.format(Locale.ROOT,
                "%s h=%.2f r=%.2f s=%.2f walk=%.3f turn=%.1f swim=%.3f climb=%.2f grip=%.2f S=%.2f fp=%s",
                canJump ? "can_jump" : "cannot_jump", hopHeight, hopReach, stepUp, walkSpeed, turnRate, swimSpeed,
                climbRise, grip, scale, fingerprint());
    }

    private static AbilityMath.Inputs inputs(YukkuriEntity body, double load) {
        var condition = body.body().condition();
        return new AbilityMath.Inputs(body.growthScale() * body.getScale(), body.life().energy(), body.getHealth(),
                body.getMaxHealth(), condition.painSeverity(), condition.footBurned(),
                body.family().adult() || body.childhood().canJump(), condition.dying(), body.life().isSleeping(), load);
    }
}
