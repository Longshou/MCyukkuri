package io.github.longshou.mcyukkuri.family;

/** Nutrition draw and visible drain of a growing stem. */
final class StemDrain {
    // The former burden's 200-tick settlement and 1 + 0.2 per fruit now pay for the actual growth.
    static final int DRAW_TICKS=200;
    static final float NUTRITION_PER_HEALTH=5, THIN_NUTRITION=50;
    private static final float THIN_ENERGY=50, WEAK_NUTRITION=30, WEAK_ENERGY=25;
    private StemDrain(){}

    /** Settlement uses the brood's clock. Ripe fruit no longer draw. */
    static int drawTicks(int elapsed,int duration) {
        int remainder=elapsed%DRAW_TICKS;
        if(elapsed>duration||remainder!=0&&elapsed!=duration)return 0;
        return remainder==0?DRAW_TICKS:remainder;
    }
    static float draw(int ticks,int unborn,float variation) {
        // The same individual scale controls the stalk's length and thickness.
        return (1f+.2f*unborn)*variation*(ticks/(float)DRAW_TICKS);
    }

    /**
     * 0 none, 1 carrying, 2 thinning, 3 weak. Stage 1 belongs to the carrying itself; 2 and 3 belong to the drained body and
     * outlast the brood until she has eaten and slept back above them.
     */
    static int stage(boolean aftermath,boolean drained,float nutrition,float energy) {
        if(!aftermath&&!drained)return 0;
        if(nutrition<WEAK_NUTRITION||energy<WEAK_ENERGY)return 3;
        if(nutrition<THIN_NUTRITION||energy<THIN_ENERGY)return 2;
        return aftermath?1:0;
    }
}
