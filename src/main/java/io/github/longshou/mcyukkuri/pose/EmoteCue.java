package io.github.longshou.mcyukkuri.pose;

/** Size and timing of the overhead symbol shared by the game and the preview. */
public final class EmoteCue {
    public static final int SWEAT_TICKS = 16;
    public static final float STEP_SECONDS = 2;

    public static double height(boolean house, String variant, float bodyHeight, float scale) {
        double top = house ? .35 : variant.endsWith("marisa") ? 1.25 : .87;
        return Math.max(bodyHeight, top * scale) + .12 * scale;
    }

    public static float size(float scale) { return .4f * scale; }

    private EmoteCue() {}
}
