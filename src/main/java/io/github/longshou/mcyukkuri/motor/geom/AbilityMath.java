package io.github.longshou.mcyukkuri.motor.geom;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The ability formulas of spec 8.1 as pure functions: size S, energy E, health H, injury I, condition F and the host
 * load factor L, then every limit an action may read. The numbers come from {@link Coefficients} (the data file) with
 * per-species {@link Multipliers} applied; nothing here knows about entities, worlds or Minecraft.
 */
public final class AbilityMath {
    /**
     * What the body is right now. {@code size} is {@code growthScale * scale} (the S source before the clamp),
     * {@code energy} is the raw need value, {@code load} is the host's deck load in the units of spec 8.1 L.
     */
    public record Inputs(double size, double energy, double health, double maxHealth, double painSeverity,
                         boolean footBurned, boolean adultOrCanJump, boolean dying, boolean sleeping, double load) {
        /** A resting, uninjured adult at full energy: the reference body of spec 8.1 and {@code AbilityFormulaTest}. */
        public static final Inputs ADULT = new Inputs(1, 80, 20, 20, 0, false, true, false, false, 0);
    }

    /** Every coefficient of spec 8.1, in data-file key order. Its names are the JSON keys of {@code ability.json}. */
    public record Coefficients(double hopHeight, double hopReach, double stepUp, double walkSpeed, double turnRate,
                               double turnFloor, double swimSpeed, double climbRise, double climbFloor,
                               double energyRef, double minJumpEnergy, double painWeight, double burnPenalty,
                               double loadRef, double sizeMin, double sizeMax, double fitnessFloor) {
        /** The Q1 numbers of spec 8.1; also the fallback when {@code body/ability.json} is missing or broken. */
        public static final Coefficients DEFAULTS = new Coefficients(.80, 1.00, .6, .10, 12, .6, .055, .75, .65,
                80, 8, .18, .3, 12, .06, 1.5, .35);

        /** The named view a data file or readout needs; insertion order is the spec's order. */
        public Map<String, Double> asMap() {
            var out = new LinkedHashMap<String, Double>();
            out.put("hop_height", hopHeight);
            out.put("hop_reach", hopReach);
            out.put("step_up", stepUp);
            out.put("walk_speed", walkSpeed);
            out.put("turn_rate", turnRate);
            out.put("turn_floor", turnFloor);
            out.put("swim_speed", swimSpeed);
            out.put("climb_rise", climbRise);
            out.put("climb_floor", climbFloor);
            out.put("energy_ref", energyRef);
            out.put("min_jump_energy", minJumpEnergy);
            out.put("pain_weight", painWeight);
            out.put("burn_penalty", burnPenalty);
            out.put("load_ref", loadRef);
            out.put("size_min", sizeMin);
            out.put("size_max", sizeMax);
            out.put("fitness_floor", fitnessFloor);
            return out;
        }
    }

    /** Per-coefficient factors (spec 8.2 modifiers): a species doubles {@code swim_speed} by putting 2.0 here. */
    public record Multipliers(Map<String, Double> factors) {
        /** No species modifier: every coefficient is used as the data file gives it. */
        public static final Multipliers IDENTITY = new Multipliers(Map.of());

        public Multipliers { factors = Map.copyOf(factors); }

        public double factor(String key) { return factors.getOrDefault(key, 1.0); }

        public boolean identity() { return factors.isEmpty(); }

        /** The same coefficients with every named factor multiplied in. */
        public Coefficients apply(Coefficients c) {
            if (factors.isEmpty()) return c;
            return new Coefficients(c.hopHeight() * factor("hop_height"), c.hopReach() * factor("hop_reach"),
                    c.stepUp() * factor("step_up"), c.walkSpeed() * factor("walk_speed"),
                    c.turnRate() * factor("turn_rate"), c.turnFloor() * factor("turn_floor"),
                    c.swimSpeed() * factor("swim_speed"), c.climbRise() * factor("climb_rise"),
                    c.climbFloor() * factor("climb_floor"), c.energyRef() * factor("energy_ref"),
                    c.minJumpEnergy() * factor("min_jump_energy"), c.painWeight() * factor("pain_weight"),
                    c.burnPenalty() * factor("burn_penalty"), c.loadRef() * factor("load_ref"),
                    c.sizeMin() * factor("size_min"), c.sizeMax() * factor("size_max"),
                    c.fitnessFloor() * factor("fitness_floor"));
        }
    }

    /** The numbers an action may read; {@code size} is S (the arc solver's minimum clearance). */
    public record Values(boolean canJump, double size, double fitness, double load, double hopHeight, double hopReach,
                         double stepUp, double walkSpeed, double turnRate, double swimSpeed, double climbRise,
                         double grip) {}

    /** Spec 8.1, verbatim: S, E, H, I, F, L and every derived limit. */
    public static Values compute(Inputs in, Coefficients coefficients, Multipliers modifiers) {
        Coefficients c = (modifiers == null ? Multipliers.IDENTITY : modifiers)
                .apply(coefficients == null ? Coefficients.DEFAULTS : coefficients);
        double size = Math.sqrt(Math.clamp(in.size(), c.sizeMin(), c.sizeMax()));
        double energy = Math.clamp(in.energy() / c.energyRef(), 0, 1);
        double health = Math.clamp(in.health() / Math.max(1, in.maxHealth()), .1, 1);
        double injury = 1 / (1 + c.painWeight() * in.painSeverity() + (in.footBurned() ? c.burnPenalty() : 0));
        double fitness = (c.fitnessFloor() + (1 - c.fitnessFloor()) * energy) * Math.sqrt(health) * injury;
        double load = 1 / (1 + Math.max(0, in.load()) / c.loadRef());
        boolean jumps = in.adultOrCanJump() && in.energy() >= c.minJumpEnergy() && !in.dying() && !in.sleeping();
        return new Values(jumps, size, fitness, load,
                jumps ? c.hopHeight() * size * fitness : 0, jumps ? c.hopReach() * size * fitness : 0,
                c.stepUp() * size, c.walkSpeed() * size * fitness * load,
                c.turnRate() * (c.turnFloor() + (1 - c.turnFloor()) * fitness),
                c.swimSpeed() * size * fitness * load,
                c.climbRise() * size * (c.climbFloor() + (1 - c.climbFloor()) * fitness), fitness);
    }

    private AbilityMath() {}
}
