package io.github.longshou.mcyukkuri.entity;

import java.util.Arrays;
import java.util.Optional;

/** Durations from the selected standard animation-rig.json and the lore recipe, at 20 game ticks/second. */
public enum YukkuriAction {
    IDLE("idle", 1.0, true),
    STRETCH_SHAPE("stretch_shape", 1.0, false),
    SIDE_BEND("side_bend", 2.36, true),
    WADDLE("waddle", 1.2, true),
    PUFF("puff", 6.4, true),
    BRACE("brace", 2.4, true),
    STARTLE("startle", 0.6, false),
    TURN("turn", 0.4, false),
    HOP("hop", 0.8, true),
    STRETCH("stretch", 2.8, true),
    EXERCISE_VERTICAL("exercise_vertical", 2.62, true),
    EXERCISE_SIDES("exercise_sides", 2.36, true),
    PRESS("press", 4.8, true),
    TAISO("taiso", 14.0, true),
    FLEE("flee", 5.0, true),
    ANATOMY_MAMU_EXTEND("anatomy_mamu_extend", 0.24, false),
    ANATOMY_MAMU_RETRACT("anatomy_mamu_retract", 0.65, false),
    ANATOMY_PENI_EXTEND("anatomy_peni_extend", 0.24, false),
    ANATOMY_PENI_RETRACT("anatomy_peni_retract", 0.65, false),
    // World-setting motions from motions/variants/lore/recipe.json, shipped in the motion corrections;
    // models outside a clip's `applies` group fall back to idle.
    EAT("eat", 1.2, true),
    SHIAWASE("shiawase", 2.0, false),
    DRINK("drink", 1.6, true),
    SLEEP("sleep", 4.0, true),
    WAKE("wake", 1.6, false),
    NUZZLE("nuzzle", 0.8, true),
    SING("sing", 2.0, true),
    SWAY("sway", 2.4, true),
    PUSH("push", 1.2, true),
    GREET("greet", 1.4, false),
    TREMBLE("tremble", 0.5, true),
    DROOP("droop", 4.0, true),
    LOOK_AROUND("look_around", 4.0, true),
    SHAKE("shake", 1.2, false),
    BROOD("brood", 4.0, true),
    LABOR("labor", 2.4, true),
    MIDWIFE("midwife", 2.4, true),
    BEREAVED("bereaved", 5.0, false),
    POSTPARTUM("postpartum", 3.0, false),
    FIRST_WAKE("first_wake", 2.0, false),
    BOAT_DRIFT("boat_drift", 4.0, true),
    BOAT_STEADY("boat_steady", 1.5, true),
    BOAT_CALL("boat_call", 2.0, true),
    BOAT_BROOD("boat_brood", 4.0, true),
    BOAT_LABOR("boat_labor", 2.4, true),
    BOAT_PADDLE("boat_paddle", 1.6, true),
    BOAT_GATHER("boat_gather", 1.6, false),
    BOAT_EAT("boat_eat", 1.2, true),
    BOAT_DRINK("boat_drink", 1.6, true),
    BOAT_SLEEP("boat_sleep", 4.0, true),
    BOAT_WAKE("boat_wake", 1.6, false),
    BOAT_BOARD("boat_board", .8, true),
    BOAT_FEED_YOUNG("boat_feed_young", 2.4, false),
    BOAT_NUZZLE("boat_nuzzle", 2.4, false),
    BOAT_LULLABY("boat_lullaby", 4.0, true),
    AQUATIC_HAT_CARE("aquatic_hat_care", 2.4, false),
    HAT_CRADLE("hat_cradle", 3.0, true),
    // Marisa's braid takes off, sets down, holds and puts back her own hat (lore hat.py); _AQ: the aquatic Marisa's larger hat floated at a bank.
    HAT_OFF("hat_off", 1.6, false),
    HAT_HOLD("hat_hold", 2.4, true),
    HAT_ON("hat_on", 1.6, false),
    HAT_OFF_SELF("hat_off_self", 1.8, false),
    HAT_ON_SELF("hat_on_self", 1.8, false),
    HAT_OFF_AQ("hat_off_aq", 1.6, false),
    HAT_HOLD_AQ("hat_hold_aq", 2.4, true),
    HAT_ON_AQ("hat_on_aq", 1.6, false),
    HAT_OFF_SELF_AQ("hat_off_self_aq", 1.8, false),
    HAT_ON_SELF_AQ("hat_on_self_aq", 1.8, false),
    /** Shell Marisa only; other models fall back to idle. */
    SHELL_RETRACT("shell_retract", 6.0, true),
    HAT_BRIM("hat_brim", 3.0, true),
    BOAT_FIRST_BALANCE("boat_first_balance", 2.0, false);

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
