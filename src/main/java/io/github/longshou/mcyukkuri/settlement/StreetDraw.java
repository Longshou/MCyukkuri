package io.github.longshou.mcyukkuri.settlement;

import static io.github.longshou.mcyukkuri.social.SocialDisposition.ramp;

/**
 * How much of a daytime a village stray spends on the street. At a stroll start an off-spell stray draws a spell with a chance
 * that grows with hunger, nerve and friendliness and fades out at dusk; while it lasts the stray strolls around the village heart
 * instead of its burrow, so a walker with food meets the strays that live at the village edge. Strays sit at nutrition 45-95
 * (bins open below 40, stalls below 45), so hunger spans 85 down to 40; dusk fades 10000 to 12000 so a 50-block walk home fits.
 */
public final class StreetDraw {
    /** A food holder this far beyond the notice range still carries scent (the notice range itself belongs to FoodNoticeGoal). */
    public static final int SCENT = 12;
    /** Blocks a stray strolls around a food holder it is drawn to. */
    public static final int HOLDER_RADIUS = 10;
    /** After a spell finds no walkable hop (a ravine, steep ground) no new draw starts this long, so the stray is never pinned. */
    public static final int BLOCKED_TICKS = 2400;

    private StreetDraw() {}

    /** 1 until tick 10000 of the day, fading to 0 at 12000 and staying 0 through the night. */
    public static double daylight(long dayTime) {
        return 1 - ramp(Math.floorMod(dayTime, 24000L), 10000, 12000);
    }

    /** 0 at nutrition 85 or more, 1 at 40 or less. */
    public static double hunger(float nutrition) { return ramp(nutrition, 85, 40); }

    private static double nerve(int boldness) { return .4 + .6 * ramp(boldness, 20, 80); }

    /** Chance that an off-spell stray starts a spell at a stroll start: 0 at night, up to about 0.6 for a hungry bold friendly one. */
    public static double chance(float nutrition, int boldness, int friendliness, double daylight) {
        return (.08 + .55 * hunger(nutrition)) * nerve(boldness) * (.7 + .3 * ramp(friendliness, 20, 80)) * daylight;
    }

    /** 1200 ticks for a fed stray, 4800 for a starving one. */
    public static int ticks(float nutrition) { return 1200 + (int) Math.round(3600 * hunger(nutrition)); }

    /** Chance per stroll that a food holder in scent range draws the stray: 0.4 fed and timid up to 0.9 hungry and bold. */
    public static double holderChance(float nutrition, int boldness, double daylight) {
        return (.4 + .5 * hunger(nutrition) * ramp(boldness, 20, 80)) * daylight;
    }
}
