package io.github.longshou.mcyukkuri.family;

import java.util.Random;

/** Litter size. */
public final class Litter {
    private Litter() {}
    /** Land: 1 + four draws of p. Aquatic (a boat seats two young, 项目设计): 1 + a draw of p + a draw of p/4, mostly 1-2. */
    public static int size(double probability, boolean aquatic, Random random) {
        int count = 1;
        if (aquatic) return count + (random.nextDouble() < probability ? 1 : 0) + (random.nextDouble() < probability / 4 ? 1 : 0);
        for (int i = 0; i < 4; i++) if (random.nextDouble() < probability) count++;
        return count;
    }
}
