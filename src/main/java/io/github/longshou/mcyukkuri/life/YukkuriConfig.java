package io.github.longshou.mcyukkuri.life;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-side life rates and environment settings; physiology never catches up offline. */
public final class YukkuriConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue DEBUG_ENTITY_SAVE_VIEWS;
    public static final ModConfigSpec.IntValue HUNGER_INTERVAL;
    public static final ModConfigSpec.IntValue ENERGY_INTERVAL;
    public static final ModConfigSpec.IntValue HYGIENE_INTERVAL;
    public static final ModConfigSpec.IntValue WET_DAMAGE_THRESHOLD;
    public static final ModConfigSpec.IntValue LOCAL_SPAWN_CAP;
    public static final ModConfigSpec.BooleanValue NATURAL_SPAWNS;
    public static final ModConfigSpec.BooleanValue SUMO_DAMAGE;
    public static final ModConfigSpec.BooleanValue LICK_HEALS;
    public static final ModConfigSpec.BooleanValue SANCTION;
    public static final ModConfigSpec.BooleanValue COMMUNITIES;
    public static final ModConfigSpec.IntValue FOREST_CHANCE, FAMILY_CHANCE, LEADER_CHANCE;
    public static final ModConfigSpec.IntValue FOREST_MIN_HOUSEHOLDS, FOREST_MAX_HOUSEHOLDS, HOUSEHOLD_MAX_CHILDREN;
    public static final ModConfigSpec.IntValue COMMUNITY_SPAWN_CAP, COMMUNITY_POPULATION_CAP;
    public static final ModConfigSpec.IntValue MIGRATE_THRESHOLD, THREAT_DECAY_TICKS, MIGRATE_TIMEOUT, MIGRATE_MIN_DISTANCE, MIGRATE_MAX_DISTANCE;
    public static final ModConfigSpec.BooleanValue BIOME_SEASONS;
    public static final ModConfigSpec.IntValue AUTUMN_STOCK_PER_RESIDENT, WINTER_COURT_STOCK, SHARE_SURPLUS_PER_RESIDENT, SHARE_NEED_PER_RESIDENT,
            SHARE_COOLDOWN, ASSEMBLY_TICKS, WINTER_QUARREL_PENALTY;
    public static final ModConfigSpec.BooleanValue HOME_CLAIMS, HOME_CLAIM_ENCLOSURE;
    public static final ModConfigSpec.IntValue HOME_CLAIM_CHANCE, HOME_CLAIM_REGION_COOLDOWN, HOME_CLAIM_SETTLE_TICKS;
    public static final ModConfigSpec.IntValue BANDIT_CHANCE, MONOPOLY_ONE_IN, BANDIT_RAID_ONE_IN, COLLAPSE_ONE_IN, BIOME_EXPEDITION_ONE_IN;
    public static final ModConfigSpec.IntValue DEIBU_NUTRITION, DEIBU_FRIENDLINESS, DEIBU_EXPOSURE, DEIBU_SUSCEPTIBILITY;
    public static final ModConfigSpec.BooleanValue BADGE_SOCIAL, BADGE_SNATCH, BADGE_HOME_CLAIM;
    public static final ModConfigSpec.IntValue BADGE_REFILL_TICKS, BADGE_CAPACITY, BADGE_OBSERVER_COOLDOWN, BADGE_PAIR_COOLDOWN, BADGE_TARGET_COOLDOWN,
            BADGE_TARGET_PER_DAY, BADGE_WORLD_REFILL_TICKS, BADGE_WORLD_CAPACITY;
    public static final ModConfigSpec.IntValue REMAINS_STALE_TICKS, REMAINS_ROTTEN_TICKS;
    public static final ModConfigSpec.BooleanValue CANNIBAL_CARRION, CANNIBAL_UNRECOGNIZED, SACRIFICE, SELF_OFFERING;
    public static final ModConfigSpec.DoubleValue COLD_THRESHOLD, FREEZE_THRESHOLD, HOT_THRESHOLD, NEST_INSULATION;
    public static final ModConfigSpec.IntValue COLD_EXPOSURE_TICKS, FREEZE_DAMAGE_INTERVAL, HEAT_DRYNESS_INTERVAL;
    public static final ModConfigSpec.IntValue SEASON_DAYS;
    public static final ModConfigSpec.IntValue AUTUMN_STOCK_BUFFER;
    public static final ModConfigSpec.DoubleValue SPRING_TEMPERATURE, SUMMER_TEMPERATURE, AUTUMN_TEMPERATURE, WINTER_TEMPERATURE;
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("life");
        LICK_HEALS=builder.comment("Worldview (komicolle 11518838): a kin's lick closes mild wounds with sugar-water saliva; deep wounds still need care. Disable for comfort only.")
                .define("lickHeals",true);
        SANCTION=builder.comment("Worldview (komicolle 11518829, user correction 2026-10-18): the sanction is bullying, not killing. Harsh yukkuri gang up on a hatless individual they cannot recognise, drive off a horribly hurt hatless 'monster', and carry out what the community rules condemn. Pets and owned yukkuri are never sanctioned.")
                .define("sanction",true);
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
        SEASON_DAYS = builder.comment("Autumn stock planning horizon in game days. This does not advance or select seasons; seasons follow biome tags.")
                .defineInRange("daysPerSeason", 7, 1, 365);
        SPRING_TEMPERATURE = builder.comment("MC biome-temperature offsets, only in dimensions with skylight and a moving sun. Not Celsius.")
                .defineInRange("springTemperature", 0.0, -2.0, 2.0);
        SUMMER_TEMPERATURE = builder.defineInRange("summerTemperature", .45, -2.0, 2.0);
        AUTUMN_TEMPERATURE = builder.defineInRange("autumnTemperature", -.25, -2.0, 2.0);
        WINTER_TEMPERATURE = builder.defineInRange("winterTemperature", -.8, -2.0, 2.0);
        AUTUMN_STOCK_BUFFER = builder.comment("Autumn stock estimate: extra percent above ordinary-forage winter meals, plus dry household spoilage. A planning target, not guaranteed survival.")
                .defineInRange("autumnStockBufferPercent", 25, 0, 200);
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
        builder.pop().push("community");
        MIGRATE_THRESHOLD = builder.comment("Predator threat scale for migration: threat points (sightings 1, attacks 2, deaths 3) of 3x this value give the full predator share (0.7) of the migration pressure, which mainly comes from member deaths and famine; 0 never migrates.")
                .defineInRange("migrateThreshold",
                3, 0, 20);
        THREAT_DECAY_TICKS = builder.comment("Loaded ticks without a new predator report before the threat level drops by one.").defineInRange("threatDecayTicks", 24000, 600, 480000);
        MIGRATE_TIMEOUT = builder.comment("Maximum marching time before a migration without an arrived leader and majority is abandoned; timeout never marks arrival.").defineInRange("migrateTimeout", 6000, 600, 72000);
        MIGRATE_MIN_DISTANCE = builder.comment("Nearest distance of a new site from the old centre.").defineInRange("migrateMinDistance", 32, 16, 96);
        MIGRATE_MAX_DISTANCE = builder.comment("Farthest distance of a new site from the old centre.").defineInRange("migrateMaxDistance", 48, 16, 128);
        builder.pop().push("seasons");
        BIOME_SEASONS = builder.comment("Biomes act as seasons (snowy = winter, plains = spring...); false restores the season-free behaviour.").define("biomeSeasons", true);
        AUTUMN_STOCK_PER_RESIDENT = builder.comment("Autumn regions: stored food each resident aims for (other regions aim for 3).").defineInRange("autumnStockPerResident", 6, 3, 12);
        WINTER_COURT_STOCK = builder.comment("Winter regions: courtship starts only when stored food >= (residents + 2) * this.").defineInRange("winterCourtStockPerHead", 2, 0, 8);
        SHARE_SURPLUS_PER_RESIDENT = builder.comment("A household with stored food above residents * this hands out one portion at a gathering.").defineInRange("shareSurplusPerResident", 4, 2, 12);
        SHARE_NEED_PER_RESIDENT = builder.comment("A household with stored food below residents * this receives portions.").defineInRange("shareNeedPerResident", 2, 1, 6);
        SHARE_COOLDOWN = builder.comment("Loaded ticks between two portions carried by the same resident.").defineInRange("shareCooldown", 1200, 100, 24000);
        ASSEMBLY_TICKS = builder.comment("How long the gathering after snowfall or on a spring morning lasts.").defineInRange("assemblyTicks", 1200, 200, 6000);
        WINTER_QUARREL_PENALTY = builder.comment("Affinity lost by each side when two starving housemates quarrel over empty stores.").defineInRange("winterQuarrelPenalty", 6, 0, 30);
        builder.pop().push("home_claims");
        HOME_CLAIMS = builder.comment("A wild family near a player's house or crops may claim it as its own home (home declaration).").define("enabled", true);
        HOME_CLAIM_CHANCE = builder.comment("Each eligibility check (about every 5 seconds per wild adult) starts a claim with chance 1/N.").defineInRange("checkOneIn", 20, 1, 10000);
        HOME_CLAIM_REGION_COOLDOWN = builder.comment("Loaded ticks before the same 64x64 area can see another claim.").defineInRange("regionCooldownTicks", 24000, 200, 1728000);
        HOME_CLAIM_SETTLE_TICKS = builder.comment("A claim nobody answers settles into a real shelter after this many loaded ticks (3 days by default).").defineInRange("settleAfterTicks",
                72000, 1200, 1728000);
        HOME_CLAIM_ENCLOSURE = builder.comment("Walled-in claimants can starve and fall out; false keeps them holding the site without the collapse.").define("enclosureCollapse", true);
        builder.pop().push("group_events");
        BANDIT_CHANCE = builder.comment("Percent of natural discoveries (after the forest and family ranges) that become a wandering bandit band.").defineInRange("banditChance", 5, 0, 20);
        MONOPOLY_ONE_IN = builder.comment("Each check (about every 5 seconds per eligible selfish adult) starts the nest monopoly with chance 1/N.").defineInRange("nestMonopolyOneIn", 8, 1, 10000);
        BANDIT_RAID_ONE_IN = builder.comment("Each check of a bandit near a stocked shelter starts a raid with chance 1/N.").defineInRange("banditRaidOneIn", 3, 1, 10000);
        COLLAPSE_ONE_IN = builder.comment("A crowded natural burrow soaked by long rain collapses with chance 1/N per 100-tick check.").defineInRange("burrowCollapseOneIn", 200, 1, 100000);
        BIOME_EXPEDITION_ONE_IN = builder.comment("A Marisa captain's expedition seeks a different biome with chance 1/N.").defineInRange("biomeExpeditionOneIn", 3, 1, 100);
        builder.pop().push("badge_social");
        BADGE_SOCIAL = builder.comment("Badge social reactions: edge-triggered sightings, a per-individual memory and a visible-reaction budget. false falls back to the throttled legacy reactions.")
                .define("enabled",
                true);
        BADGE_REFILL_TICKS = builder.comment("Loaded ticks for one budget token to refill around a player (a one-liner costs 1, a multi-beat arc 2).").defineInRange("budgetRefillTicks",
                6000, 600, 240000);
        BADGE_CAPACITY = builder.comment("Tokens the surroundings of one player can store; a restart starts full.").defineInRange("budgetCapacity", 2, 1, 8);
        BADGE_OBSERVER_COOLDOWN = builder.comment("Ticks between two visible reactions of the same observer.").defineInRange("observerCooldownTicks", 12000, 0, 480000);
        BADGE_PAIR_COOLDOWN = builder.comment("Ticks before the same observer may play another arc with the same subject (one-liners use half).").defineInRange("pairCooldownTicks", 24000, 0, 960000);
        BADGE_TARGET_COOLDOWN = builder.comment("Ticks between two reactions aimed at the same subject.").defineInRange("targetCooldownTicks", 6000, 0, 480000);
        BADGE_TARGET_PER_DAY = builder.comment("Visible reactions one subject can be the object of per game day.").defineInRange("targetPerDay", 4, 1, 64);
        BADGE_WORLD_REFILL_TICKS = builder.comment("Ticks per token of the small shared budget for places no player is close to (no audience).").defineInRange("worldRefillTicks", 12000, 600, 480000);
        BADGE_WORLD_CAPACITY = builder.comment("Tokens the shared far-from-players budget can store.").defineInRange("worldCapacity", 1, 1, 8);
        BADGE_HOME_CLAIM = builder.comment("A hungry wild yukkuri may declare a pet's home its own while the pet is close; the pet defends or reports to its owner.").define("homeClaim", true);
        BADGE_SNATCH = builder.comment("Rare: a grudge-bearing wild yukkuri pulls a lone pet's accessory (and badge) off. The item drops, never expires and is returned by the owner or fetched back. Off until verified in play.")
                .define("snatch",
                false);
        builder.pop();
        builder.push("remains");
        REMAINS_STALE_TICKS = builder.comment("Project design (komicolle gives no numbers): ticks lying in the world before remains turn stale (stale filling, a smell only yukkuri notice). Frozen while carried or buried.")
                .defineInRange("staleTicks",
                24000, 200, 24000000);
        REMAINS_ROTTEN_TICKS = builder.comment("Ticks lying in the world before remains are rotten (foul filling, strong smell, collapsed look).").defineInRange("rottenTicks", 72000, 400, 24000000);
        CANNIBAL_CARRION = builder.comment("Komicolle 11518847: eating one's own kind is forbidden yet not rare. Every dead yukkuri reeks to yukkuri, hat or not; only an extremely starved free individual overcomes the disgust and eats remains (never as a sweet).")
                .define("cannibalCarrion",
                true);
        CANNIBAL_UNRECOGNIZED = builder.comment("A starving free individual may hunt a living hatless yukkuri it cannot recognise as kin ('sweets'). Pets, owned and house individuals are never hunted and never hunt.")
                .define("cannibalUnrecognized",
                true);
        SELF_OFFERING = builder.comment("Project design on the 'please eat me' motif (wiki E05/E08): a parent with a starving child, no food in reach, may offer its own body; feeding the child in time stops it.")
                .define("selfOffering",
                true);
        SACRIFICE = builder.comment("A parent may draw a hunting predator away from its child onto itself (simplified self-sacrifice).").define("sacrifice", true);
        builder.pop();
        builder.push("debug");
        DEBUG_ENTITY_SAVE_VIEWS = builder.comment("Write the four read-only entity inspection views during saves. Enabled in newly generated playground worlds.").define("entitySaveViews", false);
        builder.pop();
        SPEC = builder.build();
    }
    private YukkuriConfig() {}
}
