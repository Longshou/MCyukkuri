package io.github.longshou.mcyukkuri.season;

import io.github.longshou.mcyukkuri.MCYukkuri;
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

    public static boolean on(Level level, BlockPos pos, Season season) { return enabled() && at(level, pos) == season; }

    /** Snow falls where winter biomes get precipitation; rain checks at a position fail on snow. */
    public static boolean snowing(Level level, BlockPos pos) { return level.isRaining() && on(level, pos, Season.WINTER); }

    public static boolean noon(Level level) { long time = level.getDayTime() % 24000; return time >= 5000 && time < 8000; }
    private Seasons() {}
}
