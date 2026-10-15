package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/** Single entry point for biome-driven seasons; a future season mod only needs to replace {@link #at}. */
public final class Seasons {
    public static final TagKey<Biome> SPRING = TagKey.create(Registries.BIOME, MCYukkuri.id("season_spring"));
    public static final TagKey<Biome> SUMMER = TagKey.create(Registries.BIOME, MCYukkuri.id("season_summer"));
    public static final TagKey<Biome> AUTUMN = TagKey.create(Registries.BIOME, MCYukkuri.id("season_autumn"));
    public static final TagKey<Biome> WINTER = TagKey.create(Registries.BIOME, MCYukkuri.id("season_winter"));

    public static boolean enabled() { return YukkuriConfig.BIOME_SEASONS.get(); }
    /** Data-driven biome tags first (winter, autumn, summer, spring); untagged biomes fall back on base temperature. */
    public static Season at(Level level, BlockPos pos) {
        var biome = level.getBiome(pos);
        if (biome.is(WINTER)) return Season.WINTER;
        if (biome.is(AUTUMN)) return Season.AUTUMN;
        if (biome.is(SUMMER)) return Season.SUMMER;
        if (biome.is(SPRING)) return Season.SPRING;
        float temperature = biome.value().getBaseTemperature();
        // [original] thresholds for modded biomes without a tag.
        return temperature < .15f ? Season.WINTER : temperature >= 1f ? Season.SUMMER : temperature < .5f ? Season.AUTUMN : Season.SPRING;
    }
    /** Stable per settlement (its centre), else the shelter, else where the animal stands. */
    public static Season of(YukkuriEntity entity) {
        var group = entity.settlement().group();
        if (group != null) return at(entity.level(), group.center);
        var shelter = entity.habitat().shelter();
        return at(entity.level(), shelter != null ? shelter.getBlockPos() : entity.blockPosition());
    }
    public static Season of(Level level, SettlementData.Group group) { return at(level, group.center); }
    /** False whenever the feature is switched off, so behaviour code is unchanged then. */
    public static boolean is(YukkuriEntity entity, Season season) {
        return enabled() && !entity.level().isClientSide && of(entity) == season;
    }
    public static boolean on(Level level, BlockPos pos, Season season) { return enabled() && at(level, pos) == season; }
    /** Snow falls where winter biomes get precipitation; rain checks at a position fail on snow. */
    public static boolean snowing(Level level, BlockPos pos) { return level.isRaining() && on(level, pos, Season.WINTER); }
    public static boolean noon(Level level) { long time = level.getDayTime() % 24000; return time >= 5000 && time < 8000; }

    private static boolean starving(YukkuriEntity entity, ShelterBlockEntity shelter) {
        float nutrition = entity.life().nutrition();
        return shelter.food() == 0 && (entity.family().adult() ? nutrition < 30 : nutrition < 15);
    }
    /** Winter closed nest: after dark and in snowfall, residents with a usable shelter stay in it (unless starving). */
    public static boolean confined(YukkuriEntity entity) {
        if (!is(entity, Season.WINTER) || entity.getVariant().isHouse() || entity.getVariant().isPredator()) return false;
        var shelter = entity.habitat().shelter();
        if (shelter == null || !shelter.usable(entity)) return false;
        if (!entity.level().isNight() && !entity.level().isRaining()) return false;
        return !starving(entity, shelter);
    }
    /** Winter foraging is limited to short daytime trips when the household store is short; never while confined. */
    public static boolean forageBlocked(YukkuriEntity entity) {
        if (confined(entity)) return true;
        if (!is(entity, Season.WINTER)) return false;
        var shelter = entity.habitat().shelter();
        return shelter != null && shelter.usable(entity) && !starving(entity, shelter) && shelter.food() >= shelter.occupants() * 2;
    }
    /** Natural courtship in a winter region needs a household store that can carry the extra mouths. */
    public static boolean mayCourt(YukkuriEntity entity) {
        if (!is(entity, Season.WINTER)) return true;
        var shelter = entity.habitat().shelter();
        return shelter == null || shelter.food() >= (shelter.occupants() + 2) * YukkuriConfig.WINTER_COURT_STOCK.get();
    }
    /** A short gathering is under way at the settlement centre (after snowfall, or on a spring morning). */
    public static boolean assembling(Level level, SettlementData.Group group) { return enabled() && group.gatherUntil > level.getGameTime(); }
    private Seasons() {}
}
