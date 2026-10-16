package io.github.longshou.mcyukkuri.season;

import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.Season;
import io.github.longshou.mcyukkuri.settlement.Seasons;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;

/** Original MC climate adjustments, before real wetness, bedding and visible heat sources. */
public final class SeasonClimate {
    private SeasonClimate() {}
    public static boolean applies(DimensionType dimension) {
        return dimension.hasSkyLight() && !dimension.hasFixedTime();
    }
    /** Climate derives from the same biome fact as settlement behaviour, never from the old clock. */
    public static double offset(ServerLevel level, BlockPos pos) {
        if (!Seasons.enabled() || !applies(level.dimensionType())) return 0;
        return offset(Seasons.at(level, pos));
    }
    private static double offset(Season season) {
        double value = switch (season) {
            case SPRING -> YukkuriConfig.SPRING_TEMPERATURE.get();
            case SUMMER -> YukkuriConfig.SUMMER_TEMPERATURE.get();
            case AUTUMN -> YukkuriConfig.AUTUMN_TEMPERATURE.get();
            case WINTER -> YukkuriConfig.WINTER_TEMPERATURE.get();
        };
        return Double.isFinite(value) ? Math.clamp(value, -2, 2) : 0;
    }
    /** Spring with its default zero offset exactly retains the pre-season daytime formula. */
    public static double ambient(double biome, double offset, boolean skylight, boolean night, boolean shade, boolean rain) {
        if (!skylight) return biome;
        double felt = offset == 0 ? biome : biome + offset;
        if (night) felt -= shade ? .15 : .35;
        else if (biome >= .8) {
            // A real roof can remove extra summer heat. Never cool shade below the .8 floor
            // by this shading term; a cold ambient climate itself can still be below .8.
            double cooling = Math.min(.65 + Math.max(0, offset), Math.max(0, felt - .8));
            felt += shade ? -cooling : .35;
        }
        if (!shade && rain && biome < .2) felt -= .2;
        return felt;
    }
}
