package io.github.longshou.mcyukkuri.predator;

import io.github.longshou.mcyukkuri.cognition.Ability;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** The Minecraft side of {@link PreySight}: reads the two bodies and the block the prey stands in. */
final class PreyEyes {
    private PreyEyes() {}

    /** Whether the hunter makes out the prey now, by the two bodies' own grades. */
    static boolean seen(YukkuriEntity hunter, YukkuriEntity prey) {
        if (!covered(prey)) return true;
        return PreySight.seen(hunter.distanceTo(prey), true, prey.getDeltaMovement().horizontalDistanceSqr() > PreySight.MOVING_SPEED * PreySight.MOVING_SPEED,
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
            skills.innate(ability, PreySight.gradeOf(ability == Ability.HUNTING
                    ? PreySight.huntScore(traits.boldness(), traits.intelligence(), adult)
                    : PreySight.hidingScore(traits.selfControl(), traits.intelligence(), adult)), "wild_traits");
        }
        return skills.grade(ability);
    }
}
