package io.github.longshou.mcyukkuri.life;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Rates advance only while the entity is loaded and ticking. */
public final class YukkuriConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue HUNGER_INTERVAL;
    public static final ModConfigSpec.IntValue ENERGY_INTERVAL;
    public static final ModConfigSpec.IntValue HYGIENE_INTERVAL;
    public static final ModConfigSpec.IntValue WET_DAMAGE_THRESHOLD;
    public static final ModConfigSpec.IntValue LOCAL_SPAWN_CAP;
    public static final ModConfigSpec.BooleanValue NATURAL_SPAWNS;
    public static final ModConfigSpec.BooleanValue SUMO_DAMAGE;
    public static final ModConfigSpec.BooleanValue COMMUNITIES;
    public static final ModConfigSpec.IntValue FOREST_CHANCE, FAMILY_CHANCE, LEADER_CHANCE;
    public static final ModConfigSpec.IntValue FOREST_MIN_HOUSEHOLDS, FOREST_MAX_HOUSEHOLDS, HOUSEHOLD_MAX_CHILDREN;
    public static final ModConfigSpec.IntValue COMMUNITY_SPAWN_CAP, COMMUNITY_POPULATION_CAP;
    public static final ModConfigSpec.IntValue DEIBU_NUTRITION, DEIBU_FRIENDLINESS, DEIBU_EXPOSURE, DEIBU_SUSCEPTIBILITY;
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("life");
        SUMO_DAMAGE=builder.comment("MC adaptation: bouts normally push and expend energy; enable one point of real injury at the result.").define("sumoDamage",false);
        HUNGER_INTERVAL = builder.comment("Loaded game ticks per nutrition point lost; no offline catch-up.")
                .defineInRange("hungerInterval", 240, 20, 24000);
        ENERGY_INTERVAL = builder.defineInRange("energyInterval", 160, 20, 24000);
        HYGIENE_INTERVAL = builder.defineInRange("hygieneInterval", 300, 20, 24000);
        WET_DAMAGE_THRESHOLD = builder.comment("Brief washing stays below this prolonged exposure threshold.")
                .defineInRange("wetDamageThreshold", 60, 20, 100);
        builder.pop().push("development");
        DEIBU_NUTRITION=builder.defineInRange("deibuNutritionMinimum",75,1,100);
        DEIBU_FRIENDLINESS=builder.comment("Environmental friendliness must be strictly below this value.").defineInRange("deibuFriendlinessBelow",30,1,100);
        DEIBU_EXPOSURE=builder.comment("Joint qualifying loaded adult ticks; progress decays twice as fast when conditions lapse.").defineInRange("deibuExposureTicks",24000,20,1728000);
        DEIBU_SUSCEPTIBILITY=builder.comment("Percent of fixed individual susceptibility ranks eligible in a lifetime, never a repeated chance roll.").defineInRange("deibuLifetimePercent",30,0,100);
        builder.pop().push("discovery");
        NATURAL_SPAWNS = builder.define("naturalSpawns", true);
        LOCAL_SPAWN_CAP = builder.defineInRange("localSpawnCap", 8, 1, 64);
        COMMUNITIES = builder.comment("Discover households and forest communities from natural spawns, once per 64x64 area.")
                .define("communities", true);
        FOREST_CHANCE = builder.defineInRange("forestCommunityChance", 25, 0, 50);
        FAMILY_CHANCE = builder.defineInRange("smallFamilyChance", 30, 0, 50);
        LEADER_CHANCE = builder.defineInRange("patchouliLeaderChance", 50, 0, 100);
        FOREST_MIN_HOUSEHOLDS = builder.defineInRange("forestMinHouseholds", 6, 3, 24);
        FOREST_MAX_HOUSEHOLDS = builder.defineInRange("forestMaxHouseholds", 12, 3, 24);
        HOUSEHOLD_MAX_CHILDREN = builder.defineInRange("householdMaxChildren", 5, 1, 8);
        COMMUNITY_SPAWN_CAP = builder.comment("Initial forest population budget, independent of ordinary solitary spawn limits.")
                .defineInRange("communitySpawnCap", 96, 1, 256);
        COMMUNITY_POPULATION_CAP = builder.comment("Forest population including reserved unborn children; leave room above the initial budget.")
                .defineInRange("communityPopulationCap", 128, 2, 512);
        builder.pop();
        SPEC = builder.build();
    }
    private YukkuriConfig() {}
}
