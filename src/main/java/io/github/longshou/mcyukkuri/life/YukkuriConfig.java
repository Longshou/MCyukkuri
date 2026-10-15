package io.github.longshou.mcyukkuri.life;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side life rates and environment settings; physiology never catches up offline. */
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
    public static final ModConfigSpec.DoubleValue COLD_THRESHOLD, FREEZE_THRESHOLD, HOT_THRESHOLD, NEST_INSULATION;
    public static final ModConfigSpec.IntValue COLD_EXPOSURE_TICKS, FREEZE_DAMAGE_INTERVAL, HEAT_DRYNESS_INTERVAL;
    public static final ModConfigSpec.IntValue SEASON_DAYS;
    public static final ModConfigSpec.DoubleValue SPRING_TEMPERATURE, SUMMER_TEMPERATURE, AUTUMN_TEMPERATURE, WINTER_TEMPERATURE;
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("life");
        SUMO_DAMAGE=builder.comment("MC adaptation: bouts normally push and expend energy; enable one point of real injury at the result.").define("sumoDamage",false);
        HUNGER_INTERVAL = builder.comment("Loaded game ticks per nutrition point lost; no offline catch-up.")
                .defineInRange("hungerInterval", 240, 20, 24000);
        ENERGY_INTERVAL = builder.defineInRange("energyInterval", 160, 20, 24000);
        HYGIENE_INTERVAL = builder.defineInRange("hygieneInterval", 300, 20, 24000);
        WET_DAMAGE_THRESHOLD = builder.comment("Wetness damage outside water. Immersion always deals one health point per second, regardless of this threshold.")
                .defineInRange("wetDamageThreshold", 60, 20, 100);
        builder.pop().push("temperature");
        COLD_THRESHOLD = builder.comment("Original gameplay thresholds in Minecraft biome-temperature units, not Celsius.")
                .defineInRange("coldBelow", .2, -2.0, 1.0);
        FREEZE_THRESHOLD = builder.comment("At or below this felt temperature, cold exposure accumulates; capped at coldBelow.")
                .defineInRange("freezeAtOrBelow", 0.0, -2.0, 1.0);
        HOT_THRESHOLD = builder.comment("Hot weather adds dryness. Effective threshold stays at least 0.5 above coldBelow.")
                .defineInRange("hotAtOrAbove", 1.5, .5, 4.0);
        NEST_INSULATION = builder.comment("Warmth from a real dry, intact, supplied nest while inside it; only in cold conditions.")
                .defineInRange("nestInsulation", 1.0, 0.0, 3.0);
        COLD_EXPOSURE_TICKS = builder.comment("Loaded cold-exposure units before freezing injury. Adults gain 1 per tick, juveniles 1.5, babies 2; warmth removes 2.")
                .defineInRange("coldExposureTicks", 2400, 20, 1728000);
        FREEZE_DAMAGE_INTERVAL = builder.defineInRange("freezeDamageInterval", 200, 20, 24000);
        HEAT_DRYNESS_INTERVAL = builder.defineInRange("heatDrynessInterval", 100, 20, 24000);
        builder.pop().push("seasons");
        SEASON_DAYS = builder.comment("Original adaptation: overworld daytime days per season. Changing this preserves the current season and its progress fraction.")
                .defineInRange("daysPerSeason", 7, 1, 365);
        SPRING_TEMPERATURE = builder.comment("MC biome-temperature offsets, only in dimensions with skylight and a moving sun. Not Celsius.")
                .defineInRange("springTemperature", 0.0, -2.0, 2.0);
        SUMMER_TEMPERATURE = builder.defineInRange("summerTemperature", .45, -2.0, 2.0);
        AUTUMN_TEMPERATURE = builder.defineInRange("autumnTemperature", -.25, -2.0, 2.0);
        WINTER_TEMPERATURE = builder.defineInRange("winterTemperature", -.8, -2.0, 2.0);
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
