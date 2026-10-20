package io.github.longshou.mcyukkuri.family;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Minecraft simulation rates, independently configurable from the source game's day clock. */
public final class FamilyConfig {
    public static final double DEFAULT_OFFSPRING_SIZE_INFLUENCE = .70;
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue GESTATION, RECOVERY, POPULATION_CAP, EGG_INCUBATION,HOUSE_ARRIVAL_TIME;
    public static final ModConfigSpec.BooleanValue NATURAL_PAIRING, AGING,HOUSE_ARRIVALS;
    public static final ModConfigSpec.DoubleValue LIVE_BIRTH_CHANCE, OFFSPRING_SIZE_INFLUENCE;
    static {
        var b = new ModConfigSpec.Builder();
        b.push("family");
        GESTATION = b.comment("Well-supported loaded ticks for stem gestation; food, safety and room are required.")
                .defineInRange("gestationTicks", 6000, 200, 2400000);
        RECOVERY = b.defineInRange("reproductionRecoveryTicks", 12000, 200, 2400000);
        POPULATION_CAP = b.comment("Known living and reserved unborn individuals within 32 blocks. Unloaded family records still count.")
                .defineInRange("localPopulationCap", 16, 2, 128);
        NATURAL_PAIRING = b.define("naturalPairing", true);
        LIVE_BIRTH_CHANCE = b.comment("Minecraft adaptation: ordinary courtship chance of live birth instead of stem birth; fixed at conception. House eggs and predator rules are separate.")
                .defineInRange("ordinaryLiveBirthChance", .20, 0, 1);
        OFFSPRING_SIZE_INFLUENCE = b.comment("Project-original maternal-size effect on ordinary stem/live brood counts (1-5). Default 0.70 preserves the original distribution; 0 removes size differences (mean 3), larger values strengthen them. House egg counts are separate.")
                .defineInRange("offspringSizeInfluence", DEFAULT_OFFSPRING_SIZE_INFLUENCE, 0, 4);
        AGING = b.comment("Allow old age to cause natural death after the saved lifespan.").define("aging", true);
        EGG_INCUBATION = b.defineInRange("eggIncubationTicks", 4000, 200, 2400000);
        HOUSE_ARRIVALS=b.define("houseArrivals",true);
        HOUSE_ARRIVAL_TIME=b.comment("Loaded ticks per opportunistic founder. Any stored food or lint attracts up to two; no night or four-food requirement.")
                .defineInRange("houseArrivalTicks",600,200,2400000);
        b.pop(); SPEC = b.build();
    }
    private FamilyConfig() {}
}
