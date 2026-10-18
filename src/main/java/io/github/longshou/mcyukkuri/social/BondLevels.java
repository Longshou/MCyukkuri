package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.mind.MindLife;

/** Existing thresholds, kept separate where the current uses disagree (D6). */
public final class BondLevels {
    public static final int AFFINITY_HOSTILE = -20;
    public static final int AFFINITY_DISLIKED = -10;
    public static final int AFFINITY_NEUTRAL = 0;
    public static final int AFFINITY_ACQUAINTED = 4;
    public static final int AFFINITY_FAMILIAR = 8;
    public static final int AFFINITY_CLOSE = 10;
    public static final int AFFINITY_COOPERATIVE = 12;
    public static final int AFFINITY_ATTACHED = 15;
    public static final int TRUST_FAMILIAR = 10;
    // 待统一（D6）：keep the three player contexts independent.
    public static final int AFFECTION_FEAR_LIMIT = 25;
    public static final int SOCIAL_FEAR_LIMIT = 30;
    public static final int OPERATION_FEAR_LIMIT = 40;
    public static final float UNEASE_LOW_LIMIT = 30;
    public static final float UNEASE_CARE_LIMIT = 50;
    public static final float UNEASE_SWEETENED = MindLife.SWEETENED;

    // Additional legacy thresholds; call-site contexts are listed in Appendix A (D6).
    public static final int FEAR_AT_15 = 15;
    public static final int TRUST_AT_20 = 20;
    public static final int TRUST_AT_MINUS_5 = -5;

    private BondLevels() {}
}
