package io.github.longshou.mcyukkuri.season;

import io.github.longshou.mcyukkuri.MCYukkuri;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** The overworld is the sole clock authority; no per-dimension or entity ticker. */
@EventBusSubscriber(modid = MCYukkuri.MOD_ID)
public final class SeasonCalendar {
    private SeasonCalendar() {}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var overworld = event.getServer().overworld();
        SeasonData.get(overworld).sample(overworld.getDayTime(),
                overworld.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT), YukkuriConfig.SEASON_DAYS.get());
    }
    public static Component page(ServerLevel level) {
        var date = SeasonData.get(level).snapshot();
        Component shift = SeasonClimate.applies(level.dimensionType())
                ? Component.literal(String.format(Locale.ROOT, "%+.2f", SeasonClimate.offset(level)))
                : Component.translatable("season.mcyukkuri.calendar_only");
        return Component.translatable("notebook.mcyukkuri.season",
                Component.translatable("season.mcyukkuri." + date.season().name().toLowerCase(Locale.ROOT)),
                date.day(), date.daysPerSeason(), shift,
                Component.translatable(level.getServer().overworld().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)
                        ? "season.mcyukkuri.running" : "season.mcyukkuri.paused"));
    }
}
