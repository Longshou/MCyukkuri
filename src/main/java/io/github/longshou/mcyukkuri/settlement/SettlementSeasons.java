package io.github.longshou.mcyukkuri.settlement;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.life.YukkuriConfig;
import io.github.longshou.mcyukkuri.season.Season;
import io.github.longshou.mcyukkuri.season.Seasons;
import net.minecraft.world.level.Level;

/** Settlement and household policy based on biome-driven seasons. */
public final class SettlementSeasons {
    /** Stable per settlement (its centre), else the shelter, else where the animal stands. */
    public static Season of(YukkuriEntity entity) {
        var group = entity.settlement().group();
        if (group != null) return Seasons.at(entity.level(), group.center);
        var shelter = entity.habitat().shelter();
        return Seasons.at(entity.level(), shelter != null ? shelter.getBlockPos() : entity.blockPosition());
    }

    public static Season of(Level level, SettlementData.Group group) { return Seasons.at(level, group.center); }

    /** False whenever the feature is switched off, so behaviour code is unchanged then. */
    public static boolean is(YukkuriEntity entity, Season season) {
        return Seasons.enabled() && !entity.level().isClientSide && of(entity) == season;
    }

    private static boolean starving(YukkuriEntity entity, ShelterBlockEntity shelter) {
        float nutrition = entity.life().nutrition();
        return shelter.food() == 0 && (entity.family().adult() ? nutrition < 30 : nutrition < 15);
    }

    /** Winter closed nest: after dark and in snowfall, residents with a usable shelter stay in it (unless starving). */
    public static boolean confined(YukkuriEntity entity) {
        if (!is(entity, Season.WINTER) || entity.profile().houseBody() || entity.profile().dangerToYukkuri()) return false;
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
        // The birth rule of a led community comes first (package 12, CommunityRules.mayCourt).
        if (!CommunityRules.mayCourt(entity)) return false;
        if (!is(entity, Season.WINTER)) return true;
        var shelter = entity.habitat().shelter();
        return shelter == null || shelter.food() >= (shelter.occupants() + 2) * YukkuriConfig.WINTER_COURT_STOCK.get();
    }

    /** A short gathering is under way at the settlement centre (after snowfall, or on a spring morning). */
    public static boolean assembling(Level level, SettlementData.Group group) { return Seasons.enabled() && group.gatherUntil > level.getGameTime(); }
    private SettlementSeasons() {}
}
