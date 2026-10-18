package io.github.longshou.mcyukkuri.season;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.settlement.Season;
import io.github.longshou.mcyukkuri.settlement.Seasons;
import io.github.longshou.mcyukkuri.settlement.ShelterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Original planning estimate, never a food grant, winter ration or survival guarantee. */
public final class AutumnStock {
    private AutumnStock() {}
    public static boolean autumn(ServerLevel level, BlockPos pos) {
        return Seasons.on(level, pos, Season.AUTUMN);
    }
    public static int target(ServerLevel level, ShelterBlockEntity home) {
        int routine = Math.min(24, home.occupants() * 3);
        if (!autumn(level, home.getBlockPos()) || home.occupants() == 0) return routine;
        int biomeMinimum = Math.min(40, home.occupants() * YukkuriConfig.AUTUMN_STOCK_PER_RESIDENT.get());
        // The retained value is a stock-planning horizon only. It never rotates the local season.
        int days = Math.clamp(YukkuriConfig.SEASON_DAYS.get(), 1, 365);
        int hunger = Math.clamp(YukkuriConfig.HUNGER_INTERVAL.get(), 20, 24_000);
        int buffer = Math.clamp(YukkuriConfig.AUTUMN_STOCK_BUFFER.get(), 0, 200);
        long winter = days * 24_000L;
        // Ordinary forage supplies 20 nutrition. Add household dry-storage spoilage, not a
        // claim that every tagged food has that nutrition or that damp storage stays safe.
        long divisor = hunger * 20L * 100;
        long meals = (winter * home.occupants() * (100 + buffer) + divisor - 1) / divisor;
        long spoilage = (winter + 5_999) / 6_000;
        return (int) Math.min(home.foodCapacity(), Math.max(Math.max(routine, biomeMinimum), meals + spoilage));
    }
    public static boolean fitCollector(YukkuriEntity entity) {
        var life = entity.life(); var family = entity.family(); var brood = family.brood();
        return family.adult() && !family.active() && !family.awaitingLanding() && family.recovery() == 0
                && (brood == null || brood.cancelled || brood.complete())
                && !entity.independence().householdOuting() && !life.hasUrgentNeed()
                && life.nutrition() >= 60 && life.energy() >= 55 && life.dryness() < 40
                && life.drugSleep() == 0 && life.sprayDelay() == 0 && life.spicyTicks() == 0
                && entity.getHealth() >= entity.getMaxHealth() * .75f
                && entity.body().condition().readyForAutumnStock()
                && !life.temperature().unsafeSleep();
    }
    public static Component observation(YukkuriEntity entity) {
        var home = entity.habitat().shelter();
        if (home == null) return Component.translatable("notebook.mcyukkuri.autumn_stock.none");
        var level = (ServerLevel) entity.level();
        String state = !autumn(level, home.getBlockPos()) ? "routine" : home.food() >= target(level, home) ? "ready"
                : fitCollector(entity) ? "gathering" : "rest";
        return Component.translatable("notebook.mcyukkuri.autumn_stock", home.food(), target(level, home), home.foodCapacity(),
                Component.translatable("autumn_stock.mcyukkuri." + state));
    }
}
