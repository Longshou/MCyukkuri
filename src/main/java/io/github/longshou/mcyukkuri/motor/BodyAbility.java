package io.github.longshou.mcyukkuri.motor;

import io.github.longshou.mcyukkuri.entity.SpeciesProfile;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.family.GrowthSize;
import io.github.longshou.mcyukkuri.life.YukkuriLife;
import io.github.longshou.mcyukkuri.motor.geom.AbilityMath;
import io.github.longshou.mcyukkuri.motor.geom.ArcSolver;
import io.github.longshou.mcyukkuri.motor.geom.Ballistic;
import io.github.longshou.mcyukkuri.motor.geom.Gait;
import java.util.Locale;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** What one body can do right now: the S/E/H/I/F formulas over {@link AbilityTable} coefficients and {@link AbilityModifier} factors; the host load L is 1 when a caller knows none. */
public record BodyAbility(boolean canJump, double hopHeight, double hopReach, double hopSpeed, double stepUp, double walkSpeed,
                          double turnRate, double swimSpeed, double climbRise, double grip, double scale) {
    /** A body that carries nothing: the host load factor L stays 1. */
    public static BodyAbility of(YukkuriEntity body) { return of(body, 0); }

    /** Reads the ability of {@code body}, where {@code load} is the host's load in the units of L. The {@code scale} is S, which the arc solver uses for its minimum clearance. */
    public static BodyAbility of(YukkuriEntity body, double load) {
        AbilityMath.Values ability = values(body, load);
        double temperament = temperament(body);
        return new BodyAbility(ability.canJump(), ability.hopHeight(), ability.hopReach(), Gait.hop(ability.size(), temperament),
                ability.stepUp(), Gait.walk(ability.walkSpeed(), temperament), ability.turnRate(),
                Gait.swim(ability.swimSpeed(), temperament), ability.climbRise(), ability.grip(),
                ability.size());
    }

    public static double fitness(YukkuriEntity body) {
        return AbilityMath.fitness(body.life().energy(), body.getHealth(), body.getMaxHealth(),
                body.body().painSeverity(), body.body().footBurned(), AbilityTable.current(), AbilityModifier.of(body));
    }

    private static AbilityMath.Values values(YukkuriEntity body, double load) {
        return AbilityMath.compute(inputs(body, load), AbilityTable.current(), AbilityModifier.of(body));
    }

    /** Character and present mood: boldness sets the usual pace, a grieving body drags itself along (mind/EmotionCourse). */
    public static double temperament(YukkuriEntity body) {
        return Gait.temperament(body.life().personality().boldness()) * body.mind().feeling().pace();
    }

    public static double growthPace(YukkuriEntity body) {
        var family = body.family();
        return GrowthSize.movement(family.stage(), family.scale(), body.profile().houseBody());
    }

    /** Energy below this is tired: the sleepy face and the worn gait begin together (see PresentationArbiter). */
    public static final double TIRED_ENERGY = 25;

    /** The one authority for a body with nothing to spare: starving (nutrition under {@link YukkuriLife#STARVING_NUTRITION}),
     *  badly hurt (health at or under a third) or exhausted (energy under {@link #TIRED_ENERGY}). It waddles at its ordinary
     *  walk at most: no request may ask it faster, and it cannot spring (see {@link #cannotSpring}). Light wounds and a full bowel do not count. */
    public static boolean frail(YukkuriEntity body) {
        return body.body().weak() || body.life().energy() < TIRED_ENERGY || body.life().nutrition() < YukkuriLife.STARVING_NUTRITION;
    }

    /** Whether any hop, run, vanilla step jump or in-place hop clip is shut to this body: frail, or a baked sole. */
    public static boolean cannotSpring(YukkuriEntity body) {
        return body.body().footBurned() || frail(body);
    }

    /** Slowing of a tired body not already weak (BodyLife slows a weak one): up to a quarter at no energy. */
    public static double fatigue(YukkuriEntity body) {
        if (body.body().weak()) return 1;
        return 1 - .25 * Math.clamp((TIRED_ENERGY - body.life().energy()) / TIRED_ENERGY, 0, 1);
    }

    /** Keep the goal's request intact for repaths. Convert it when vanilla consumes it. */
    public static double groundModifier(YukkuriEntity body, double request) {
        double attribute = body.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (attribute == 0) return request; // A dying body has no drive.
        if (frail(body)) request = Math.min(request, 1);
        return Gait.groundControl(attribute * request, temperament(body) * carrying(body)) / attribute;
    }

    public static double flightPace(YukkuriEntity body, double request) {
        if (frail(body)) request = Math.min(request, 1);
        return Gait.flight(body.getAttributeValue(Attributes.FLYING_SPEED), request, growthPace(body), temperament(body))
                * body.species().stride(request) * (body.body().weak() ? .6 : fatigue(body)) * wingLift(body);
    }

    /** A predator's wings are built for the chase: up to 1.4 times the common flier's pace, in proportion to its fitness (energy, health, pain), so a spent hunter has none of it. */
    public static double wingLift(YukkuriEntity body) {
        return body.profile().ecology() == SpeciesProfile.Ecology.PREDATOR ? 1 + .4 * fitness(body) : 1;
    }

    /** The highest flight displacement per tick the body can reach: the common flier's ceiling, lifted for a predator. */
    public static double flightCeiling(YukkuriEntity body) { return Gait.HUMAN_WALK * wingLift(body); }

    /** The pace it presses or swims with (blocks a tick): its swim speed in water, its walk speed out of it. */
    public double pace(boolean inWater) { return inWater ? swimSpeed : walkSpeed; }

    /** Vanilla stores ground velocity after friction. Recover its actual last ground step, rather than
     * relying on position/old-position whose sampling depends on the entity's tick order. */
    public static Vec3 groundMotion(YukkuriEntity body) {
        var below = body.getBlockPosBelowThatAffectsMyMovement();
        double friction = body.level().getBlockState(below).getFriction(body.level(), below, body);
        return body.getDeltaMovement().multiply(1, 0, 1).scale(1 / (friction * Ballistic.AIR_DRAG));
    }

    /** The highest bank or ledge it can strain over by a hop and a hook of the braid (see {@link AbilityMath#scrambleRise}). */
    public double scrambleRise() { return AbilityMath.scrambleRise(hopHeight, climbRise); }

    public double runningReach(double groundSpeed, double rise) {
        return ArcSolver.runningReach(hopHeight, hopReach, groundSpeed, rise, hopSpeed);
    }

    /** An adult's ram effort per stroke, from its current pace, fitness, size and species strength. */
    public static double bashPower(YukkuriEntity body) {
        var ability = of(body);
        return body.family().adult() && ability.canJump()
                ? ability.walkSpeed() * ability.scale() * ability.grip() * body.species().traits().might() : 0;
    }

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
        var condition = body.body();
        return new AbilityMath.Inputs(body.growthScale() * body.getScale(), body.life().energy(), body.getHealth(),
                body.getMaxHealth(), condition.painSeverity(), condition.footBurned(),
                body.family().adult() || body.childhood().canJump(), condition.dying(), body.life().isSleeping(), load, cannotSpring(body));
    }
}
