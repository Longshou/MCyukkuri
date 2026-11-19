package io.github.longshou.mcyukkuri.life;

/** One swallowed serving can indulge a demand, a surplus, or both. */
final class Indulgence {
    static boolean sweet(boolean ownerSweetSwallowed, boolean lessonReward, boolean demanded, boolean overDailyLimit,
                         float nutritionBefore) {
        return ownerSweetSwallowed && !lessonReward && (demanded || overDailyLimit || nutritionBefore > LifeMeal.FULL);
    }

    private Indulgence() {}
}
