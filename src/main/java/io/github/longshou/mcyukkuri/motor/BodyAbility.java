package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import java.util.Locale;

/**
 * What one body can do right now (spec 8.1): the S/E/H/I/F formulas of {@code AbilityMath} with the coefficients of
 * {@link AbilityTable} and the species factors of {@link AbilityModifier}.
 *
 * <p>The host load factor L is the load a caller knows about (WP1 callers pass none, so L is 1). WP4 lets a host
 * supply it; nothing here reads {@code subspecies/}.
 */
public record BodyAbility(boolean canJump, double hopHeight, double hopReach, double stepUp, double walkSpeed,
                          double turnRate, double swimSpeed, double climbRise, double grip, double scale) {
    /** A body that carries nothing: the host load factor L stays 1. */
    public static BodyAbility of(YukkuriEntity body) { return of(body, 0); }

    /**
     * Reads the ability of {@code body}, where {@code load} is the host's load in the units of spec 8.1 L. The
     * {@code scale} is S, which the arc solver uses for its minimum clearance.
     */
    public static BodyAbility of(YukkuriEntity body, double load) {
        AbilityMath.Values ability = AbilityMath.compute(inputs(body, load), AbilityTable.current(),
                AbilityModifier.of(body));
        return new BodyAbility(ability.canJump(), ability.hopHeight(), ability.hopReach(), ability.stepUp(),
                ability.walkSpeed(), ability.turnRate(), ability.swimSpeed(), ability.climbRise(), ability.grip(),
                ability.size());
    }

    /** The parameter fingerprint of the loaded ability data, for the {@code body.ability} readout. */
    public String fingerprint() { return AbilityTable.fingerprint(); }

    /** Every value plus the data fingerprint (spec 13.2 {@code body.ability}). */
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
