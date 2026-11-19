package io.github.longshou.mcyukkuri.pose;

import io.github.longshou.mcyukkuri.mind.ExcitedLeak;
import java.util.function.Supplier;

/** Size and timing of the overhead symbol shared by the game and the preview. */
public final class EmoteCue {
    public static final int SWEAT_TICKS = 16;
    public record Step(float seconds, String symbol) {}

    public static String select(String expression, boolean sweat, float elapsed, float duration, Supplier<Step> frame) {
        if (ExcitedLeak.STARTLE_FACE.equals(expression)) return "exclaim";
        if (sweat) return "sweat";
        if (elapsed >= duration) return "";
        var step = frame.get();
        return step.seconds < 2 ? step.symbol : "";
    }

    public static double height(boolean house, String variant, float bodyHeight, float scale) {
        double top = house ? .35 : variant.endsWith("marisa") ? 1.25 : .87;
        return Math.max(bodyHeight, top * scale) + .12 * scale;
    }

    public static float size(float scale) { return .4f * scale; }

    private EmoteCue() {}
}
