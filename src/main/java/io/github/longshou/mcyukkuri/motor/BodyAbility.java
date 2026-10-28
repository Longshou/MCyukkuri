package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/**
 * What one body can do right now (spec 8.1): the S/E/H/I/F formulas of {@code life/YukkuriLocomotion.limits} with the
 * Q1 coefficients. Only code defaults live here; the JSON table and its modifiers arrive in WP2 ({@code AbilityTable}).
 *
 * <p>The hull load factor L is 1 in WP1: a body on a host does not yet carry the host's load into its speed. WP2/WP4
 * pass it in from the host, so nothing here reads {@code subspecies/}.
 */
public record BodyAbility(boolean canJump, double hopHeight, double hopReach, double stepUp, double walkSpeed,
                          double turnRate, double swimSpeed, double climbRise, double grip, double scale) {
    /** Q1 coefficients: 0.80 blocks of hop height and 1.00 block of reach for a full-condition adult. */
    private static final double HOP_HEIGHT = .80, HOP_REACH = 1.0, STEP_UP = .6, WALK = .10, SWIM = .055, CLIMB = .75;
    /** Turn rate in degrees per tick: {@code 12 * (.6 + .4F)}, so a hurt body turns slower but never stalls. */
    private static final double TURN = 12, TURN_CONDITION = .4;
    /** Minimum energy that still allows a jump (a tired body cannot spring, whatever its size). */
    private static final double JUMP_ENERGY = 8;

    /**
     * Reads the ability of {@code body}: size S from growth and scale, condition F from energy, health and injury.
     * {@code scale} is S, which the arc solver uses for its minimum clearance.
     */
    public static BodyAbility of(YukkuriEntity body) {
        double size = Math.sqrt(Math.clamp(body.growthScale() * body.getScale(), .06, 1.5));
        double energy = Math.clamp(body.life().energy() / 80.0, 0, 1);
        var condition = body.body().condition();
        double health = Math.clamp(body.getHealth() / Math.max(1, body.getMaxHealth()), .1, 1);
        double injury = 1 / (1 + .18 * condition.painSeverity() + (condition.footBurned() ? .3 : 0));
        double fitness = (.35 + .65 * energy) * Math.sqrt(health) * injury;
        boolean jumps = (body.family().adult() || body.childhood().canJump()) && body.life().energy() >= JUMP_ENERGY
                && !condition.dying() && !body.life().isSleeping();
        double load = 1;
        return new BodyAbility(jumps, jumps ? HOP_HEIGHT * size * fitness : 0, jumps ? HOP_REACH * size * fitness : 0,
                STEP_UP * size, WALK * size * fitness * load, TURN * (.6 + TURN_CONDITION * fitness),
                SWIM * size * fitness * load, CLIMB * size * (.65 + .35 * fitness), fitness, size);
    }
}
