package io.github.longshou.mcyukkuri.season;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Observation text for the location-derived season system: a position's biome is the only season
 * fact used by gameplay.
 */
public final class SeasonCalendar {
    private SeasonCalendar() {}

    public static Component page(ServerLevel level, BlockPos pos) {
        Component season = Seasons.enabled()
                ? Component.translatable("season.mcyukkuri." + Seasons.at(level, pos).id())
                : Component.translatable("season.mcyukkuri.off");
        Component shift = Component.literal(String.format(Locale.ROOT, "%+.2f", SeasonClimate.offset(level, pos)));
        return Component.translatable("notebook.mcyukkuri.biome_season", season, shift);
    }
}
