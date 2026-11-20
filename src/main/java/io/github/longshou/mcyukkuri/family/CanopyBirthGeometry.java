package io.github.longshou.mcyukkuri.family;

import java.util.function.BiPredicate;

final class CanopyBirthGeometry {
    /** Centre the ripe row over the pad while the mother's box clears its raised lip by the arrival tolerance. */
    static double depth(double first, double last, double motherWidth) {
        return Math.max((first + last) / 2, .5 + motherWidth / 2 + .02);
    }
    static boolean canRelease(boolean hasPadPlan, boolean padIntact, boolean aligned, boolean safeGround) {
        return hasPadPlan ? padIntact && aligned : safeGround;
    }
    static boolean groundUnderFruit(double x, double z, double radius, BiPredicate<Integer,Integer> dryGround) {
        for (int col=(int)Math.floor(x-radius); col<=Math.floor(x+radius); col++)
            for (int row=(int)Math.floor(z-radius); row<=Math.floor(z+radius); row++)
                if (!dryGround.test(col,row)) return false;
        return true;
    }
    private CanopyBirthGeometry() {}
}
