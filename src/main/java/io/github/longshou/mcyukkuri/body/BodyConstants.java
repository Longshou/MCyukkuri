package io.github.longshou.mcyukkuri.body;

/** Existing body limits and authored scale conventions; no conversion of saved values. */
public final class BodyConstants {
    public static final int MAX_FILLING = 12;
    public static final int WOUND_CAP = 8;
    public static final float HOUSE_WOUND_SIZE = .35f;
    public static final float HOUSE_SOURCE_SCALE = .32f;
    /** House yukkuri stay frail at every growth stage; non-adults die to one or two ordinary hits. */
    public static final float HOUSE_BABY_HEALTH = 1;
    public static final float HOUSE_JUVENILE_HEALTH = 2;
    public static final float HOUSE_ADULT_HEALTH = 4;
    public static final float SOLE_HEIGHT_BAND = .22f;

    private BodyConstants() {}
}
