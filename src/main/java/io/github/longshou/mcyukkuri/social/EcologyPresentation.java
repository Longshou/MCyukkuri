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
            case "take_food", "chew", "swallow" -> YukkuriAction.EAT;
            case "meal_finish" -> YukkuriAction.SHIAWASE;
            case "drink" -> YukkuriAction.DRINK;
            case "sleep_enter", "sleep" -> YukkuriAction.SLEEP;
            case "wake" -> YukkuriAction.WAKE;
            case "nuzzle" -> YukkuriAction.NUZZLE;
            case "sing" -> YukkuriAction.SING;
            case "listen_song", "exercise_watch" -> YukkuriAction.SWAY;
            case "search_family", "wait_child" -> YukkuriAction.LOOK_AROUND;
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
    /** Short body reaction to the dialogue event of showReaction, used while otherwise idle. */
    public static YukkuriAction reaction(String event) {
        return switch (event) {
            case "hello", "peer", "known_peer", "caregiver", "introduced", "gentle" -> YukkuriAction.GREET;
            case "afraid", "wounded" -> YukkuriAction.TREMBLE;
            case "lost_accessory", "family_died", "lost_parent", "cold" -> YukkuriAction.DROOP;
            case "washed" -> YukkuriAction.SHAKE;
            case "toy" -> YukkuriAction.HOP;
            default -> YukkuriAction.IDLE;
        };
    }
    public static String expression(YukkuriEntity e, String fallback) {
        if (e.life().isSleeping() || e.family().awaitingLanding()) return "sleep";
        if (e.isPanicking() || e.hurtTime > 0) return "surprise";
        if (e.body().wound() > 0 || e.body().burns() > 0 || e.body().infection() > 0 || e.life().spicyTicks() > 0) return "cry";
        if (e.life().constipated() && e.life().bladder() >= 70) return "teary";
        String expression = switch (e.getBehaviorId()) {
            case "nuzzle", "listen_song", "exercise_watch" -> "closed_smile";
            case "meal_finish" -> "satisfied";
            case "sing" -> (e.level().getGameTime()-e.getBehaviorStartTick())%10 < 7 ? "open_smile" : "closed_smile";
            case "unun_exercise" -> "happy";
            case "sleep_enter" -> "sleepy";
            case "wake" -> "yawn";
            case "toilet", "ground_toilet" -> "chewing_closed";
            case "toilet_accident" -> "teary";
            case "seek_toilet" -> e.life().bladder() >= 90 ? "teary" : "neutral";
            case "take_food", "seek_food" -> "drool";
            case "chew" -> (e.level().getGameTime()-e.getBehaviorStartTick())%8 < 4 ? "chewing" : "chewing_closed";
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
