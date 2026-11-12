package io.github.longshou.mcyukkuri.entity;

import java.util.Arrays;
import java.util.Optional;

/** Durations from the selected standard animation-rig.json and the lore recipe, at 20 game ticks/second. */
public enum YukkuriAction {
    IDLE("idle", 1.0, true),
    STRETCH_SHAPE("stretch_shape", 1.0, false),
    SIDE_BEND("side_bend", 2.36, true),
    WADDLE("waddle", 1.2, true),
    PUFF("puff", 2.8, true),
    BRACE("brace", 2.4, true),
    STARTLE("startle", 0.6, false),
    TURN("turn", 0.4, false),
    HOP("hop", 0.8, true),
    HOP_LEG("hop_leg", 0.9, false),
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
    MEAL_CONTENT("meal_content", 3.6, false),
    DRINK("drink", 1.6, true),
    SLEEP("sleep", 4.0, true),
    WAKE("wake", 3.4, false),
    NUZZLE("nuzzle", 0.8, true),
    COMFORT_LICK("comfort_lick", 1.6, true),
    SING("sing", 2.0, true),
    SWAY("sway", 2.4, true),
    PUSH("push", 1.2, true),
    GREET("greet", 1.4, false),
    TREMBLE("tremble", 0.5, true),
    DROOP("droop", 4.0, true),
    LOOK_AROUND("look_around", 4.0, true),
    CALL_CHILD("call_child", 3.2, false),
    SHAKE("shake", 1.2, false),
    BROOD("brood", 4.0, true),
    LABOR("labor", 7.0, true),
    MIDWIFE("midwife", 7.0, true),
    BEREAVED("bereaved", 5.0, false),
    POSTPARTUM("postpartum", 8.0, false),
    FIRST_WAKE("first_wake", 2.0, false),
    BOAT_DRIFT("boat_drift", 4.0, true),
    BOAT_STEADY("boat_steady", 1.5, true),
    BOAT_PUFF("boat_puff", 2.8, true),
    BOAT_CALL("boat_call", 2.0, true),
    BOAT_BROOD("boat_brood", 4.0, true),
    BOAT_PADDLE("boat_paddle", 1.6, true),
    BOAT_PADDLE_BRAID("boat_paddle_braid", 1.6, true),
    BANK_BECKON("bank_beckon", 1.6, true),
    WATER_BECKON("water_beckon", 1.6, true),
    BOAT_GATHER("boat_gather", 1.6, false),
    BOAT_EAT("boat_eat", 1.2, true),
    BOAT_DRINK("boat_drink", 1.6, true),
    BOAT_BOARD("boat_board", .8, true),
    BOAT_FEED_YOUNG("boat_feed_young", 2.4, false),
    BOAT_NUZZLE("boat_nuzzle", 2.4, false),
    BOAT_TOILET("boat_toilet", 2.3, false),
    KID_BRIM_SPLASH("kid_brim_splash", 2.4, true),
    KID_MIMIC_PADDLE("kid_mimic_paddle", 1.8, true),
    KID_HIDE_BEHIND("kid_hide_behind", 2.4, false),
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
    BOAT_FIRST_BALANCE("boat_first_balance", 2.0, false),
    HAT_STUMBLE("hat_stumble", 1.2, true),
    HAT_JOSTLE("hat_jostle", 1.0, true),
    WATER_PLUNGE("water_plunge", .6, false),
    WATER_STRUGGLE("water_struggle", 1.6, true),
    WATER_GASP("water_gasp", 1.6, true),
    WATER_SWIM("water_swim", 1.2, true),
    WATER_RECOVER("water_recover", 2.8, false),
    WATER_HAT_FLIP("water_hat_flip", .8, false),
    WATER_HAT_GRIP("water_hat_grip", 1.0, true),
    WATER_HAT_CLIMB("water_hat_climb", 1.8, false),
    BOAT_REASSURE("boat_reassure", 2.0, true),
    BOAT_ALARM_CALL("boat_alarm_call", 1.2, false),
    BOAT_ALARM_WAIT("boat_alarm_wait", 2.0, true),
    BOAT_FLEE("boat_flee", 2.0, true),
    BOAT_CALM("boat_calm", 4.0, false),
    BOAT_ANSWER_CALL("boat_answer_call", 1.0, false),
    BRAID_RESCUE_REACH("braid_rescue_reach", 1.1, false),
    BRAID_RESCUE_HOLD("braid_rescue_hold", 1.2, false),
    BRAID_RESCUE_SETTLE("braid_rescue_settle", .9, false),
    // Brim-dip, mouth-bite redesign (project original, not canon): see docs/FEATURES.md.
    HAT_BRIM_OFFER("hat_brim_offer", 1.2, true),
    HAT_HOP_UP("hat_hop_up", .8, false),
    WATER_BOB_LUNGE("water_bob_lunge", .7, false),
    HAT_SLIDE_OVER("hat_slide_over", 1.4, false),
    WATER_MOUTH_GRIP("water_mouth_grip", .8, true),
    WATER_HAT_SHAKE("water_hat_shake", 1.2, false),
    WATER_HAT_PUSH("water_hat_push", .8, false),
    BOAT_NUDGE_YOUNG("boat_nudge_young", .8, false),
    // Braid and mouth tools, capsize and self-rescue (lore aquatic_tools.py; project original, water 11 ruling).
    BRAID_CRADLE_LIFT("braid_cradle_lift", 2.4, false),
    // Marisa's braid lifting a young onto her brim edge and back down (lore braid_keys.py; ParentCarry.BRIM_LIFT).
    BRAID_BRIM_LIFT("braid_brim_lift", 2.6, false),
    BRAID_BRIM_LOWER("braid_brim_lower", 2.6, false),
    BE_CRADLED("be_cradled", 1.2, true),
    MOUTH_BITE_HOLD("mouth_bite_hold", 1.0, true),
    MOUTH_RELEASE("mouth_release", 1.6, false),
    BOAT_THROWN("boat_thrown", .6, false),
    WATER_HAT_RIGHT("water_hat_right", 1.6, false),
    BOAT_CARGO_STOW("boat_cargo_stow", 1.0, false),
    BOAT_CARGO_FETCH("boat_cargo_fetch", 1.0, false),
    BOAT_JETTISON("boat_jettison", 1.0, false),
    // Resting crew of the hat home (hat_deck_layout.json crew, ecology design D 3.4; project original).
    BOAT_RIM_PEEK("boat_rim_peek", 4.8, true),
    // Pet shop, exam and raising performances (lore recipe v13, all standard forms).
    THINK("think", 2.0, true),
    NERVOUS("nervous", 1.5, true),
    PROUD("proud", 2.0, false),
    JOY_JUMP("joy_jump", 1.6, false),
    SULK("sulk", 2.4, true),
    HIDE_FACE("hide_face", 2.0, true),
    BEG("beg", 1.4, true),
    BOW("bow", 1.2, false),
    SHY("shy", 2.0, true),
    DOZE_OFF("doze_off", 4.5, false),
    STRUGGLE("struggle", 1.0, true),
    BASKET_IN("basket_in", 0.8, false),
    BASKET_OUT("basket_out", 1.6, false),
    IGNORE("ignore", 1.6, false),
    RESTRAIN("restrain", 1.6, true),
    // Badge social arcs (lore recipe v16, all standard forms).
    SNATCH_PULL("snatch_pull", 1.2, false),
    STAND_GROUND("stand_ground", 1.6, true),
    OFFER_BITE("offer_bite", 1.4, false),
    BOAT_LABOR("boat_labor", 12.0, true),
    // Weather and warmth moments (lore recipe v18, all standard forms): pressed beside a partner on its left or right in the rain,
    // flattened from a thunderstorm, basking flat in the winter sun.
    RAIN_HUDDLE_LEFT("rain_huddle_left", 4.0, true),
    RAIN_HUDDLE_RIGHT("rain_huddle_right", 4.0, true),
    THUNDER_COWER("thunder_cower", 3.2, true),
    SUNBATHE("sunbathe", 10.0, true),
    // A frightened puff with tears (lore recipe): a parent taunting a predator away from its child (moment behavior_protect_taunt).
    PUKU_TEARY("puku_teary", 3.2, true),
    // Landed base rig: launch .36, impact .52, settled 1.1 seconds.
    LUNGE_RAM("lunge_ram", 1.1, false),
    SUKKIRI_PAIR_LOOP("sukkiri_pair_loop", 1.6, true),
    SUKKIRI_MAMU_LOOP("sukkiri_mamu_loop", 1.6, true),
    SUKKIRI_PAIR_CLIMAX("sukkiri_pair_climax", 6.5, false),
    SUKKIRI_MAMU_CLIMAX("sukkiri_mamu_climax", 6.5, false),
    SUKKIRI_ALICE_LOOP("sukkiri_alice_loop", 1, true),
    SUKKIRI_HELD_LOOP("sukkiri_held_loop", 1, true),
    SUKKIRI_HELD_CLIMAX("sukkiri_held_climax", 6.5, false),
    SUKKIRI_ALICE_CHASE("sukkiri_alice_chase", 1.4, true),
    SUKKIRI_AFTER_DAZED("sukkiri_after_dazed", 6, false),
    SUKKIRI_AFTER_TENDER("sukkiri_after_tender", 6, false),
    SUKKIRI_AFTER_PROUD("sukkiri_after_proud", 6, false),
    SUKKIRI_AFTER_MANIC("sukkiri_after_manic", 6, false);

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
    public static boolean sukkiri(String id) { return id.startsWith("sukkiri_"); }
    public static Optional<YukkuriAction> byId(String id) {
        return Arrays.stream(values()).filter(action -> action.id.equals(id)).findFirst();
    }
}
