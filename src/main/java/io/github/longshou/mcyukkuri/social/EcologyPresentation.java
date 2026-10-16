package io.github.longshou.mcyukkuri.social;

import io.github.longshou.mcyukkuri.entity.YukkuriAction;
import io.github.longshou.mcyukkuri.entity.YukkuriEntity;

/** Semantic state wins over a stale reaction; the five added atlases retain approved UV/geometry. */
public final class EcologyPresentation {
    /** Body motion for a stationary behavior; walking is chosen before this (see motions/variants/lore). */
    public static YukkuriAction action(String behavior) {
        return switch (behavior) {
            case "unun_exercise" -> YukkuriAction.TAISO;
            case "toilet", "ground_toilet", "toilet_accident" -> YukkuriAction.BRACE;
            case "take_food", "chew", "family_chew", "swallow" -> YukkuriAction.EAT;
            case "receive_mash" -> YukkuriAction.DRINK;
            case "meal_finish" -> YukkuriAction.SHIAWASE;
            case "drink" -> YukkuriAction.DRINK;
            case "sleep_enter", "sleep", "shade_rest" -> YukkuriAction.SLEEP;
            case "seek_warmth", "warm_up" -> YukkuriAction.TREMBLE;
            case "seek_shade" -> YukkuriAction.LOOK_AROUND;
            case "independence_build", "nest_build" -> YukkuriAction.BRACE;
            case "feed_child" -> YukkuriAction.NUZZLE;
            case "nest_gather" -> YukkuriAction.LOOK_AROUND;
            case "independence_farewell", "independence_visit" -> YukkuriAction.GREET;
            case "independence_gather", "independence_return" -> YukkuriAction.LOOK_AROUND;
            case "wake" -> YukkuriAction.WAKE;
            case "nuzzle" -> YukkuriAction.NUZZLE;
            case "sing" -> YukkuriAction.SING;
            case "listen_song", "exercise_watch" -> YukkuriAction.SWAY;
            case "search_family", "wait_child", "stay_home" -> YukkuriAction.LOOK_AROUND;
            case "tend_brood" -> YukkuriAction.BROOD;
            case "labor" -> YukkuriAction.LABOR;
            case "midwife" -> YukkuriAction.MIDWIFE;
            case "boat_launch", "voyage_launch" -> YukkuriAction.HAT_OFF_SELF_AQ;
            case "voyage_prepare", "voyage_wait_parent", "voyage_watch" -> YukkuriAction.LOOK_AROUND;
            case "voyage_praise" -> YukkuriAction.GREET;
            case "voyage_proud" -> YukkuriAction.SHIAWASE;
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
        if (owner.family().laboring()) return YukkuriAction.BOAT_LABOR;
        if (boat.flooding() > 0 || owner.isPanicking()) return YukkuriAction.BOAT_STEADY;
        if (owner.life().isSleeping()) return YukkuriAction.BOAT_SLEEP;
        if (owner.life().mealTicks() > 0) return YukkuriAction.BOAT_EAT;
        if (behavior.equals("family_chew")) return YukkuriAction.BOAT_EAT;
        if (behavior.equals("voyage_balance")) return YukkuriAction.BOAT_FIRST_BALANCE;
        if (behavior.equals("voyage_praise")) return YukkuriAction.BOAT_NUZZLE;
        if (behavior.equals("boat_feed_young") || behavior.equals("feed_child")) return YukkuriAction.BOAT_FEED_YOUNG;
        if (behavior.equals("boat_nuzzle")) return YukkuriAction.BOAT_NUZZLE;
        if (behavior.equals("boat_lullaby")) return YukkuriAction.BOAT_LULLABY;
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
            case "afraid", "wounded" -> YukkuriAction.TREMBLE;
            case "lost_accessory", "family_died", "lost_parent", "cold", "settlement_hurt", "settlement_submit" -> YukkuriAction.DROOP;
            case "washed" -> YukkuriAction.SHAKE;
            case "toy" -> YukkuriAction.HOP;
            case "newborn", "hatched" -> YukkuriAction.FIRST_WAKE;
            case "birth" -> YukkuriAction.POSTPARTUM;
            case "birth_lost" -> YukkuriAction.BEREAVED;
            case "returned_accessory" -> YukkuriAction.SHIAWASE;
            default -> YukkuriAction.IDLE;
        };
    }
    public static String expression(YukkuriEntity e, String fallback) {
        if (e.life().isSleeping() || e.family().awaitingLanding()) return "sleep";
        if (e.isPanicking() || e.hurtTime > 0) return "surprise";
        if (e.body().wound() > 0 || e.body().burns() > 0 || e.body().infection() > 0 || e.life().spicyTicks() > 0) return "cry";
        if (e.life().constipated() && e.life().bladder() >= 70) return "teary";
        String expression = switch (e.getBehaviorId()) {
            case "nuzzle", "listen_song", "exercise_watch", "boat_nuzzle", "boat_receive_care", "boat_listen" -> "closed_smile";
            case "meal_finish" -> "satisfied";
            case "independence_farewell", "independence_visit" -> "happy";
            case "independence_build", "nest_build" -> "chewing_closed";
            case "feed_child" -> "happy";
            case "receive_mash" -> "open_smile";
            case "sing" -> (e.level().getGameTime()-e.getBehaviorStartTick())%10 < 7 ? "open_smile" : "closed_smile";
            case "unun_exercise" -> "happy";
            case "sleep_enter" -> "sleepy";
            case "seek_warmth", "warm_up" -> "teary";
            case "seek_shade", "shade_rest" -> "sleepy";
            case "wake" -> "yawn";
            case "toilet", "ground_toilet" -> "chewing_closed";
            case "toilet_accident" -> "teary";
            case "seek_toilet" -> e.life().bladder() >= 90 ? "teary" : "neutral";
            case "take_food", "seek_food" -> "drool";
            case "boat_paddle", "boat_drift" -> "closed_smile";
            case "hat_brim", "voyage_out", "voyage_proud", "voyage_praise" -> "happy";
            case "voyage_prepare", "voyage_watch", "voyage_return" -> "neutral";
            case "voyage_balance" -> "chewing_closed";
            case "boat_feed_young", "aquatic_hat_care" -> "happy";
            case "boat_beg", "boat_provision" -> "drool";
            case "boat_lullaby" -> (e.level().getGameTime()-e.getBehaviorStartTick())%20 < 13 ? "open_smile" : "closed_smile";
            case "boat_gather", "boat_eat" -> "chewing";
            case "boat_drink" -> "chewing_closed";
            case "boat_call", "boat_wait_young" -> "teary";
            case "boat_steady" -> "surprise";
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
