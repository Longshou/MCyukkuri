package io.github.longshou.mcyukkuri.family;

/** Nutrition draw and visible drain of a growing stem. */
final class StemDrain {
    // The same total as 30 old 200-tick draws, spread across a roughly twenty-second brood.
    static final int DRAW_TICKS=20, LEGACY_DRAW_TICKS=200, ZERO_NUTRITION_GRACE=60;
    static final float NUTRITION_PER_HEALTH=5, THIN_NUTRITION=50, WEAK_NUTRITION=30;
    private static final float THIN_ENERGY=50, WEAK_ENERGY=25;
    private StemDrain(){}

    /** Settlement uses the brood's clock. Ripe fruit no longer draw. */
    static int duration(float variation) { return Math.round(400*variation); }

    static int drawTicks(int elapsed,int duration,int interval) {
        int remainder=elapsed%interval;
        if(elapsed<=0||elapsed>duration||remainder!=0&&elapsed!=duration)return 0;
        return remainder==0?interval:remainder;
    }
    static float draw(int ticks,int unborn,float variation,int duration,boolean fast) {
        float total=(1f+.2f*unborn)*variation;
        return fast ? total*30*ticks/duration : total*ticks/LEGACY_DRAW_TICKS;
    }

    static float damage(float deficit,float health,int elapsed,int emptyAt) {
        float damage=deficit/NUTRITION_PER_HEALTH;
        return elapsed-emptyAt<ZERO_NUTRITION_GRACE ? Math.min(damage,Math.max(0,health-1)) : damage;
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
