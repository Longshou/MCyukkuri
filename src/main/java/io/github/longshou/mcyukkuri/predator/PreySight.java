package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * What a hunting predator makes out of prey standing in grass: open prey is seen across the whole hunt range, covered prey
 * only within a range that grows with the predator's HUNTING grade, shrinks with the prey's WILD_SURVIVAL grade, and
 * grows when the prey moves. The two grades are each body's own, set once from its traits and age.
 */
public final class PreySight {
    /** Blocks within which a hunter makes out still prey in cover at HUNTING grade 0, and the gain per grade (grades run 0..3: 4..16 blocks). */
    static final double BASE_DETECT = 4, DETECT_PER_GRADE = 4;
    /** Prey that moves in cover shows itself: its detect range is this much longer. */
    static final double MOVING_FACTOR = 1.5;
    /** Each WILD_SURVIVAL grade shortens the range at which a hunter sees the prey by this share (grade 3: 45%). */
    static final double HIDING_PER_GRADE = .15;
    /** Blocks per tick above which the prey counts as moving. */
    static final double MOVING_SPEED = .05;

    private PreySight() {}

    /** The 0..3 grade of a 0..1 score: quarters. */
    static int gradeOf(double score) { return Math.clamp((int) (score * 4), 0, 3); }

    /** A hunter's own score: boldness and intelligence, and the years of an adult. */
    static double huntScore(int boldness, int intelligence, boolean adult) {
        return .5 * boldness / 100 + .3 * intelligence / 100 + (adult ? .2 : 0);
    }

    /** A prey's own score: self-control and intelligence, and the years of an adult. */
    static double hidingScore(int selfControl, int intelligence, boolean adult) {
        return .5 * selfControl / 100 + .3 * intelligence / 100 + (adult ? .2 : 0);
    }

    /** The farthest distance (blocks) at which this hunter makes out this covered prey. */
    static double detectRange(int hunting, int survival, boolean moving) {
        return (BASE_DETECT + DETECT_PER_GRADE * hunting) * (moving ? MOVING_FACTOR : 1) * (1 - HIDING_PER_GRADE * survival);
    }

    /** Whether prey at {@code distance} is made out: open prey always (the caller bounds it by the hunt range), covered prey within its detect range. */
    static boolean seen(double distance, boolean covered, boolean moving, int hunting, int survival) {
        return !covered || distance <= detectRange(hunting, survival, moving);
    }

    /** Whether the hunter makes out the prey now, by the two bodies' own grades. */
    public static boolean seen(YukkuriEntity hunter, YukkuriEntity prey) {
        if (!covered(prey)) return true;
        return seen(hunter.distanceTo(prey), true, prey.getDeltaMovement().horizontalDistanceSqr() > MOVING_SPEED * MOVING_SPEED,
                grade(hunter, Ability.HUNTING), grade(prey, Ability.WILD_SURVIVAL));
    }

    /** Whether the body stands in a clump of tall grass, fern or berry bush that hides it (short grass is knee-high and hides no one). */
    static boolean covered(YukkuriEntity body) {
        Block block = body.level().getBlockState(body.blockPosition()).getBlock();
        return block == Blocks.TALL_GRASS || block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.SWEET_BERRY_BUSH;
    }

    /** The body's grade of a wild ability, set from its traits at the first look. */
    private static int grade(YukkuriEntity body, Ability ability) {
        var skills = body.skills();
        if (!skills.assessed(ability)) {
            var traits = body.life().personality();
            boolean adult = body.family().adult();
            skills.innate(ability, gradeOf(ability == Ability.HUNTING
                    ? huntScore(traits.boldness(), traits.intelligence(), adult)
                    : hidingScore(traits.selfControl(), traits.intelligence(), adult)), "wild_traits");
        }
        return skills.grade(ability);
    }
}
