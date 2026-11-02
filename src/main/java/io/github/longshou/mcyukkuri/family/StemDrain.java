package io.github.longshou.mcyukkuri.family;

/** The visible drain of an attack-conceived brood (project design: no source record of a drained bearer's look). */
final class StemDrain {
    /** Below these needs she looks thinner (2) and then weak (3); the rule itself lives in FEATURES 攻击素鸡立. */
    private static final float THIN_NUTRITION=50, THIN_ENERGY=50, WEAK_NUTRITION=30, WEAK_ENERGY=25;
    private StemDrain(){}
    /**
     * 0 none, 1 carrying, 2 thinning, 3 weak. Stage 1 belongs to the carrying itself; 2 and 3 belong to the drained body and
     * outlast the brood until she has eaten and slept back above them.
     */
    static int stage(boolean carrying,boolean drained,float nutrition,float energy) {
        if(!carrying&&!drained)return 0;
        if(nutrition<WEAK_NUTRITION||energy<WEAK_ENERGY)return 3;
        if(nutrition<THIN_NUTRITION||energy<THIN_ENERGY)return 2;
        return carrying?1:0;
    }
}
