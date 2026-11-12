package io.github.longshou.mcyukkuri.family;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.motor.ClipMarkers;

/** Shared authored timing for courtship and Alice's contact. Their goals own all effects and exits. */
public final class SukkiriTimeline {
    public static final int PAIR_CYCLE = ClipMarkers.of(YukkuriAction.SUKKIRI_PAIR_LOOP.id()).length();
    public static final int ALICE_CYCLE = ClipMarkers.of(YukkuriAction.SUKKIRI_ALICE_LOOP.id()).length();
    private static final ClipMarkers.Clip CLIMAX = ClipMarkers.of(YukkuriAction.SUKKIRI_PAIR_CLIMAX.id());
    public static final int PEAK_END = CLIMAX.ticks("peak_end", 130);

    public enum Beat {
        BUILD("beat_build", 0, "heat_relief_1"),
        SU("beat_su", 30, "heat_relief_2"),
        SU_AGAIN("beat_su_again", 52, "heat_relief_2"),
        RELEASE("beat_release", 70, "heat_relief_3");

        public final String marker;
        public final int tick;
        public final String line;

        Beat(String marker, int fallback, String line) {
            this.marker = marker;
            this.tick = CLIMAX.ticks(marker, fallback);
            this.line = line;
        }
    }

    private static final Beat[] BEATS = Beat.values();

    public static Beat beatAt(int tick) {
        for (var beat : BEATS) if (tick == beat.tick) return beat;
        return null;
    }

    public static int cycles(int ticks, int cycle) {
        return Math.ceilDiv(ticks, cycle) * cycle;
    }

    private SukkiriTimeline() {}
}
