package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Semantic state wins over a stale reaction; the five added atlases retain approved UV/geometry. */
public final class EcologyPresentation {
    /** Body motion for a stationary behavior; walking is chosen before this (see motions/variants/lore). */
    public static YukkuriAction action(String behavior) {
        return switch (behavior) {
            case "unun_exercise" -> YukkuriAction.TAISO;
            case "toilet", "ground_toilet", "boat_toilet", "toilet_accident", "first_toilet_stimulated" -> YukkuriAction.BRACE;
            case "take_food", "chew", "family_chew", "swallow" -> YukkuriAction.EAT;
            case "receive_mash" -> YukkuriAction.DRINK;
            case "meal_finish" -> YukkuriAction.SHIAWASE;
            case "drink" -> YukkuriAction.DRINK;
            case "sleep_enter", "sleep", "shade_rest" -> YukkuriAction.SLEEP;
            case "house_sleep", "house_hide" -> YukkuriAction.SLEEP;
            case "house_peek", "house_search_home" -> YukkuriAction.LOOK_AROUND;
            case "house_tend", "house_listen" -> YukkuriAction.IDLE;
            case "house_startled" -> YukkuriAction.STARTLE;
            case "seek_warmth", "warm_up" -> YukkuriAction.TREMBLE;
            // status hub reactions: predator in view, rain or snow on the body
            case "threat_watch" -> YukkuriAction.LOOK_AROUND;
            case "threat_hide", "rain_wait", "rain_huddle" -> YukkuriAction.TREMBLE;
            case "rain_shake" -> YukkuriAction.SHAKE;
            case "seek_shade" -> YukkuriAction.LOOK_AROUND;
            case "independence_build", "nest_build", "nest_place", "nest_spread", "nest_press", "nest_harvest" -> YukkuriAction.BRACE;
            // These distinct care/learning states select matching authored clips in YukkuriModel.
            // The base enum remains the closest locomotion family so server animation timing stays shared.
            case "feed_child", "first_toilet_assist" -> YukkuriAction.NUZZLE;
            case "first_toilet_seek" -> YukkuriAction.LOOK_AROUND;
            case "newborn_crawl", "hat_walk" -> YukkuriAction.WADDLE;
            case "learn_jump" -> YukkuriAction.HOP;
            case "newborn_wait", "first_toilet_wait", "care_finish", "learn_watch" -> YukkuriAction.IDLE;
            case "boat_carry_young", "boat_be_carried" -> YukkuriAction.BRACE;
            case "nest_gather" -> YukkuriAction.LOOK_AROUND;
            case "independence_farewell", "independence_visit" -> YukkuriAction.GREET;
            case "independence_gather", "independence_return" -> YukkuriAction.LOOK_AROUND;
            case "wake" -> YukkuriAction.WAKE;
            case "nuzzle" -> YukkuriAction.NUZZLE;
            case "comfort_lick" -> YukkuriAction.COMFORT_LICK;
            case "comfort_receive" -> YukkuriAction.DROOP;
            case "sing" -> YukkuriAction.SING;
            case "listen_song", "exercise_watch" -> YukkuriAction.SWAY;
            case "search_family", "wait_child", "stay_home" -> YukkuriAction.LOOK_AROUND;
            case "tend_brood" -> YukkuriAction.BROOD;
            case "labor" -> YukkuriAction.LABOR;
            case "postpartum" -> YukkuriAction.POSTPARTUM;
            case "midwife" -> YukkuriAction.MIDWIFE;
            case "boat_launch", "voyage_launch" -> YukkuriAction.HAT_OFF_SELF_AQ;
            case "voyage_prepare", "voyage_wait_parent", "voyage_watch" -> YukkuriAction.LOOK_AROUND;
            case "voyage_praise" -> YukkuriAction.GREET;
            case "voyage_proud" -> YukkuriAction.SHIAWASE;
            case "aquatic_watch_parent", "aquatic_paddle_practice", "aquatic_imitate_paddle",
                 "boat_return_family", "boat_follow_family" -> YukkuriAction.BOAT_DRIFT;
            case "aquatic_answer_family" -> YukkuriAction.BOAT_LULLABY;
            case "boat_guard_young" -> YukkuriAction.BOAT_STEADY;
            case "boat_rocked", "hat_stumble" -> YukkuriAction.HAT_STUMBLE;
            case "boat_capsizing" -> YukkuriAction.BOAT_STEADY;
            case "hat_jostle", "hat_scramble", "hat_slipping" -> YukkuriAction.HAT_JOSTLE;
            case "boat_reassure", "boat_rescue_young", "boat_crowd_guard" -> YukkuriAction.BOAT_REASSURE;
            case "boat_rescue_approach" -> YukkuriAction.BOAT_PADDLE;
            case "boat_alarm_call" -> YukkuriAction.BOAT_ALARM_CALL;
            case "boat_alarm_wait" -> YukkuriAction.BOAT_ALARM_WAIT;
            case "boat_flee", "boat_return_called" -> YukkuriAction.BOAT_FLEE;
            case "boat_calm" -> YukkuriAction.BOAT_CALM;
            case "boat_answer_call" -> YukkuriAction.BOAT_ANSWER_CALL;
            case "braid_rescue_offer" -> YukkuriAction.HAT_BRIM_OFFER;
            case "braid_rescue_push" -> YukkuriAction.BOAT_NUDGE_YOUNG;
            case "braid_rescue_reach" -> YukkuriAction.BRAID_RESCUE_REACH;
            case "braid_rescue_hold" -> YukkuriAction.BRAID_RESCUE_HOLD;
            case "braid_rescue_settle" -> YukkuriAction.BRAID_RESCUE_SETTLE;
            case "braid_be_rescued" -> YukkuriAction.WATER_GASP;
            case "water_impact" -> YukkuriAction.WATER_PLUNGE;
            case "escape_water", "struggle", "water_struggle" -> YukkuriAction.WATER_STRUGGLE;
            case "water_exhausted" -> YukkuriAction.WATER_GASP;
            case "water_recover" -> YukkuriAction.WATER_RECOVER;
            case "water_hat_remove" -> YukkuriAction.WATER_HAT_SHAKE;
            case "water_hat_right" -> YukkuriAction.WATER_HAT_PUSH;
            case "water_hat_reach" -> YukkuriAction.WATER_STRUGGLE;
            case "water_hat_grip", "hat_board_grip" -> YukkuriAction.WATER_MOUTH_GRIP;
            case "water_hat_climb" -> YukkuriAction.WATER_HAT_CLIMB;
            case "hat_board_lift" -> YukkuriAction.WATER_BOB_LUNGE;
            case "hat_board_settle" -> YukkuriAction.HAT_SLIDE_OVER;
            case "water_hat_slip" -> YukkuriAction.WATER_GASP;
            case "water_hat_safe" -> YukkuriAction.WATER_RECOVER;
            case "hat_board_align" -> YukkuriAction.BOAT_STEADY;
            case "hat_board_hop" -> YukkuriAction.HAT_HOP_UP;
            case "boat_give_paddle" -> YukkuriAction.BOAT_CALL;
            case "boat_receive_paddle" -> YukkuriAction.HAT_CRADLE;
            case "boat_wait_young", "boat_find_paddle", "boat_seek_bank", "boat_provision" -> YukkuriAction.LOOK_AROUND;
            case "aquatic_hat_care" -> YukkuriAction.AQUATIC_HAT_CARE;
            case "hat_off", "hat_on", "hat_hold", "hat_off_self", "hat_on_self",
                 "hat_off_aq", "hat_on_aq", "hat_hold_aq", "hat_off_self_aq", "hat_on_self_aq" -> YukkuriAction.byId(behavior).orElse(YukkuriAction.IDLE);
            default -> YukkuriAction.IDLE;
        };
    }
    /** Sumo and bullying push; a defender keeps the threat puff. */
    public static YukkuriAction group(String activity) {
        return switch (activity) {
            case "sumo_push", "bully_peer" -> YukkuriAction.PUSH;
            case "sumo_prepare" -> YukkuriAction.BRACE;
            default -> YukkuriAction.PUFF;
        };
    }
    /** Aquatic Marisa in her hat boat: labor and expecting first, asleep at night, then calling to a companion on the bank, steadying a troubled boat, else drifting. */
    public static YukkuriAction boat(YukkuriEntity owner, io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat, String behavior, String reaction) {
        if(boat.rocking()||boat.capsizing())return YukkuriAction.BOAT_STEADY;
        if(behavior.startsWith("braid_rescue_")||behavior.equals("boat_alarm_call")||behavior.equals("boat_alarm_wait")
                ||behavior.equals("boat_calm")||behavior.equals("boat_answer_call"))return action(behavior);
        if(behavior.equals("boat_flee")||behavior.equals("boat_return_called"))
            return boat.paddling()?YukkuriAction.BOAT_PADDLE:YukkuriAction.BOAT_FLEE;
        if (behavior.equals("boat_reassure") || behavior.equals("boat_rescue_young") || behavior.equals("boat_rescue_approach") || behavior.equals("boat_crowd_guard"))
            return boat.paddling() ? YukkuriAction.BOAT_PADDLE : YukkuriAction.BOAT_REASSURE;
        if (owner.family().laboring()) return YukkuriAction.BOAT_LABOR;
        if (boat.flooding() > 0 || owner.isPanicking()) return YukkuriAction.BOAT_STEADY;
        if (owner.life().isSleeping()) return YukkuriAction.BOAT_SLEEP;
        if (owner.life().mealTicks() > 0) return YukkuriAction.BOAT_EAT;
        // BOAT_EAT supplies the existing seated base. YukkuriModel replaces it with the
        // boat_toilet source clip, while HatBoatEntity owns the water-facing rear orientation.
        if (behavior.equals("boat_toilet")) return YukkuriAction.BOAT_EAT;
        if (behavior.equals("family_chew")) return YukkuriAction.BOAT_EAT;
        if (behavior.equals("voyage_balance")) return YukkuriAction.BOAT_FIRST_BALANCE;
        if (behavior.equals("voyage_praise")) return YukkuriAction.BOAT_NUZZLE;
        if (behavior.equals("boat_feed_young") || behavior.equals("feed_child")) return YukkuriAction.BOAT_FEED_YOUNG;
        if (behavior.equals("boat_nuzzle")) return YukkuriAction.BOAT_NUZZLE;
        if (behavior.equals("boat_lullaby")) return YukkuriAction.BOAT_LULLABY;
        // Looking and answering retain a supported seated motion, even while the neighbouring parent is paddling.
        if (behavior.equals("aquatic_watch_parent")) return YukkuriAction.BOAT_DRIFT;
        if (behavior.equals("aquatic_answer_family")) return YukkuriAction.BOAT_LULLABY;
        if (behavior.equals("boat_give_paddle")) return YukkuriAction.BOAT_CALL;
        if (behavior.equals("aquatic_paddle_practice") || behavior.equals("aquatic_imitate_paddle")
                || behavior.equals("boat_follow_family") || behavior.equals("boat_return_family"))
            return owner.getUUID().equals(boat.ownerYukkuri()) && boat.paddling() ? YukkuriAction.BOAT_PADDLE : YukkuriAction.BOAT_DRIFT;
        if (behavior.equals("boat_guard_young")) return boat.paddling() ? YukkuriAction.BOAT_PADDLE : YukkuriAction.BOAT_STEADY;
        if (reaction.equals("awake")) return YukkuriAction.BOAT_WAKE;
        if (behavior.equals("boat_drink")) return YukkuriAction.BOAT_DRINK;
        if (behavior.equals("boat_gather")) return YukkuriAction.BOAT_GATHER;
        if (behavior.equals("boat_call") || behavior.equals("boat_wait_young")) return YukkuriAction.BOAT_CALL;
        if (boat.paddling()) return YukkuriAction.BOAT_PADDLE;
        var brood = owner.family().brood();
        if (brood != null && !brood.cancelled && !brood.complete()) return YukkuriAction.BOAT_BROOD;
        if (behavior.equals("event_river")) return YukkuriAction.BOAT_CALL;
        if (boat.flooding() > 0 || boat.towed() || reaction.equals("afraid")) return YukkuriAction.BOAT_STEADY;
        return YukkuriAction.BOAT_DRIFT;
    }
    /** Short body reaction to the dialogue event of showReaction, used while otherwise idle. */
    public static YukkuriAction reaction(String event) {
        return switch (event) {
            case "hello", "peer", "known_peer", "caregiver", "introduced", "gentle" -> YukkuriAction.GREET;
            case "afraid", "wounded", "water_help", "family_alarm_flee" -> YukkuriAction.TREMBLE;
            case "boat_rock_startle" -> YukkuriAction.STARTLE;
            case "family_alarm_call" -> YukkuriAction.BOAT_ALARM_CALL;
            case "family_alarm_answer" -> YukkuriAction.BOAT_ANSWER_CALL;
            case "family_alarm_calm" -> YukkuriAction.BOAT_CALM;
            case "lost_accessory", "family_died", "lost_parent", "cold", "settlement_hurt", "settlement_submit" -> YukkuriAction.DROOP;
            case "washed" -> YukkuriAction.SHAKE;
            case "toy" -> YukkuriAction.HOP;
            case "newborn", "hatched" -> YukkuriAction.FIRST_WAKE;
            case "birth" -> YukkuriAction.POSTPARTUM;
            case "birth_lost" -> YukkuriAction.BEREAVED;
            case "boat_reassure", "boat_rescue_young", "boat_crowd_guard" -> YukkuriAction.BOAT_REASSURE;
            case "hat_jostle", "hat_scramble", "hat_slipping" -> YukkuriAction.HAT_JOSTLE;
            case "returned_accessory" -> YukkuriAction.SHIAWASE;
            default -> YukkuriAction.IDLE;
        };
    }
    public static String expression(YukkuriEntity e, String fallback) {
        if(e.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat&&(boat.rocking()||boat.capsizing()))
            return e.family().adult()?"surprise":"teary";
        if(!e.isPassenger()&&e.isInWaterOrBubble())return "teary";
        if (e.life().isSleeping() || e.family().awaitingLanding()) return "sleep";
        if (e.isPanicking() || e.hurtTime > 0) return "surprise";
        // Being licked or just comforted eases the crying; it changes the face only, not the wound.
        if (e.woundComfort().receiving() || e.woundComfort().soothed()) {
            if (e.body().wound() > 0 || e.body().burns() > 0) return e.woundComfort().inContact() ? "closed_smile" : "teary";
        }
        if (e.body().wound() > 0 || e.body().burns() > 0 || e.body().infection() > 0 || e.life().spicyTicks() > 0) return "cry";
        if (e.life().constipated() && e.life().bladder() >= 70) return "teary";
        String expression = switch (e.getBehaviorId()) {
            case "comfort_lick" -> "teary";
            case "nuzzle", "listen_song", "exercise_watch", "boat_nuzzle", "boat_receive_care", "boat_listen" -> "closed_smile";
            case "meal_finish" -> "satisfied";
            case "independence_farewell", "independence_visit" -> "happy";
            case "independence_build", "nest_build", "nest_place", "nest_spread", "nest_press", "nest_harvest" -> "chewing_closed";
            case "feed_child" -> "happy";
            case "first_toilet_seek", "first_toilet_wait" -> "teary";
            case "first_toilet_stimulated" -> "surprise";
            case "first_toilet_assist" -> "closed_smile";
            case "newborn_crawl", "learn_jump" -> "happy";
            case "receive_mash" -> "open_smile";
            case "sing" -> (e.level().getGameTime()-e.getBehaviorStartTick())%10 < 7 ? "open_smile" : "closed_smile";
            case "unun_exercise" -> "happy";
            case "sleep_enter" -> "sleepy";
            case "house_sleep" -> "sleep";
            case "house_hide" -> e.getDeltaMovement().horizontalDistanceSqr()>.0001?"neutral":"sleepy";
            case "house_peek" -> "neutral";
            case "house_search_home" -> "teary";
            case "house_startled" -> "surprise";
            case "house_tend" -> "closed_smile";
            case "house_listen" -> "happy";
            case "house_pickup", "house_carry" -> "chewing_closed";
            case "house_deposit" -> "satisfied";
            case "seek_warmth", "warm_up" -> "teary";
            case "threat_watch", "threat_retreat" -> "surprise";
            case "threat_hide", "rain_run", "rain_wait", "rain_huddle" -> "teary";
            case "rain_shake" -> "neutral";
            case "seek_shade", "shade_rest" -> "sleepy";
            case "wake" -> "yawn";
            case "toilet", "ground_toilet", "boat_toilet" -> "chewing_closed";
            case "toilet_accident" -> "teary";
            case "seek_toilet" -> e.life().bladder() >= 90 ? "teary" : "neutral";
            case "take_food", "seek_food" -> "drool";
            case "boat_paddle", "boat_drift" -> "closed_smile";
            case "aquatic_watch_parent" -> "neutral";
            case "aquatic_answer_family" -> (e.level().getGameTime()-e.getBehaviorStartTick())%20 < 7 ? "open_smile" : "closed_smile";
            case "aquatic_paddle_practice", "boat_follow_family" -> "happy";
            case "aquatic_imitate_paddle", "boat_guard_young" -> "chewing_closed";
            case "boat_give_paddle", "boat_receive_paddle" -> "closed_smile";
            case "boat_return_family" -> e.life().nutrition()<30 ? "drool" : "teary";
            case "hat_brim", "voyage_out", "voyage_proud", "voyage_praise" -> "happy";
            case "voyage_prepare", "voyage_watch", "voyage_return" -> "neutral";
            case "voyage_balance" -> "chewing_closed";
            case "boat_feed_young", "aquatic_hat_care" -> "happy";
            case "boat_beg", "boat_provision" -> "drool";
            case "boat_lullaby" -> (e.level().getGameTime()-e.getBehaviorStartTick())%20 < 13 ? "open_smile" : "closed_smile";
            case "boat_gather", "boat_eat" -> "chewing";
            case "boat_drink" -> "chewing_closed";
            case "boat_sleep" -> "sleepy";
            case "boat_call", "boat_wait_young" -> "teary";
            case "boat_steady" -> "surprise";
            case "boat_rocked", "hat_stumble", "hat_jostle", "hat_scramble", "hat_slipping", "boat_crowd_guard" -> "teary";
            case "boat_reassure", "boat_rescue_young" -> "teary";
            case "boat_rescue_approach", "water_hat_remove", "water_hat_right", "water_hat_reach", "water_hat_grip", "water_hat_climb", "water_hat_slip",
                 "hat_board_align", "hat_board_hop", "hat_board_grip", "hat_board_lift", "hat_board_settle" -> "teary";
            case "water_hat_safe" -> "sad";
            case "boat_alarm_call", "boat_answer_call" -> "surprise";
            case "boat_alarm_wait", "boat_flee", "boat_return_called", "braid_rescue_reach", "braid_rescue_hold", "braid_rescue_offer", "braid_be_rescued" -> "teary";
            case "braid_rescue_push" -> "closed_smile";
            case "boat_calm" -> e.level().getGameTime()-e.aquaticAlarmStartTick()<40?"teary":"neutral";
            case "braid_rescue_settle" -> "neutral";
            case "water_impact" -> "cry";
            case "water_struggle", "water_exhausted", "water_recover" -> "teary";
            case "chew", "family_chew" -> (e.level().getGameTime()-e.getBehaviorStartTick())%8 < 4 ? "chewing" : "chewing_closed";
            case "swallow", "drink" -> "chewing_closed";
            case "bully_peer", "protect_peer", "sumo_push" -> "angry";
            default -> e.contactPose().equals("RUB") ? "closed_smile"
                    : e.life().nutrition() < 30 ? "drool" : e.life().energy() < 25 ? "sleepy"
                    : e.dailyLife().affectionNeed() >= 80 && fallback.equals("neutral") ? "teary" : fallback;
        };
        // Predator authoring is a separate task and does not use these ordinary-species face atlases.
        if(e.getVariant().isPredator())return switch(expression){
            case "closed_smile", "satisfied", "open_smile" -> "happy";
            case "chewing", "chewing_closed" -> "neutral";
            default -> expression;
        };
        return expression;
    }
    private EcologyPresentation() {}
}
