package io.github.longshou.mcyukkuri.motor.geom;

import java.util.LinkedHashMap;
import java.util.Map;

/** The ability formulas as pure functions: S, E, H, I, F and L, then every limit an action reads. The numbers come from {@link Coefficients} with per-species {@link Multipliers}. */
public final class AbilityMath {
    /** What the body is right now: {@code size} is {@code growthScale * scale} (S before the clamp), {@code energy} the raw need value, {@code load} the host's load in L. */
    public record Inputs(double size, double energy, double health, double maxHealth, double painSeverity,
                         boolean footBurned, boolean adultOrCanJump, boolean dying, boolean sleeping, double load) {
        /** A resting, uninjured adult at full energy: the reference body. */
        public static final Inputs ADULT = new Inputs(1, 80, 20, 20, 0, false, true, false, false, 0);
    }

    /** Every coefficient, in data-file key order. Its names are the JSON keys of {@code ability.json}. */
    public record Coefficients(double hopHeight, double hopReach, double stepUp, double walkSpeed, double turnRate,
                               double turnFloor, double swimSpeed, double climbRise, double climbFloor,
                               double energyRef, double minJumpEnergy, double painWeight, double burnPenalty,
                               double loadRef, double sizeMin, double sizeMax, double fitnessFloor) {
        /** The default numbers; also the fallback when {@code body/ability.json} is missing or broken. */
        public static final Coefficients DEFAULTS = new Coefficients(.80, 1.00, .6, .10, 12, .6, .03, .75, .65,
                80, 8, .18, .3, 12, .06, 1.5, .35);

        /** The named view a data file or readout needs; insertion order is {@link Coefficients}' own order. */
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

    /** Per-coefficient factors: a species swims half again as fast by putting {@code swim_speed} 1.5 here; a key that is no coefficient is a capability {@link #flag}. */
    public record Multipliers(Map<String, Double> factors) {
        /** No species modifier: every coefficient is used as the data file gives it. */
        public static final Multipliers IDENTITY = new Multipliers(Map.of());

        public Multipliers { factors = Map.copyOf(factors); }

        public double factor(String key) {
            Double factor = factors.get(key);
            return factor == null ? 1 : factor;
        }

        /** A species capability the data switches on (authored above 0), off where it authors none: {@code hat_boat}. */
        public boolean flag(String key) { return factors.getOrDefault(key, 0.0) > 0; }

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

    /** S, E, H, I, F, L and every derived limit. A young too young to jump cannot pull itself over an edge either, so its climb rise is 0; energy only lowers it to its floor. */
    public static Values compute(Inputs in, Coefficients coefficients, Multipliers modifiers) {
        Coefficients c = (modifiers == null ? Multipliers.IDENTITY : modifiers)
                .apply(coefficients == null ? Coefficients.DEFAULTS : coefficients);
        double size = Math.sqrt(Math.clamp(in.size(), c.sizeMin(), c.sizeMax()));
        double fitness = fitness(in.energy(), in.health(), in.maxHealth(), in.painSeverity(), in.footBurned(), c, Multipliers.IDENTITY);
        double load = 1 / (1 + Math.max(0, in.load()) / c.loadRef());
        boolean jumps = in.adultOrCanJump() && in.energy() >= c.minJumpEnergy() && !in.dying() && !in.sleeping();
        return new Values(jumps, size, fitness, load,
                jumps ? c.hopHeight() * size * fitness : 0, jumps ? c.hopReach() * size * fitness : 0,
                c.stepUp() * size, c.walkSpeed() * size * fitness * load,
                c.turnRate() * (c.turnFloor() + (1 - c.turnFloor()) * fitness),
                c.swimSpeed() * size * fitness * load,
                in.adultOrCanJump() ? c.climbRise() * size * (c.climbFloor() + (1 - c.climbFloor()) * fitness) : 0, fitness);
    }

    /** Share of a body's hop and its pull-up together that a strained scramble over a ledge can make good: a spring and a hook
     *  of the braid, not a full spring and a full pull. For a reference adult (hop 0.8, climb 0.75) at ordinary fitness it just
     *  clears one block; a small or worn body falls short. Never less than the plain climb. */
    public static final double SCRAMBLE_SHARE = .8;

    /** The highest bank edge (blocks) a body can strain over out of the water: pure function of its hop and climb rises. */
    public static double scrambleRise(double hopHeight, double climbRise) {
        return Math.max(climbRise, SCRAMBLE_SHARE * (hopHeight + climbRise));
    }

    public static double fitness(double energy, double health, double maxHealth, double pain, boolean burned,
                                 Coefficients c, Multipliers m) {
        double stamina = Math.clamp(energy / (c.energyRef() * m.factor("energy_ref")), 0, 1);
        double floor = c.fitnessFloor() * m.factor("fitness_floor");
        double injury = 1 / (1 + c.painWeight() * m.factor("pain_weight") * pain
                + (burned ? c.burnPenalty() * m.factor("burn_penalty") : 0));
        return (floor + (1 - floor) * stamina) * Math.sqrt(Math.clamp(health / Math.max(1, maxHealth), .1, 1)) * injury;
    }

    private AbilityMath() {}
}
