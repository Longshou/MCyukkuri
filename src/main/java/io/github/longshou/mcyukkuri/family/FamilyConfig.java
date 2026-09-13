package io.github.longshou.mcyukkuri.family;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Minecraft simulation rates, independently configurable from the source game's day clock. */
public final class FamilyConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue LIFESPAN, GESTATION, RECOVERY, POPULATION_CAP, EGG_INCUBATION,HOUSE_ARRIVAL_TIME;
    public static final ModConfigSpec.BooleanValue NATURAL_PAIRING, AGING,HOUSE_ARRIVALS;
    public static final ModConfigSpec.DoubleValue LIVE_BIRTH_CHANCE;
    static {
        var b = new ModConfigSpec.Builder();
        b.push("family");
        LIFESPAN = b.comment("Base loaded simulation ticks in a lifespan. Maturity is checked on waking after one third.")
                .defineInRange("lifespanTicks", 72000, 600, 24000000);
        GESTATION = b.comment("Well-supported loaded ticks for stem gestation; food, safety and room are required.")
                .defineInRange("gestationTicks", 6000, 200, 2400000);
        RECOVERY = b.defineInRange("reproductionRecoveryTicks", 12000, 200, 2400000);
        POPULATION_CAP = b.comment("Known living and reserved unborn individuals within 32 blocks. Unloaded family records still count.")
                .defineInRange("localPopulationCap", 16, 2, 128);
        NATURAL_PAIRING = b.define("naturalPairing", true);
        LIVE_BIRTH_CHANCE = b.comment("Minecraft adaptation: ordinary courtship chance of live birth instead of stem birth; fixed at conception. House eggs and predator rules are separate.")
                .defineInRange("ordinaryLiveBirthChance", .20, 0, 1);
        AGING = b.comment("Allow old age to cause natural death after the saved lifespan.").define("aging", true);
        EGG_INCUBATION = b.defineInRange("eggIncubationTicks", 4000, 200, 2400000);
        HOUSE_ARRIVALS=b.define("houseArrivals",true);
        HOUSE_ARRIVAL_TIME=b.comment("Loaded ticks per founder; each crevice attracts at most two and consumes four scraps each.").defineInRange("houseArrivalTicks",1200,200,2400000);
        b.pop(); SPEC = b.build();
    }
    private FamilyConfig() {}
}
