package io.github.longshou.mcyukkuri.entity;

import java.util.Arrays;
import java.util.Optional;

/** Durations from the selected standard animation-rig.json, at 20 game ticks/second. */
public enum YukkuriAction {
    IDLE("idle", 1.0, true),
    STRETCH_SHAPE("stretch_shape", 1.0, false),
    SIDE_BEND("side_bend", 2.36, true),
    WADDLE("waddle", 1.2, true),
    PUFF("puff", 6.4, true),
    BRACE("brace", 2.4, true),
    STARTLE("startle", 0.6, false),
    TURN("turn", 0.4, false),
    HOP("hop", 0.2617993877991494, true),
    STRETCH("stretch", 2.8, true),
    EXERCISE_VERTICAL("exercise_vertical", 2.62, true),
    EXERCISE_SIDES("exercise_sides", 2.36, true),
    PRESS("press", 4.8, true),
    TAISO("taiso", 14.0, true),
    FLEE("flee", 5.0, true),
    ANATOMY_MAMU_EXTEND("anatomy_mamu_extend", 0.24, false),
    ANATOMY_MAMU_RETRACT("anatomy_mamu_retract", 0.65, false),
    ANATOMY_PENI_EXTEND("anatomy_peni_extend", 0.24, false),
    ANATOMY_PENI_RETRACT("anatomy_peni_retract", 0.65, false);

    private final String id;
    private final double duration;
    private final boolean loop;

    YukkuriAction(String id, double duration, boolean loop) {
        this.id = id;
        this.duration = duration;
        this.loop = loop;
    }

    public String id() { return id; }
    public double duration() { return duration; }
    public int durationTicks() { return (int) Math.ceil(duration * 20.0); }
    public boolean loops() { return loop; }
    public boolean holdsEndPose() { return id.endsWith("_extend"); }
    public static Optional<YukkuriAction> byId(String id) {
        return Arrays.stream(values()).filter(action -> action.id.equals(id)).findFirst();
    }
}
