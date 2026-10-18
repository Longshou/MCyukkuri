package io.github.longshou.mcyukkuri.rare;

import io.github.longshou.mcyukkuri.entity.YukkuriEntity;
import io.github.longshou.mcyukkuri.settlement.Season;
import io.github.longshou.mcyukkuri.settlement.Seasons;
import io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind;

/**
 * Behaviour of the appearance forms of komicolle 40720 No.11518905 that need no species of their own (docs/RARE_FORMS.md).
 * The forms themselves are {@link Kind} values (saved, synced, inherited through SubspeciesLife.birthFlags).
 *
 * <ul>
 *   <li>Kotatsu Marisa (wiki marisa/kotatsu-marisa: L12 an accessory form, L23 warm inside, active in winter, sleeping
 *       through summer): while wearing the kotatsu a cold felt temperature is warmed by up to {@link #KOTATSU_WARMTH}, she skips the
 *       winter stay in the den and keeps foraging; in summer she rests day and night (the ordinary heat refuge takes her
 *       to shade first). Without the kotatsu she is an ordinary Marisa.
 *   <li>Long- and round-tailed Chen: appearance only.
 *   <li>Red-eyed Myon: appearance; a settlement that sees one counts it as an omen (settlement/Migration, wiki L05).
 * </ul>
 */
public final class RareForms {
    /** The kotatsu's own heat (L23 "内部发热"; L22 "耐寒怕热"): it warms a cold body up to {@link #KOTATSU_MILD} by at most
     *  this much and never heats a mild or hot one. Project numbers in biome-temperature units: snowy plains in winter
     *  (-0.8 by day) become comfortable, summer stays as hot as for anyone. */
    public static final double KOTATSU_WARMTH = 1.4, KOTATSU_MILD = .8;
    private static final int TALK_INTERVAL = 2400;

    public static boolean kotatsu(YukkuriEntity entity) {
        return entity.subspecies().kind() == Kind.KOTATSU_MARISA && entity.life().hasAccessory();
    }
    public static double kotatsuWarmth(YukkuriEntity entity, double felt) {
        return kotatsu(entity) && felt < KOTATSU_MILD ? Math.min(KOTATSU_WARMTH, KOTATSU_MILD - felt) : 0;
    }
    /** Summer sleep of a kotatsu Marisa: her rest time covers the whole day. */
    public static boolean summerDormant(YukkuriEntity entity) { return kotatsu(entity) && Seasons.is(entity, Season.SUMMER); }
    /** In winter she stays out and about instead of keeping to the den. */
    public static boolean winterActive(YukkuriEntity entity) { return kotatsu(entity) && Seasons.is(entity, Season.WINTER); }
    public static boolean redEye(YukkuriEntity entity) { return entity.subspecies().kind() == Kind.REDEYE_MYON; }

    /** Occasional remarks that make the season visible: too hot in summer, snug and inviting in winter. */
    public static void tick(YukkuriEntity entity) {
        if (entity.level().isClientSide || !kotatsu(entity) || (entity.tickCount + entity.getId()) % TALK_INTERVAL != 0) return;
        if (entity.life().isSleeping() || entity.isPanicking()) return;
        if (Seasons.is(entity, Season.SUMMER)) entity.showReaction("sleepy", "kotatsu_hot", 60);
        else if (Seasons.is(entity, Season.WINTER)) entity.showReaction("happy", "kotatsu_warm", 60);
    }
    private RareForms() {}
}
